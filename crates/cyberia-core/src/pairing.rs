//! Emparejamiento por QR (RF-01 a RF-03).

use serde::Serialize;
use std::net::IpAddr;
use std::time::{Duration, Instant};

use crate::util;

/// El código vence a los 5 minutos (RF-02).
pub const PAIRING_TTL: Duration = Duration::from_secs(5 * 60);
/// Intentos fallidos permitidos antes de invalidar el código.
pub const MAX_FAILED_ATTEMPTS: u32 = 10;

#[derive(Debug, Clone)]
pub struct PairingSession {
    pub code: String,
    pub created: Instant,
    pub expires_at_ms: u64,
    pub failed_attempts: u32,
    pub offer: PairingOffer,
}

/// Lo que muestra la interfaz mientras espera que el celular escanee.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingOffer {
    pub uri: String,
    pub qr_svg: String,
    pub expires_at_ms: u64,
    pub hosts: Vec<String>,
    pub port: u16,
}

impl PairingSession {
    pub fn is_expired(&self) -> bool {
        self.created.elapsed() >= PAIRING_TTL
    }

    pub fn code_matches(&self, code: &str) -> bool {
        // Comparación de tiempo constante a través de BLAKE3.
        blake3::hash(self.code.as_bytes()) == blake3::hash(code.as_bytes())
    }
}

pub struct QrData<'a> {
    pub pc_id: &'a str,
    pub pc_name: &'a str,
    pub hosts: &'a [IpAddr],
    pub port: u16,
    pub fingerprint: &'a str,
    pub code: &'a str,
}

pub fn new_code() -> String {
    util::random_token(16)
}

/// `cyberia://pair?v=1&id=…&name=…&hosts=ip1,ip2&port=…&fp=…&code=…`
pub fn build_uri(data: &QrData<'_>) -> String {
    let hosts: Vec<String> = data.hosts.iter().map(|h| h.to_string()).collect();
    format!(
        "cyberia://pair?v={}&id={}&name={}&hosts={}&port={}&fp={}&code={}",
        crate::API_VERSION,
        urlencoding::encode(data.pc_id),
        urlencoding::encode(data.pc_name),
        urlencoding::encode(&hosts.join(",")),
        data.port,
        data.fingerprint,
        urlencoding::encode(data.code),
    )
}

pub fn qr_svg(uri: &str) -> anyhow::Result<String> {
    let code = qrcode::QrCode::with_error_correction_level(uri.as_bytes(), qrcode::EcLevel::M)?;
    Ok(code
        .render::<qrcode::render::svg::Color<'_>>()
        .min_dimensions(256, 256)
        .quiet_zone(true)
        .dark_color(qrcode::render::svg::Color("#000000"))
        .light_color(qrcode::render::svg::Color("#ffffff"))
        .build())
}

/// QR para mostrar en una terminal (servidor sin interfaz).
pub fn qr_terminal(uri: &str) -> anyhow::Result<String> {
    let code = qrcode::QrCode::with_error_correction_level(uri.as_bytes(), qrcode::EcLevel::L)?;
    Ok(code
        .render::<qrcode::render::unicode::Dense1x2>()
        .dark_color(qrcode::render::unicode::Dense1x2::Light)
        .light_color(qrcode::render::unicode::Dense1x2::Dark)
        .quiet_zone(true)
        .build())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arma_uri_y_qr() {
        let hosts = ["192.168.0.10".parse().unwrap()];
        let uri = build_uri(&QrData {
            pc_id: "abcd",
            pc_name: "PC de Eloy",
            hosts: &hosts,
            port: 47474,
            fingerprint: "00ff",
            code: "c0d3",
        });
        assert_eq!(
            uri,
            "cyberia://pair?v=1&id=abcd&name=PC%20de%20Eloy&hosts=192.168.0.10&port=47474&fp=00ff&code=c0d3"
        );
        assert!(qr_svg(&uri).unwrap().starts_with("<?xml"));
    }
}
