//! Certificado propio de la PC. Se genera una sola vez y el celular lo fija al escanear el QR.

use anyhow::Context;
use sha2::{Digest, Sha256};
use std::path::Path;

use crate::util;

#[derive(Clone)]
pub struct Identity {
    pub cert_der: Vec<u8>,
    /// Clave privada en PKCS#8 DER.
    pub key_der: Vec<u8>,
    /// SHA-256 del certificado en DER, en hexadecimal minúscula.
    pub fingerprint: String,
}

impl std::fmt::Debug for Identity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Identity")
            .field("fingerprint", &self.fingerprint)
            .finish_non_exhaustive()
    }
}

pub fn fingerprint(cert_der: &[u8]) -> String {
    hex::encode(Sha256::digest(cert_der))
}

/// Carga el certificado de `dir` o lo genera si no existe.
pub fn load_or_create(dir: &Path) -> anyhow::Result<Identity> {
    let cert_path = dir.join("cert.der");
    let key_path = dir.join("key.der");
    if cert_path.exists() && key_path.exists() {
        let cert_der = std::fs::read(&cert_path).context("leyendo cert.der")?;
        let key_der = std::fs::read(&key_path).context("leyendo key.der")?;
        let fingerprint = fingerprint(&cert_der);
        return Ok(Identity { cert_der, key_der, fingerprint });
    }

    let mut params = rcgen::CertificateParams::new(vec!["cyberia.local".to_string()])?;
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, "Cyberia");
    let key_pair = rcgen::KeyPair::generate()?;
    let cert = params.self_signed(&key_pair)?;
    let cert_der = cert.der().to_vec();
    let key_der = key_pair.serialize_der();

    std::fs::create_dir_all(dir)?;
    util::write_atomic(&key_path, &key_der)?;
    restrict_permissions(&key_path);
    util::write_atomic(&cert_path, &cert_der)?;
    let fingerprint = fingerprint(&cert_der);
    Ok(Identity { cert_der, key_der, fingerprint })
}

#[cfg(unix)]
fn restrict_permissions(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
}

#[cfg(not(unix))]
fn restrict_permissions(_path: &Path) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn genera_una_vez_y_reutiliza() {
        let dir = tempfile::tempdir().unwrap();
        let a = load_or_create(dir.path()).unwrap();
        let b = load_or_create(dir.path()).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.fingerprint.len(), 64);
    }
}
