//! Índice de la carpeta de audios.
//!
//! Recorre la carpeta, separa los audios compatibles de los que no lo son y calcula la huella
//! BLAKE3 de cada audio. La huella se guarda junto con el tamaño y la fecha de modificación y
//! solo se recalcula si alguno de los dos cambia (RNF-04).

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Component, Path};

use crate::util;

/// Extensiones aceptadas (RF-13).
pub const SUPPORTED_EXTENSIONS: &[&str] = &["mp3", "m4a", "aac", "wav", "flac", "ogg"];

/// Archivos del sistema que no se muestran ni se marcan como incompatibles.
const IGNORED_NAMES: &[&str] = &["thumbs.db", "desktop.ini", "folder.jpg", "albumart.jpg"];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AudioFile {
    /// Huella del contenido: `b3:` seguido del BLAKE3 en hexadecimal.
    pub id: String,
    /// Ruta relativa a la carpeta, separada siempre con `/`.
    pub path: String,
    /// Subcarpeta que contiene al audio (`""` si está en la raíz).
    pub playlist: String,
    pub size: u64,
    pub modified_ms: u64,
}

impl AudioFile {
    pub fn file_name(&self) -> &str {
        self.path.rsplit('/').next().unwrap_or(&self.path)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UnsupportedFile {
    pub path: String,
    pub playlist: String,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ScanError {
    pub path: String,
    pub message: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Scan {
    pub files: Vec<AudioFile>,
    pub unsupported: Vec<UnsupportedFile>,
    pub errors: Vec<ScanError>,
    /// Cuántas huellas hubo que calcular en este recorrido.
    pub hashed: usize,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct HashCache {
    entries: HashMap<String, CacheEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CacheEntry {
    size: u64,
    modified_ms: u64,
    id: String,
}

impl HashCache {
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

pub fn is_supported(path: &str) -> bool {
    extension(path)
        .map(|ext| SUPPORTED_EXTENSIONS.contains(&ext.as_str()))
        .unwrap_or(false)
}

fn extension(path: &str) -> Option<String> {
    let name = path.rsplit('/').next()?;
    let (_, ext) = name.rsplit_once('.')?;
    Some(ext.to_ascii_lowercase())
}

/// Calcula la huella de un archivo.
pub fn hash_file(path: &Path) -> std::io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("b3:{}", hasher.finalize().to_hex()))
}

/// Recorre `root` y devuelve el índice. Actualiza `cache` y descarta las entradas de archivos
/// que ya no existen.
pub fn scan(root: &Path, cache: &mut HashCache) -> std::io::Result<Scan> {
    if !root.is_dir() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            format!("no existe la carpeta {}", root.display()),
        ));
    }

    let mut result = Scan::default();
    let mut next_cache = HashMap::with_capacity(cache.entries.len());

    let walker = walkdir::WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| e.depth() == 0 || !is_hidden(e.file_name()));

    for entry in walker {
        let entry = match entry {
            Ok(e) => e,
            Err(err) => {
                let path = err
                    .path()
                    .and_then(|p| relative_path(root, p))
                    .unwrap_or_default();
                result.errors.push(ScanError { path, message: err.to_string() });
                continue;
            }
        };
        if !entry.file_type().is_file() {
            continue;
        }
        let Some(rel) = relative_path(root, entry.path()) else {
            continue;
        };
        let name_lower = entry.file_name().to_string_lossy().to_lowercase();
        if IGNORED_NAMES.contains(&name_lower.as_str()) {
            continue;
        }
        let playlist = parent_of(&rel).to_string();
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(err) => {
                result.errors.push(ScanError { path: rel, message: err.to_string() });
                continue;
            }
        };
        let size = meta.len();
        if !is_supported(&rel) {
            result.unsupported.push(UnsupportedFile { path: rel, playlist, size });
            continue;
        }
        let modified_ms = meta.modified().map(util::system_time_ms).unwrap_or(0);

        let id = match cache.entries.get(&rel) {
            Some(c) if c.size == size && c.modified_ms == modified_ms => c.id.clone(),
            _ => match hash_file(entry.path()) {
                Ok(id) => {
                    result.hashed += 1;
                    id
                }
                Err(err) => {
                    result.errors.push(ScanError { path: rel, message: err.to_string() });
                    continue;
                }
            },
        };
        next_cache.insert(
            rel.clone(),
            CacheEntry { size, modified_ms, id: id.clone() },
        );
        result.files.push(AudioFile { id, path: rel, playlist, size, modified_ms });
    }

    result.files.sort_by(|a, b| sort_key(&a.playlist, &a.path).cmp(&sort_key(&b.playlist, &b.path)));
    result
        .unsupported
        .sort_by(|a, b| sort_key(&a.playlist, &a.path).cmp(&sort_key(&b.playlist, &b.path)));
    cache.entries = next_cache;
    Ok(result)
}

fn sort_key(playlist: &str, path: &str) -> (String, String) {
    (playlist.to_lowercase(), path.to_lowercase())
}

fn is_hidden(name: &std::ffi::OsStr) -> bool {
    name.to_string_lossy().starts_with('.')
}

/// Ruta relativa con `/` como separador. `None` si la ruta no es texto válido o sale de `root`.
fn relative_path(root: &Path, path: &Path) -> Option<String> {
    let rel = path.strip_prefix(root).ok()?;
    let mut parts = Vec::new();
    for component in rel.components() {
        match component {
            Component::Normal(part) => parts.push(part.to_str()?.to_string()),
            _ => return None,
        }
    }
    if parts.is_empty() {
        return None;
    }
    Some(parts.join("/"))
}

pub fn parent_of(rel: &str) -> &str {
    rel.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn indexa_playlists_y_marca_incompatibles() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("Para correr")).unwrap();
        fs::create_dir_all(root.join(".oculta")).unwrap();
        fs::write(root.join("Para correr/b.mp3"), b"bbbb").unwrap();
        fs::write(root.join("Para correr/A.FLAC"), b"aaaa").unwrap();
        fs::write(root.join("Para correr/notas.txt"), b"hola").unwrap();
        fs::write(root.join("suelto.ogg"), b"ogg").unwrap();
        fs::write(root.join(".oculta/x.mp3"), b"x").unwrap();
        fs::write(root.join("Thumbs.db"), b"x").unwrap();

        let mut cache = HashCache::default();
        let scan = scan(root, &mut cache).unwrap();
        let paths: Vec<_> = scan.files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(paths, ["suelto.ogg", "Para correr/A.FLAC", "Para correr/b.mp3"]);
        assert_eq!(scan.files[1].playlist, "Para correr");
        assert_eq!(scan.files[0].playlist, "");
        assert_eq!(scan.unsupported.len(), 1);
        assert_eq!(scan.unsupported[0].path, "Para correr/notas.txt");
        assert_eq!(scan.hashed, 3);
        assert_eq!(
            scan.files[2].id,
            format!("b3:{}", blake3::hash(b"bbbb").to_hex())
        );
    }

    #[test]
    fn no_recalcula_huellas_sin_cambios() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("a.mp3"), b"uno").unwrap();
        fs::write(dir.path().join("b.mp3"), b"dos").unwrap();
        let mut cache = HashCache::default();
        assert_eq!(scan(dir.path(), &mut cache).unwrap().hashed, 2);
        assert_eq!(scan(dir.path(), &mut cache).unwrap().hashed, 0);

        fs::write(dir.path().join("b.mp3"), b"dos, pero mas largo").unwrap();
        let again = scan(dir.path(), &mut cache).unwrap();
        assert_eq!(again.hashed, 1);

        fs::remove_file(dir.path().join("a.mp3")).unwrap();
        scan(dir.path(), &mut cache).unwrap();
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn carpeta_inexistente_es_error() {
        let mut cache = HashCache::default();
        assert!(scan(Path::new("/no/existe/cyberia"), &mut cache).is_err());
    }
}
