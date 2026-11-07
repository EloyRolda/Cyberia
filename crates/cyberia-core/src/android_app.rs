//! La app del celular que viaja con la app de PC.
//!
//! El instalador de Windows trae el APK de la misma versión. El celular lo ve en `GET /v1/info`
//! y, si es más nuevo que el que tiene, lo baja por `GET /v1/app/android` y se actualiza: no
//! hace falta pasar archivos al celular ni volver a vincularlo.

use anyhow::Context;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::{Path, PathBuf};

/// Nombre del APK dentro de la carpeta de recursos.
pub const APK_FILE: &str = "cyberia.apk";
/// `output-metadata.json` que genera Gradle junto al APK, con la versión.
pub const METADATA_FILE: &str = "cyberia-apk.json";

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AndroidApp {
    #[serde(skip)]
    pub path: PathBuf,
    pub version_code: u64,
    pub version_name: String,
    pub size: u64,
    /// SHA-256 del APK en hexadecimal, para que el celular lo verifique antes de instalarlo.
    pub sha256: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OutputMetadata {
    elements: Vec<OutputElement>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OutputElement {
    version_code: u64,
    #[serde(default)]
    version_name: String,
}

impl AndroidApp {
    /// Carga el APK y su versión desde una carpeta con [`APK_FILE`] y [`METADATA_FILE`].
    /// `Ok(None)` si la carpeta no trae APK (por ejemplo, en desarrollo).
    pub fn from_dir(dir: &Path) -> anyhow::Result<Option<Self>> {
        let apk = dir.join(APK_FILE);
        if !apk.is_file() {
            return Ok(None);
        }
        let metadata = std::fs::read(dir.join(METADATA_FILE))
            .with_context(|| format!("falta {METADATA_FILE} junto al APK"))?;
        let metadata: OutputMetadata = serde_json::from_slice(&metadata)?;
        let element = metadata
            .elements
            .first()
            .context("el archivo de versión del APK está vacío")?;
        Self::load(&apk, element.version_code, element.version_name.clone()).map(Some)
    }

    /// Carga un APK con una versión dada (servidor sin interfaz y pruebas).
    pub fn load(path: &Path, version_code: u64, version_name: String) -> anyhow::Result<Self> {
        let mut file = std::fs::File::open(path)
            .with_context(|| format!("no se pudo abrir {}", path.display()))?;
        let mut hasher = Sha256::new();
        let mut buf = vec![0u8; 256 * 1024];
        let mut size = 0u64;
        loop {
            let n = file.read(&mut buf)?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
            size += n as u64;
        }
        Ok(Self {
            path: path.to_path_buf(),
            version_code,
            version_name,
            size,
            sha256: hex::encode(hasher.finalize()),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lee_la_version_que_escribe_gradle() {
        let dir = tempfile::tempdir().unwrap();
        assert!(AndroidApp::from_dir(dir.path()).unwrap().is_none());
        std::fs::write(dir.path().join(APK_FILE), b"apk").unwrap();
        std::fs::write(
            dir.path().join(METADATA_FILE),
            r#"{"version":3,"applicationId":"app.cyberia","variantName":"release",
                "elements":[{"type":"SINGLE","versionCode":12,"versionName":"0.1.12","outputFile":"app-release.apk"}]}"#,
        )
        .unwrap();
        let app = AndroidApp::from_dir(dir.path()).unwrap().unwrap();
        assert_eq!(app.version_code, 12);
        assert_eq!(app.version_name, "0.1.12");
        assert_eq!(app.size, 3);
        assert_eq!(app.sha256, hex::encode(Sha256::digest(b"apk")));
    }
}
