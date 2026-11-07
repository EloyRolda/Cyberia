//! Estado central de la app de PC.

use serde::Serialize;
use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Instant;
use tokio::sync::broadcast;

use crate::android_app::AndroidApp;
use crate::cert::{self, Identity};
use crate::config::{self, PairedDevice, Settings};
use crate::library::{self, AudioFile, HashCache, Scan, ScanError};
use crate::manifest::Manifest;
use crate::pairing::{self, PairingOffer, PairingSession, QrData};
use crate::report::{StoredReport, SyncReport};
use crate::{net, util, DEFAULT_PORT};

#[derive(Debug, Clone)]
pub struct CoreOptions {
    /// Carpeta donde se guardan la configuración, el certificado y el manifiesto.
    pub data_dir: PathBuf,
    /// Puerto a usar. `None` usa el último o el predeterminado.
    pub port: Option<u16>,
    /// Nombre de la PC la primera vez. Por defecto, el nombre del equipo.
    pub pc_name: Option<String>,
    /// Anunciar la PC por mDNS.
    pub advertise_mdns: bool,
    pub bind_ip: IpAddr,
}

impl CoreOptions {
    pub fn new(data_dir: impl Into<PathBuf>) -> Self {
        Self {
            data_dir: data_dir.into(),
            port: None,
            pc_name: None,
            advertise_mdns: true,
            bind_ip: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error("Todavía no elegiste una carpeta de audios.")]
    NoFolder,
    #[error("No se encuentra la carpeta {0}. ¿Está conectado el disco?")]
    FolderMissing(String),
    #[error("{0}")]
    Other(#[from] anyhow::Error),
}

/// Avisos para la interfaz.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum CoreEvent {
    /// Cambió algo del estado: pedir un `snapshot` nuevo.
    StateChanged,
    #[serde(rename_all = "camelCase")]
    Paired { device_name: String },
    Unpaired,
    #[serde(rename_all = "camelCase")]
    PhoneConnection { connected: bool },
    #[serde(rename_all = "camelCase")]
    Report { report: StoredReport },
    #[serde(rename_all = "camelCase")]
    Published { version: u64, changed: bool },
}

/// Avisos para la conexión de eventos del celular.
#[derive(Debug, Clone)]
pub(crate) enum PhoneSignal {
    Version(u64),
    Unpaired,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncOutcome {
    pub version: u64,
    /// `false` si no había cambios respecto de lo publicado.
    pub changed: bool,
    pub files: usize,
    pub bytes: u64,
    pub phone_connected: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub pc_id: String,
    pub pc_name: String,
    pub port: Option<u16>,
    pub fingerprint: String,
    pub lan_addresses: Vec<String>,
    pub folder: Option<String>,
    pub folder_exists: bool,
    pub manifest_version: u64,
    pub published_at_ms: u64,
    pub published_count: usize,
    pub published_bytes: u64,
    /// Hay audios agregados, cambiados o borrados que todavía no se publicaron.
    pub pending_changes: bool,
    pub library: Option<LibraryView>,
    pub device: Option<DeviceView>,
    pub phone_connected: bool,
    pub phone_last_seen_ms: Option<u64>,
    pub last_report: Option<StoredReport>,
    pub pairing: Option<PairingOffer>,
    /// App del celular que trae esta PC para actualizarlo.
    pub android_app: Option<AndroidApp>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceView {
    pub name: String,
    pub paired_at_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryView {
    pub playlists: Vec<PlaylistView>,
    pub total_files: usize,
    pub total_bytes: u64,
    pub unsupported_count: usize,
    pub errors: Vec<ScanError>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistView {
    /// Nombre de la subcarpeta; `""` para los audios sueltos en la raíz.
    pub name: String,
    pub items: Vec<LibraryItem>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryItem {
    pub name: String,
    pub path: String,
    pub size: u64,
    pub supported: bool,
}

struct Paths {
    settings: PathBuf,
    manifest: PathBuf,
    cache: PathBuf,
    report: PathBuf,
    identity_dir: PathBuf,
}

impl Paths {
    fn new(data_dir: &Path) -> Self {
        Self {
            settings: data_dir.join("settings.json"),
            manifest: data_dir.join("manifest.json"),
            cache: data_dir.join("hash-cache.json"),
            report: data_dir.join("last-report.json"),
            identity_dir: data_dir.join("identity"),
        }
    }
}

struct Inner {
    settings: Settings,
    manifest: Manifest,
    cache: HashCache,
    last_scan: Option<Scan>,
    pairing: Option<PairingSession>,
    last_report: Option<StoredReport>,
    last_seen_ms: Option<u64>,
    bound_port: Option<u16>,
    android_app: Option<AndroidApp>,
}

pub struct Core {
    options: CoreOptions,
    paths: Paths,
    identity: Identity,
    inner: Mutex<Inner>,
    scan_lock: tokio::sync::Mutex<()>,
    sse_count: AtomicUsize,
    phone_tx: broadcast::Sender<PhoneSignal>,
    events_tx: broadcast::Sender<CoreEvent>,
    mdns: Mutex<Option<mdns_sd::ServiceDaemon>>,
    server: Mutex<Option<axum_server::Handle>>,
}

impl Core {
    /// Carga (o crea) la configuración, el certificado y el último manifiesto publicado.
    pub fn open(options: CoreOptions) -> anyhow::Result<Arc<Self>> {
        std::fs::create_dir_all(&options.data_dir)?;
        let paths = Paths::new(&options.data_dir);
        let default_name = options.pc_name.clone();
        let settings = Settings::load_or_create(&paths.settings, || {
            default_name.unwrap_or_else(|| {
                gethostname::gethostname()
                    .to_string_lossy()
                    .trim()
                    .to_string()
            })
        })?;
        let identity = cert::load_or_create(&paths.identity_dir)?;
        let manifest = Manifest::load(&paths.manifest)?;
        let cache: HashCache = util::read_json(&paths.cache).ok().flatten().unwrap_or_default();
        let last_report = util::read_json(&paths.report).ok().flatten();
        let (phone_tx, _) = broadcast::channel(16);
        let (events_tx, _) = broadcast::channel(64);

        Ok(Arc::new(Self {
            options,
            paths,
            identity,
            inner: Mutex::new(Inner {
                settings,
                manifest,
                cache,
                last_scan: None,
                pairing: None,
                last_report,
                last_seen_ms: None,
                bound_port: None,
                android_app: None,
            }),
            scan_lock: tokio::sync::Mutex::new(()),
            sse_count: AtomicUsize::new(0),
            phone_tx,
            events_tx,
            mdns: Mutex::new(None),
            server: Mutex::new(None),
        }))
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn subscribe(&self) -> broadcast::Receiver<CoreEvent> {
        self.events_tx.subscribe()
    }

    fn emit(&self, event: CoreEvent) {
        let _ = self.events_tx.send(event);
    }

    pub fn identity(&self) -> &Identity {
        &self.identity
    }

    pub fn data_dir(&self) -> &Path {
        &self.options.data_dir
    }

    /// Levanta la API HTTPS y el anuncio mDNS. Tiene que llamarse dentro de un runtime de tokio.
    pub async fn start(self: &Arc<Self>) -> anyhow::Result<SocketAddr> {
        let preferred = self
            .options
            .port
            .or(self.lock().settings.port)
            .unwrap_or(DEFAULT_PORT);
        let listener = match std::net::TcpListener::bind((self.options.bind_ip, preferred)) {
            Ok(l) => l,
            Err(err) if self.options.port.is_none() => {
                tracing::warn!("el puerto {preferred} no está libre ({err}); uso otro");
                std::net::TcpListener::bind((self.options.bind_ip, 0))?
            }
            Err(err) => return Err(err.into()),
        };
        listener.set_nonblocking(true)?;
        let addr = listener.local_addr()?;

        {
            let mut inner = self.lock();
            inner.bound_port = Some(addr.port());
            if inner.settings.port != Some(addr.port()) {
                inner.settings.port = Some(addr.port());
                inner.settings.save(&self.paths.settings)?;
            }
        }

        let handle = crate::server::spawn(self.clone(), listener)?;
        *self.server.lock().unwrap() = Some(handle);

        if self.options.advertise_mdns {
            let (pc_id, pc_name) = {
                let inner = self.lock();
                (inner.settings.pc_id.clone(), inner.settings.pc_name.clone())
            };
            match crate::discovery::advertise(&pc_id, &pc_name, addr.port()) {
                Ok(daemon) => *self.mdns.lock().unwrap() = Some(daemon),
                Err(err) => tracing::warn!("no se pudo anunciar la PC por mDNS: {err:#}"),
            }
        }
        tracing::info!("Cyberia escucha en https://{addr}");
        Ok(addr)
    }

    pub fn shutdown(&self) {
        if let Some(handle) = self.server.lock().unwrap().take() {
            handle.graceful_shutdown(Some(std::time::Duration::from_secs(2)));
        }
        if let Some(daemon) = self.mdns.lock().unwrap().take() {
            let _ = daemon.shutdown();
        }
    }

    pub fn port(&self) -> Option<u16> {
        self.lock().bound_port
    }

    pub fn snapshot(&self) -> Snapshot {
        let mut inner = self.lock();
        if inner.pairing.as_ref().is_some_and(|p| p.is_expired()) {
            inner.pairing = None;
        }
        let folder = inner.settings.folder.clone();
        let folder_exists = folder.as_ref().is_some_and(|f| f.is_dir());
        let pending_changes = inner
            .last_scan
            .as_ref()
            .is_some_and(|scan| !inner.manifest.same_files(&scan.files));
        Snapshot {
            pc_id: inner.settings.pc_id.clone(),
            pc_name: inner.settings.pc_name.clone(),
            port: inner.bound_port,
            fingerprint: self.identity.fingerprint.clone(),
            lan_addresses: net::lan_ipv4_addrs().iter().map(|ip| ip.to_string()).collect(),
            folder: folder.map(|f| f.display().to_string()),
            folder_exists,
            manifest_version: inner.manifest.version,
            published_at_ms: inner.manifest.published_at_ms,
            published_count: inner.manifest.files.len(),
            published_bytes: inner.manifest.total_bytes(),
            pending_changes,
            library: inner.last_scan.as_ref().map(library_view),
            device: inner.settings.device.as_ref().map(|d| DeviceView {
                name: d.name.clone(),
                paired_at_ms: d.paired_at_ms,
            }),
            phone_connected: self.phone_connected(),
            phone_last_seen_ms: inner.last_seen_ms,
            last_report: inner.last_report.clone(),
            pairing: inner.pairing.as_ref().map(|p| p.offer.clone()),
            android_app: inner.android_app.clone(),
        }
    }

    // ---- Carpeta y biblioteca ----

    /// Usa `path` como carpeta de audios; la crea si no existe (RF-08).
    pub fn set_folder(&self, path: &Path) -> Result<(), CoreError> {
        std::fs::create_dir_all(path).map_err(anyhow::Error::from)?;
        let path = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
        {
            let mut inner = self.lock();
            inner.settings.folder = Some(path);
            inner.last_scan = None;
            inner.settings.save(&self.paths.settings)?;
        }
        self.emit(CoreEvent::StateChanged);
        Ok(())
    }

    pub fn folder(&self) -> Option<PathBuf> {
        self.lock().settings.folder.clone()
    }

    /// Recorre la carpeta y actualiza la lista que muestra la interfaz (RF-09).
    pub async fn refresh_library(self: &Arc<Self>) -> Result<LibraryView, CoreError> {
        let scan = self.scan().await?;
        let view = library_view(&scan);
        self.emit(CoreEvent::StateChanged);
        Ok(view)
    }

    async fn scan(self: &Arc<Self>) -> Result<Scan, CoreError> {
        let _guard = self.scan_lock.lock().await;
        let folder = self.folder().ok_or(CoreError::NoFolder)?;
        if !folder.is_dir() {
            return Err(CoreError::FolderMissing(folder.display().to_string()));
        }
        let mut cache = self.lock().cache.clone();
        let this = self.clone();
        let (scan, cache) = tokio::task::spawn_blocking(move || {
            let result = library::scan(&folder, &mut cache);
            (result, cache)
        })
        .await
        .map_err(anyhow::Error::from)?;
        let scan = scan.map_err(anyhow::Error::from)?;
        if let Err(err) = util::write_json(&this.paths.cache, &cache) {
            tracing::warn!("no se pudo guardar la caché de huellas: {err:#}");
        }
        let mut inner = this.lock();
        inner.cache = cache;
        inner.last_scan = Some(scan.clone());
        Ok(scan)
    }

    /// Botón Sincronizar (RF-10): recorre la carpeta, publica una versión nueva del manifiesto si
    /// hubo cambios y avisa al celular conectado. Sin carpeta no se publica nada, para no vaciar
    /// el celular si se desconectó el disco.
    pub async fn sync_now(self: &Arc<Self>) -> Result<SyncOutcome, CoreError> {
        let scan = self.scan().await?;
        let (version, changed, files, bytes) = {
            let mut inner = self.lock();
            let changed = !inner.manifest.same_files(&scan.files) || inner.manifest.version == 0;
            if changed {
                inner.manifest.version += 1;
                inner.manifest.files = scan.files.clone();
                inner.manifest.published_at_ms = util::now_ms();
                inner.manifest.save(&self.paths.manifest)?;
            }
            (
                inner.manifest.version,
                changed,
                inner.manifest.files.len(),
                inner.manifest.total_bytes(),
            )
        };
        let _ = self.phone_tx.send(PhoneSignal::Version(version));
        self.emit(CoreEvent::Published { version, changed });
        self.emit(CoreEvent::StateChanged);
        Ok(SyncOutcome {
            version,
            changed,
            files,
            bytes,
            phone_connected: self.phone_connected(),
        })
    }

    // ---- Emparejamiento ----

    /// Genera un QR nuevo de un solo uso que vence a los 5 minutos (RF-01, RF-02).
    pub fn start_pairing(&self) -> anyhow::Result<PairingOffer> {
        let mut inner = self.lock();
        let port = inner
            .bound_port
            .ok_or_else(|| anyhow::anyhow!("el servidor todavía no arrancó"))?;
        // Si el servidor escucha en una IP concreta, esa es la única que sirve.
        let hosts: Vec<IpAddr> = if self.options.bind_ip.is_unspecified() {
            net::lan_ipv4_addrs()
                .into_iter()
                .take(4)
                .map(IpAddr::V4)
                .collect()
        } else {
            vec![self.options.bind_ip]
        };
        let code = pairing::new_code();
        let uri = pairing::build_uri(&QrData {
            pc_id: &inner.settings.pc_id,
            pc_name: &inner.settings.pc_name,
            hosts: &hosts,
            port,
            fingerprint: &self.identity.fingerprint,
            code: &code,
        });
        let expires_at_ms = util::now_ms() + pairing::PAIRING_TTL.as_millis() as u64;
        let offer = PairingOffer {
            qr_svg: pairing::qr_svg(&uri)?,
            uri,
            expires_at_ms,
            hosts: hosts.iter().map(|h| h.to_string()).collect(),
            port,
        };
        inner.pairing = Some(PairingSession {
            code,
            created: Instant::now(),
            expires_at_ms,
            failed_attempts: 0,
            offer: offer.clone(),
        });
        drop(inner);
        self.emit(CoreEvent::StateChanged);
        Ok(offer)
    }

    pub fn cancel_pairing(&self) {
        self.lock().pairing = None;
        self.emit(CoreEvent::StateChanged);
    }

    pub(crate) fn pair_device(
        &self,
        code: &str,
        device_id: &str,
        device_name: &str,
    ) -> Result<(String, String, String), PairError> {
        let mut inner = self.lock();
        let session = inner.pairing.as_mut().ok_or(PairError::NoSession)?;
        if session.is_expired() {
            inner.pairing = None;
            return Err(PairError::Expired);
        }
        if !session.code_matches(code) {
            session.failed_attempts += 1;
            if session.failed_attempts >= pairing::MAX_FAILED_ATTEMPTS {
                inner.pairing = None;
                return Err(PairError::TooManyAttempts);
            }
            return Err(PairError::BadCode);
        }
        // Un solo uso (RF-02).
        inner.pairing = None;

        let token = util::random_token(32);
        let name = sanitize_device_name(device_name);
        // Un celular por PC en el MVP: el nuevo reemplaza al anterior.
        let _ = self.phone_tx.send(PhoneSignal::Unpaired);
        inner.settings.device = Some(PairedDevice {
            id: device_id.chars().take(64).collect(),
            name: name.clone(),
            token_hash: config::hash_token(&token),
            paired_at_ms: util::now_ms(),
        });
        inner.last_report = None;
        inner.last_seen_ms = Some(util::now_ms());
        let _ = std::fs::remove_file(&self.paths.report);
        inner
            .settings
            .save(&self.paths.settings)
            .map_err(PairError::Storage)?;
        let pc_id = inner.settings.pc_id.clone();
        let pc_name = inner.settings.pc_name.clone();
        drop(inner);
        self.emit(CoreEvent::Paired { device_name: name });
        self.emit(CoreEvent::StateChanged);
        Ok((token, pc_id, pc_name))
    }

    /// Desvincula el celular (RF-07). El celular se entera por la conexión de avisos o con un 401.
    pub fn unpair(&self) -> anyhow::Result<()> {
        {
            let mut inner = self.lock();
            if inner.settings.device.take().is_none() {
                return Ok(());
            }
            inner.last_report = None;
            inner.last_seen_ms = None;
            inner.settings.save(&self.paths.settings)?;
        }
        let _ = std::fs::remove_file(&self.paths.report);
        let _ = self.phone_tx.send(PhoneSignal::Unpaired);
        self.emit(CoreEvent::Unpaired);
        self.emit(CoreEvent::StateChanged);
        Ok(())
    }

    // ---- Usado por la API ----

    pub(crate) fn authenticate(&self, token: &str) -> bool {
        let mut inner = self.lock();
        let ok = inner
            .settings
            .device
            .as_ref()
            .is_some_and(|d| d.token_matches(token));
        if ok {
            inner.last_seen_ms = Some(util::now_ms());
        }
        ok
    }

    pub(crate) fn info(&self) -> (String, String, u64, Option<AndroidApp>) {
        let inner = self.lock();
        (
            inner.settings.pc_id.clone(),
            inner.settings.pc_name.clone(),
            inner.manifest.version,
            inner.android_app.clone(),
        )
    }

    /// Define la app del celular que ofrece esta PC (la que viene en el instalador).
    pub fn set_android_app(&self, app: Option<AndroidApp>) {
        if let Some(app) = &app {
            tracing::info!("app del celular disponible: {} ({})", app.version_name, app.version_code);
        }
        self.lock().android_app = app;
        self.emit(CoreEvent::StateChanged);
    }

    pub(crate) fn android_app(&self) -> Option<AndroidApp> {
        self.lock().android_app.clone()
    }

    pub(crate) fn manifest_json(&self) -> anyhow::Result<Vec<u8>> {
        let inner = self.lock();
        Ok(serde_json::to_vec(&inner.manifest.public())?)
    }

    pub(crate) fn manifest_version(&self) -> u64 {
        self.lock().manifest.version
    }

    /// Ruta absoluta y datos publicados del audio con huella `id`.
    pub(crate) fn file_for_id(&self, id: &str) -> Option<(PathBuf, AudioFile)> {
        let inner = self.lock();
        let folder = inner.settings.folder.clone()?;
        let file = inner.manifest.find(id)?.clone();
        let mut abs = folder;
        for part in file.path.split('/') {
            abs.push(part);
        }
        Some((abs, file))
    }

    pub(crate) fn store_report(&self, report: SyncReport) -> anyhow::Result<()> {
        let stored = {
            let mut inner = self.lock();
            let device_name = inner
                .settings
                .device
                .as_ref()
                .map(|d| d.name.clone())
                .unwrap_or_default();
            let stored = StoredReport {
                received_at_ms: util::now_ms(),
                device_name,
                report,
            };
            inner.last_report = Some(stored.clone());
            stored
        };
        util::write_json(&self.paths.report, &stored)?;
        self.emit(CoreEvent::Report { report: stored });
        self.emit(CoreEvent::StateChanged);
        Ok(())
    }

    pub(crate) fn phone_signals(&self) -> broadcast::Receiver<PhoneSignal> {
        self.phone_tx.subscribe()
    }

    /// Se llama al abrir la conexión de avisos. El celular se considera conectado mientras la
    /// guarda siga viva (RF-11).
    pub(crate) fn phone_stream_opened(self: &Arc<Self>) -> PhoneStreamGuard {
        if self.sse_count.fetch_add(1, Ordering::SeqCst) == 0 {
            self.emit(CoreEvent::PhoneConnection { connected: true });
            self.emit(CoreEvent::StateChanged);
        }
        PhoneStreamGuard { core: self.clone() }
    }

    pub fn phone_connected(&self) -> bool {
        self.sse_count.load(Ordering::SeqCst) > 0
    }
}

pub(crate) struct PhoneStreamGuard {
    core: Arc<Core>,
}

impl Drop for PhoneStreamGuard {
    fn drop(&mut self) {
        if self.core.sse_count.fetch_sub(1, Ordering::SeqCst) == 1 {
            self.core.emit(CoreEvent::PhoneConnection { connected: false });
            self.core.emit(CoreEvent::StateChanged);
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub(crate) enum PairError {
    #[error("no hay un código de vinculación activo")]
    NoSession,
    #[error("el código venció")]
    Expired,
    #[error("el código no es válido")]
    BadCode,
    #[error("demasiados intentos")]
    TooManyAttempts,
    #[error("no se pudo guardar la vinculación: {0}")]
    Storage(anyhow::Error),
}

fn sanitize_device_name(name: &str) -> String {
    let clean: String = name
        .chars()
        .filter(|c| !c.is_control())
        .take(60)
        .collect();
    let clean = clean.trim();
    if clean.is_empty() {
        "Celular".to_string()
    } else {
        clean.to_string()
    }
}

fn library_view(scan: &Scan) -> LibraryView {
    let mut groups: BTreeMap<(String, String), Vec<LibraryItem>> = BTreeMap::new();
    for f in &scan.files {
        groups
            .entry((f.playlist.to_lowercase(), f.playlist.clone()))
            .or_default()
            .push(LibraryItem {
                name: f.file_name().to_string(),
                path: f.path.clone(),
                size: f.size,
                supported: true,
            });
    }
    for u in &scan.unsupported {
        groups
            .entry((u.playlist.to_lowercase(), u.playlist.clone()))
            .or_default()
            .push(LibraryItem {
                name: u.path.rsplit('/').next().unwrap_or(&u.path).to_string(),
                path: u.path.clone(),
                size: u.size,
                supported: false,
            });
    }
    let playlists = groups
        .into_iter()
        .map(|((_, name), mut items)| {
            items.sort_by_key(|i| i.name.to_lowercase());
            PlaylistView { name, items }
        })
        .collect();
    LibraryView {
        playlists,
        total_files: scan.files.len(),
        total_bytes: scan.files.iter().map(|f| f.size).sum(),
        unsupported_count: scan.unsupported.len(),
        errors: scan.errors.clone(),
    }
}
