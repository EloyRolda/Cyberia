//! Utilidades de red local.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// `true` si la dirección pertenece a la red local (RNF-05).
pub fn is_local_addr(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_local_v4(v4),
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_local_v4(v4);
            }
            is_local_v6(v6)
        }
    }
}

fn is_local_v4(ip: Ipv4Addr) -> bool {
    ip.is_loopback() || ip.is_private() || ip.is_link_local()
}

fn is_local_v6(ip: Ipv6Addr) -> bool {
    let first = ip.segments()[0];
    ip.is_loopback()
        // fc00::/7, direcciones locales únicas
        || (first & 0xfe00) == 0xfc00
        // fe80::/10, enlace local
        || (first & 0xffc0) == 0xfe80
}

/// Direcciones IPv4 privadas de esta PC, las más probables primero.
pub fn lan_ipv4_addrs() -> Vec<Ipv4Addr> {
    let mut addrs: Vec<Ipv4Addr> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|iface| !iface.is_loopback())
        .filter_map(|iface| match iface.ip() {
            IpAddr::V4(v4) if v4.is_private() => Some(v4),
            _ => None,
        })
        .collect();
    addrs.sort_by_key(|ip| rank(*ip));
    addrs.dedup();
    addrs
}

/// Las redes de casa suelen ser 192.168.x.x; 172.17+ suele ser de máquinas virtuales o Docker.
fn rank(ip: Ipv4Addr) -> u8 {
    match ip.octets() {
        [192, 168, ..] => 0,
        [10, ..] => 1,
        [172, 16, ..] => 2,
        _ => 3,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reconoce_direcciones_locales() {
        for ip in ["192.168.1.20", "10.0.0.3", "172.20.1.1", "127.0.0.1", "169.254.3.4", "::1", "fe80::1", "fd12::4", "::ffff:192.168.0.9"] {
            assert!(is_local_addr(ip.parse().unwrap()), "{ip} debería ser local");
        }
        for ip in ["8.8.8.8", "100.64.0.1", "2001:4860::8888", "::ffff:1.1.1.1"] {
            assert!(!is_local_addr(ip.parse().unwrap()), "{ip} no debería ser local");
        }
    }
}
