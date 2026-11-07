//! Actualización automática de la app de PC.
//!
//! Cada versión que publica la CI queda en un repositorio público de versiones, firmada con la
//! clave de actualizaciones. La app revisa al arrancar y cada 6 horas; si hay una más nueva, la
//! baja, verifica la firma y la instala. En Windows el instalador cierra Cyberia y la vuelve a
//! abrir. La versión nueva trae la app del celular de su misma versión, así que después el
//! celular también se actualiza desde la PC.

use std::time::Duration;
use tauri::{AppHandle, Manager, Runtime};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_updater::UpdaterExt;

use crate::AppState;

const FIRST_CHECK: Duration = Duration::from_secs(60);
const INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);

/// `true` si la compilación trae la clave pública y la dirección de las actualizaciones.
pub fn configured<R: Runtime>(context: &tauri::Context<R>) -> bool {
    context
        .config()
        .plugins
        .0
        .get("updater")
        .and_then(|updater| updater.get("pubkey"))
        .and_then(|pubkey| pubkey.as_str())
        .is_some_and(|pubkey| !pubkey.trim().is_empty())
}

/// Revisa al arrancar y después cada 6 horas.
pub async fn run(app: AppHandle) {
    tokio::time::sleep(FIRST_CHECK).await;
    loop {
        if let Err(err) = check_and_install(&app).await {
            tracing::warn!("no se pudo buscar actualizaciones: {err:#}");
        }
        tokio::time::sleep(INTERVAL).await;
    }
}

/// Busca una versión nueva y, si la hay, la instala. Devuelve `false` si ya estaba al día.
pub async fn check_and_install(app: &AppHandle) -> anyhow::Result<bool> {
    let core = app.state::<AppState>().core.clone();
    let updater = app
        .updater_builder()
        // Cierra el servidor antes de que el instalador reemplace la app.
        .on_before_exit(move || core.shutdown())
        .build()?;
    let Some(update) = updater.check().await? else {
        return Ok(false);
    };
    tracing::info!("instalando Cyberia {}", update.version);
    let _ = app
        .notification()
        .builder()
        .title("Actualizando Cyberia")
        .body(format!(
            "Se instala la versión {}. Cyberia se vuelve a abrir sola en unos segundos.",
            update.version
        ))
        .show();
    update.download_and_install(|_, _| {}, || {}).await?;
    // En Windows el instalador ya cerró la app y la vuelve a abrir; en otros sistemas, se reinicia.
    app.restart();
}
