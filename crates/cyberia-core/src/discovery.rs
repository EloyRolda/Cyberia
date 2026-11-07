//! Anuncio de la PC en la red por mDNS (RF-05).

use mdns_sd::{ServiceDaemon, ServiceInfo};

use crate::SERVICE_TYPE;

/// Anuncia `_cyberia._tcp` con el id de la PC en los registros TXT.
/// La dirección IP se toma sola de las interfaces y se actualiza si cambia.
pub fn advertise(pc_id: &str, pc_name: &str, port: u16) -> anyhow::Result<ServiceDaemon> {
    let daemon = ServiceDaemon::new()?;
    let instance = instance_name(pc_name, pc_id);
    let host = format!("cyberia-{}.local.", &pc_id[..pc_id.len().min(8)]);
    let api = crate::API_VERSION.to_string();
    let properties = [("id", pc_id), ("v", api.as_str())];
    let info = ServiceInfo::new(SERVICE_TYPE, &instance, &host, (), port, &properties[..])?
        .enable_addr_auto();
    daemon.register(info)?;
    Ok(daemon)
}

/// Nombre de instancia válido para DNS-SD: máximo 63 bytes y sin puntos.
fn instance_name(pc_name: &str, pc_id: &str) -> String {
    let mut name: String = pc_name.replace('.', " ").trim().to_string();
    if name.is_empty() {
        name = format!("Cyberia {}", &pc_id[..pc_id.len().min(6)]);
    }
    while name.len() > 63 {
        name.pop();
    }
    name
}
