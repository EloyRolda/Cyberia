//! Manifiesto publicado: la lista de audios que el celular tiene que tener.

use serde::{Deserialize, Serialize};
use std::path::Path;

use crate::library::AudioFile;
use crate::util;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub version: u64,
    #[serde(default)]
    pub published_at_ms: u64,
    pub files: Vec<AudioFile>,
}

/// Lo que recibe el celular en `GET /v1/manifest`.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicManifest<'a> {
    pub version: u64,
    pub files: Vec<PublicFile<'a>>,
}

#[derive(Debug, Serialize)]
pub struct PublicFile<'a> {
    pub id: &'a str,
    pub path: &'a str,
    pub playlist: &'a str,
    pub size: u64,
}

impl Manifest {
    pub fn load(path: &Path) -> anyhow::Result<Self> {
        Ok(util::read_json(path)?.unwrap_or_default())
    }

    pub fn save(&self, path: &Path) -> anyhow::Result<()> {
        util::write_json(path, self)
    }

    /// `true` si `files` describe exactamente lo publicado.
    pub fn same_files(&self, files: &[AudioFile]) -> bool {
        self.files == files
    }

    pub fn find(&self, id: &str) -> Option<&AudioFile> {
        self.files.iter().find(|f| f.id == id)
    }

    pub fn total_bytes(&self) -> u64 {
        self.files.iter().map(|f| f.size).sum()
    }

    pub fn public(&self) -> PublicManifest<'_> {
        PublicManifest {
            version: self.version,
            files: self
                .files
                .iter()
                .map(|f| PublicFile {
                    id: &f.id,
                    path: &f.path,
                    playlist: &f.playlist,
                    size: f.size,
                })
                .collect(),
        }
    }
}
