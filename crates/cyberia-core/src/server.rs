//! API HTTPS local que consulta el celular.
//!
//! | Método y ruta           | Para qué                                             |
//! |-------------------------|------------------------------------------------------|
//! | `POST /v1/pair`         | Cambiar el código del QR por un token.               |
//! | `GET /v1/info`          | Nombre de la PC y versión actual del manifiesto.     |
//! | `GET /v1/manifest`      | Lista completa de audios.                            |
//! | `GET /v1/files/{id}`    | Descargar un audio; acepta rangos.                   |
//! | `GET /v1/events`        | Avisos (Server-Sent Events) con la versión nueva.    |
//! | `POST /v1/report`       | El celular informa qué descargó, borró y falló.      |
//! | `DELETE /v1/pair`       | Desvincular el celular.                              |

use axum::body::Body;
use axum::extract::{ConnectInfo, FromRequestParts, Path, Request, State};
use axum::http::{header, request::Parts, StatusCode};
use axum::middleware::{self, Next};
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::Stream;
use serde::Deserialize;
use serde_json::json;
use std::convert::Infallible;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast::error::RecvError;
use tower::ServiceExt;
use tower_http::services::ServeFile;

use crate::core::{Core, PairError, PhoneSignal};
use crate::report::SyncReport;
use crate::util;

type AppState = Arc<Core>;

/// Sin cambios, la PC manda una línea de comentario cada 60 segundos para mantener viva la
/// conexión de avisos.
const KEEP_ALIVE: Duration = Duration::from_secs(60);

pub(crate) fn spawn(core: Arc<Core>, listener: std::net::TcpListener) -> anyhow::Result<axum_server::Handle> {
    let tls = tls_config(&core)?;
    let app = router(core);
    let handle = axum_server::Handle::new();
    let server = axum_server::from_tcp_rustls(listener, tls).handle(handle.clone());
    tokio::spawn(async move {
        if let Err(err) = server
            .serve(app.into_make_service_with_connect_info::<SocketAddr>())
            .await
        {
            tracing::error!("el servidor HTTPS se detuvo: {err}");
        }
    });
    Ok(handle)
}

fn tls_config(core: &Core) -> anyhow::Result<axum_server::tls_rustls::RustlsConfig> {
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    let identity = core.identity();
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(identity.cert_der.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.key_der.clone())),
        )?;
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(axum_server::tls_rustls::RustlsConfig::from_config(Arc::new(config)))
}

pub(crate) fn router(core: Arc<Core>) -> Router {
    Router::new()
        .route("/v1/pair", post(pair).delete(unpair))
        .route("/v1/info", get(info))
        .route("/v1/manifest", get(manifest))
        .route("/v1/files/{id}", get(file))
        .route("/v1/events", get(events))
        .route("/v1/report", post(report))
        .route("/v1/app/android", get(android_app))
        .layer(middleware::from_fn(local_only))
        .with_state(core)
}

/// Solo se aceptan conexiones desde la red local (RNF-05).
async fn local_only(ConnectInfo(addr): ConnectInfo<SocketAddr>, req: Request, next: Next) -> Response {
    if !crate::net::is_local_addr(addr.ip()) {
        tracing::warn!("conexión rechazada desde {addr}: no es de la red local");
        return error(StatusCode::FORBIDDEN, "not_local", "Solo se aceptan conexiones de la red local.");
    }
    next.run(req).await
}

fn error(status: StatusCode, code: &str, message: &str) -> Response {
    (status, Json(json!({ "error": code, "message": message }))).into_response()
}

/// Pedido de un celular vinculado: exige `Authorization: Bearer <token>`.
struct Authed;

impl FromRequestParts<AppState> for Authed {
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, core: &AppState) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(str::trim);
        match token {
            Some(token) if core.authenticate(token) => Ok(Authed),
            _ => Err(error(StatusCode::UNAUTHORIZED, "unpaired", "Este celular no está vinculado.")),
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairRequest {
    code: String,
    #[serde(default)]
    device_id: String,
    #[serde(default)]
    device_name: String,
}

async fn pair(State(core): State<AppState>, Json(req): Json<PairRequest>) -> Response {
    match core.pair_device(&req.code, &req.device_id, &req.device_name) {
        Ok((token, pc_id, pc_name)) => Json(json!({
            "token": token,
            "pcId": pc_id,
            "pcName": pc_name,
            "apiVersion": crate::API_VERSION,
        }))
        .into_response(),
        Err(PairError::NoSession | PairError::Expired) => error(
            StatusCode::GONE,
            "expired",
            "El código venció. Generá un QR nuevo en la PC.",
        ),
        Err(PairError::BadCode) => error(StatusCode::FORBIDDEN, "bad_code", "El código no es válido."),
        Err(PairError::TooManyAttempts) => error(
            StatusCode::TOO_MANY_REQUESTS,
            "too_many_attempts",
            "Demasiados intentos. Generá un QR nuevo en la PC.",
        ),
        Err(err @ PairError::Storage(_)) => {
            tracing::error!("{err}");
            error(StatusCode::INTERNAL_SERVER_ERROR, "storage", "No se pudo guardar la vinculación.")
        }
    }
}

async fn unpair(State(core): State<AppState>, _auth: Authed) -> Response {
    match core.unpair() {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(err) => {
            tracing::error!("no se pudo desvincular: {err:#}");
            error(StatusCode::INTERNAL_SERVER_ERROR, "storage", "No se pudo desvincular.")
        }
    }
}

async fn info(State(core): State<AppState>, _auth: Authed) -> Response {
    let (pc_id, pc_name, version, android_app) = core.info();
    Json(json!({
        "apiVersion": crate::API_VERSION,
        "pcId": pc_id,
        "pcName": pc_name,
        "manifestVersion": version,
        "androidApp": android_app,
    }))
    .into_response()
}

async fn manifest(State(core): State<AppState>, _auth: Authed) -> Response {
    match core.manifest_json() {
        Ok(body) => ([(header::CONTENT_TYPE, "application/json")], body).into_response(),
        Err(err) => {
            tracing::error!("no se pudo armar el manifiesto: {err:#}");
            error(StatusCode::INTERNAL_SERVER_ERROR, "manifest", "No se pudo armar el manifiesto.")
        }
    }
}

/// Descarga de un audio por su huella. Acepta `Range` para retomar descargas cortadas (RF-23).
async fn file(State(core): State<AppState>, _auth: Authed, Path(id): Path<String>, req: Request) -> Response {
    let Some((abs, entry)) = core.file_for_id(&id) else {
        return error(StatusCode::NOT_FOUND, "not_found", "Ese audio no está en el manifiesto.");
    };
    match tokio::fs::metadata(&abs).await {
        Ok(meta) => {
            let modified = meta.modified().map(util::system_time_ms).unwrap_or(0);
            if meta.len() != entry.size || modified != entry.modified_ms {
                return error(
                    StatusCode::CONFLICT,
                    "changed",
                    "El audio cambió en la PC después de publicarse. Hay que sincronizar de nuevo.",
                );
            }
        }
        Err(_) => {
            return error(StatusCode::GONE, "missing", "El audio ya no está en la carpeta de la PC.");
        }
    }
    match ServeFile::new(&abs).oneshot(req).await {
        Ok(res) => {
            let (mut parts, body) = res.into_parts();
            parts.headers.insert(
                "x-cyberia-id",
                header::HeaderValue::from_str(&entry.id).unwrap_or(header::HeaderValue::from_static("")),
            );
            Response::from_parts(parts, Body::new(body))
        }
        Err(never) => match never {},
    }
}

/// Conexión de avisos (Server-Sent Events). Al abrirse manda la versión actual y después una
/// versión cada vez que se aprieta Sincronizar en la PC.
async fn events(State(core): State<AppState>, _auth: Authed) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let guard = core.phone_stream_opened();
    let rx = core.phone_signals();
    let initial = core.manifest_version();

    let stream = futures_util::stream::unfold(
        (rx, Some(initial), false, guard),
        |(mut rx, initial, done, guard)| async move {
            if done {
                return None;
            }
            if let Some(v) = initial {
                return Some((Ok(version_event(v)), (rx, None, false, guard)));
            }
            loop {
                match rx.recv().await {
                    Ok(PhoneSignal::Version(v)) => {
                        return Some((Ok(version_event(v)), (rx, None, false, guard)))
                    }
                    Ok(PhoneSignal::Unpaired) => {
                        let event = Event::default().event("unpaired").data("{}");
                        return Some((Ok(event), (rx, None, true, guard)));
                    }
                    Err(RecvError::Lagged(_)) => continue,
                    Err(RecvError::Closed) => return None,
                }
            }
        },
    );
    Sse::new(stream).keep_alive(KeepAlive::new().interval(KEEP_ALIVE).text(""))
}

/// La app del celular que trae esta PC, para que el celular se actualice sin cables.
async fn android_app(State(core): State<AppState>, _auth: Authed, req: Request) -> Response {
    let Some(app) = core.android_app() else {
        return error(StatusCode::NOT_FOUND, "no_app", "Esta PC no trae la app del celular.");
    };
    match ServeFile::new(&app.path).oneshot(req).await {
        Ok(res) => {
            let (mut parts, body) = res.into_parts();
            parts.headers.insert(
                header::CONTENT_TYPE,
                header::HeaderValue::from_static("application/vnd.android.package-archive"),
            );
            Response::from_parts(parts, Body::new(body))
        }
        Err(never) => match never {},
    }
}

fn version_event(version: u64) -> Event {
    Event::default()
        .event("version")
        .data(json!({ "version": version }).to_string())
}

async fn report(State(core): State<AppState>, _auth: Authed, Json(report): Json<SyncReport>) -> Response {
    match core.store_report(report) {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(err) => {
            tracing::error!("no se pudo guardar el informe: {err:#}");
            error(StatusCode::INTERNAL_SERVER_ERROR, "storage", "No se pudo guardar el informe.")
        }
    }
}
