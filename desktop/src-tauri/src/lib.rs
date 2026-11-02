//! App de bandeja de Cyberia (RF-08 a RF-14).
//!
//! La ventana es solo una vista: al cerrarla se destruye el WebView y queda únicamente el
//! proceso Rust en la bandeja, sirviendo la API para el celular (RF-14, RNF-01).

use cyberia_core::pairing::PairingOffer;
use cyberia_core::{Core, CoreEvent, CoreOptions, Snapshot, SyncOutcome};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;

const MAIN_WINDOW: &str = "main";
const TRAY_ID: &str = "cyberia";
const EVENT_NAME: &str = "cyberia://event";

struct AppState {
    core: Arc<Core>,
    server_error: Mutex<Option<String>>,
}

/// Estado que ve la interfaz.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct UiState {
    #[serde(flatten)]
    snapshot: Snapshot,
    server_error: Option<String>,
    default_folder: String,
}

fn ui_state(app: &AppHandle, state: &AppState) -> UiState {
    UiState {
        snapshot: state.core.snapshot(),
        server_error: state.server_error.lock().unwrap().clone(),
        default_folder: default_folder(app).display().to_string(),
    }
}

/// `Música/Cyberia` del usuario, o `~/Cyberia` si el sistema no tiene carpeta de música.
fn default_folder(app: &AppHandle) -> PathBuf {
    let path = app.path();
    path.audio_dir()
        .or_else(|_| path.home_dir())
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("Cyberia")
}

#[tauri::command]
fn get_state(app: AppHandle, state: State<'_, AppState>) -> UiState {
    ui_state(&app, &state)
}

#[tauri::command]
async fn refresh_library(app: AppHandle, state: State<'_, AppState>) -> Result<UiState, String> {
    state.core.refresh_library().await.map_err(|e| e.to_string())?;
    Ok(ui_state(&app, &state))
}

#[tauri::command]
async fn sync_now(state: State<'_, AppState>) -> Result<SyncOutcome, String> {
    state.core.sync_now().await.map_err(|e| e.to_string())
}

#[tauri::command]
fn start_pairing(state: State<'_, AppState>) -> Result<PairingOffer, String> {
    state.core.start_pairing().map_err(|e| format!("{e:#}"))
}

#[tauri::command]
fn cancel_pairing(state: State<'_, AppState>) {
    state.core.cancel_pairing();
}

#[tauri::command]
fn unpair(state: State<'_, AppState>) -> Result<(), String> {
    state.core.unpair().map_err(|e| format!("{e:#}"))
}

#[tauri::command]
async fn set_folder(app: AppHandle, state: State<'_, AppState>, path: String) -> Result<UiState, String> {
    state
        .core
        .set_folder(&PathBuf::from(path))
        .map_err(|e| e.to_string())?;
    let _ = state.core.refresh_library().await;
    Ok(ui_state(&app, &state))
}

#[tauri::command]
async fn create_default_folder(app: AppHandle, state: State<'_, AppState>) -> Result<UiState, String> {
    let folder = default_folder(&app);
    state.core.set_folder(&folder).map_err(|e| e.to_string())?;
    let _ = state.core.refresh_library().await;
    Ok(ui_state(&app, &state))
}

#[tauri::command]
fn open_folder(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let folder = state.core.folder().ok_or("Todavía no elegiste una carpeta.")?;
    app.opener()
        .open_path(folder.display().to_string(), None::<&str>)
        .map_err(|e| e.to_string())
}

/// Muestra la ventana; si estaba cerrada, la vuelve a crear.
fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        return;
    }
    let result = WebviewWindowBuilder::new(app, MAIN_WINDOW, WebviewUrl::App("index.html".into()))
        .title("Cyberia")
        .inner_size(460.0, 720.0)
        .min_inner_size(380.0, 520.0)
        .center()
        .build();
    if let Err(err) = result {
        tracing::error!("no se pudo abrir la ventana: {err}");
    }
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Abrir Cyberia", true, None::<&str>)?;
    let sync = MenuItem::with_id(app, "sync", "Sincronizar ahora", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Salir", true, None::<&str>)?;
    let separator = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(app, &[&open, &sync, &separator, &quit])?;

    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("Cyberia")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "sync" => {
                let core = app.state::<AppState>().core.clone();
                tauri::async_runtime::spawn(async move {
                    if let Err(err) = core.sync_now().await {
                        tracing::warn!("no se pudo sincronizar: {err}");
                    }
                });
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

fn update_tray_tooltip(app: &AppHandle, core: &Core) {
    let snap = core.snapshot();
    let text = match (&snap.device, snap.phone_connected) {
        (None, _) => "Cyberia · ningún celular vinculado".to_string(),
        (Some(d), true) => format!("Cyberia · {} conectado", d.name),
        (Some(d), false) => format!("Cyberia · {} sin conexión", d.name),
    };
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_tooltip(Some(text));
    }
}

/// Avisa en la PC el resultado de una sincronización (RF-12), aunque la ventana esté cerrada.
fn notify_report(app: &AppHandle, report: &cyberia_core::report::StoredReport) {
    let r = &report.report;
    if r.downloaded.is_empty() && r.failed.is_empty() && !r.no_space {
        return;
    }
    let device = if report.device_name.is_empty() { "el celular" } else { &report.device_name };
    let title = match r.downloaded.len() {
        0 => format!("No llegaron audios a {device}"),
        1 => format!("1 audio llegó a {device}"),
        n => format!("{n} audios llegaron a {device}"),
    };
    let body = if r.no_space {
        "El celular se quedó sin espacio. No se borró nada para hacer lugar.".to_string()
    } else if let Some(first) = r.failed.first() {
        let name = first.path.rsplit('/').next().unwrap_or(&first.path);
        if r.failed.len() == 1 {
            format!("Falló {name}: {}", first.reason)
        } else {
            format!("Fallaron {} audios. {name}: {}", r.failed.len(), first.reason)
        }
    } else {
        "Todo listo para escuchar.".to_string()
    };
    let _ = app.notification().builder().title(title).body(body).show();
}

pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,mdns_sd=warn".into()),
        )
        .init();

    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app);
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let core = Core::open(CoreOptions::new(data_dir))?;
            app.manage(AppState {
                core: core.clone(),
                server_error: Mutex::new(None),
            });

            // Reenvía los avisos del núcleo a la ventana, la bandeja y las notificaciones.
            let handle = app.handle().clone();
            let mut events = core.subscribe();
            let events_core = core.clone();
            tauri::async_runtime::spawn(async move {
                loop {
                    let event = match events.recv().await {
                        Ok(event) => event,
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(_) => break,
                    };
                    match &event {
                        CoreEvent::Report { report } => notify_report(&handle, report),
                        CoreEvent::PhoneConnection { .. }
                        | CoreEvent::Paired { .. }
                        | CoreEvent::Unpaired => update_tray_tooltip(&handle, &events_core),
                        _ => {}
                    }
                    let _ = handle.emit(EVENT_NAME, &event);
                }
            });

            // Levanta la API y hace un primer recorrido de la carpeta.
            let handle = app.handle().clone();
            let start_core = core.clone();
            tauri::async_runtime::spawn(async move {
                if let Err(err) = start_core.start().await {
                    tracing::error!("no se pudo levantar el servidor: {err:#}");
                    let state = handle.state::<AppState>();
                    *state.server_error.lock().unwrap() = Some(format!(
                        "No se pudo abrir el puerto para el celular: {err}"
                    ));
                }
                if start_core.folder().is_some() {
                    let _ = start_core.refresh_library().await;
                }
                update_tray_tooltip(&handle, &start_core);
                let _ = handle.emit(EVENT_NAME, &CoreEvent::StateChanged);
            });

            build_tray(app.handle())?;
            show_main_window(app.handle());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_state,
            refresh_library,
            sync_now,
            start_pairing,
            cancel_pairing,
            unpair,
            set_folder,
            create_default_folder,
            open_folder,
        ])
        .build(tauri::generate_context!())
        .expect("no se pudo iniciar Cyberia");

    app.run(|app, event| match event {
        // Cerrar la ventana no cierra la app: sigue en la bandeja (RF-14).
        RunEvent::ExitRequested { api, code: None, .. } => api.prevent_exit(),
        RunEvent::Exit => {
            if let Some(state) = app.try_state::<AppState>() {
                state.core.shutdown();
            }
        }
        _ => {}
    });
}
