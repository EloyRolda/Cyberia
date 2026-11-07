//! Resultado de una sincronización, informado por el celular (RF-25).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncReport {
    /// Versión del manifiesto contra la que sincronizó el celular.
    pub manifest_version: u64,
    #[serde(default)]
    pub downloaded: Vec<ReportItem>,
    #[serde(default)]
    pub deleted: Vec<ReportItem>,
    #[serde(default)]
    pub failed: Vec<FailedItem>,
    /// Audios que se borrarían pero esperan confirmación en el celular (RF-20).
    #[serde(default)]
    pub pending_deletions: u32,
    /// El celular se quedó sin espacio (RF-26).
    #[serde(default)]
    pub no_space: bool,
    #[serde(default)]
    pub free_bytes: Option<u64>,
    /// Audios que tiene el celular después de sincronizar.
    #[serde(default)]
    pub library_count: u32,
    #[serde(default)]
    pub library_bytes: u64,
    /// El celular tiene todo lo del manifiesto.
    #[serde(default)]
    pub complete: bool,
    /// Versión de la app instalada en el celular.
    #[serde(default)]
    pub app_version_code: Option<u64>,
    #[serde(default)]
    pub app_version_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ReportItem {
    pub id: String,
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FailedItem {
    pub id: String,
    pub path: String,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StoredReport {
    pub received_at_ms: u64,
    pub device_name: String,
    #[serde(flatten)]
    pub report: SyncReport,
}
