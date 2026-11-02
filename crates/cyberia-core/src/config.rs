//! Configuración persistente de la app de PC.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

use crate::util;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    /// Identificador estable de esta PC (va en el QR y en el anuncio mDNS).
    pub pc_id: String,
    /// Nombre que ve el celular.
    pub pc_name: String,
    /// Carpeta de audios publicada.
    #[serde(default)]
    pub folder: Option<PathBuf>,
    /// Último puerto usado. Se mantiene para que la IP:puerto conocida siga sirviendo.
    #[serde(default)]
    pub port: Option<u16>,
    /// Celular vinculado. En el MVP hay uno solo por PC.
    #[serde(default)]
    pub device: Option<PairedDevice>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    /// BLAKE3 del token en hexadecimal. El token en claro solo lo tiene el celular.
    pub token_hash: String,
    pub paired_at_ms: u64,
}

impl Settings {
    pub fn load_or_create(path: &Path, default_name: impl FnOnce() -> String) -> anyhow::Result<Self> {
        if let Some(settings) = util::read_json::<Settings>(path)? {
            return Ok(settings);
        }
        let settings = Settings {
            pc_id: util::random_hex(8),
            pc_name: default_name(),
            folder: None,
            port: None,
            device: None,
        };
        settings.save(path)?;
        Ok(settings)
    }

    pub fn save(&self, path: &Path) -> anyhow::Result<()> {
        util::write_json(path, self)
    }
}

impl PairedDevice {
    pub fn token_matches(&self, token: &str) -> bool {
        match blake3::Hash::from_hex(&self.token_hash) {
            // La comparación entre `blake3::Hash` es de tiempo constante.
            Ok(stored) => stored == blake3::hash(token.as_bytes()),
            Err(_) => false,
        }
    }
}

pub fn hash_token(token: &str) -> String {
    blake3::hash(token.as_bytes()).to_hex().to_string()
}
