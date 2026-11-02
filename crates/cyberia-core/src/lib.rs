//! Núcleo de Cyberia para la PC.
//!
//! Indexa la carpeta de audios, arma el manifiesto, genera el certificado propio,
//! maneja el emparejamiento por QR y sirve la API HTTPS local que consulta el celular.
//! La app de bandeja (Tauri) y el servidor sin interfaz (`cyberia-server`) usan este crate.

pub mod cert;
pub mod config;
mod core;
pub mod discovery;
pub mod library;
pub mod manifest;
pub mod net;
pub mod pairing;
pub mod report;
mod server;
mod util;

pub use crate::core::{
    Core, CoreError, CoreEvent, CoreOptions, DeviceView, LibraryItem, LibraryView, PlaylistView,
    Snapshot, SyncOutcome,
};

/// Versión de la API HTTPS que habla el celular.
pub const API_VERSION: u32 = 1;
/// Servicio mDNS con el que se anuncia la PC.
pub const SERVICE_TYPE: &str = "_cyberia._tcp.local.";
/// Puerto preferido. Si está ocupado se usa otro y se recuerda.
pub const DEFAULT_PORT: u16 = 47474;
