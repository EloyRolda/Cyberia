package app.cyberia.core

import java.net.URLDecoder

/** Datos que trae el QR de la PC (RF-01, RF-03). */
data class PairingInfo(
    val version: Int,
    val pcId: String,
    val pcName: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val code: String,
) {
    val endpoints: List<Endpoint> get() = hosts.map { Endpoint(it, port) }

    companion object {
        private const val PREFIX = "cyberia://pair?"

        /**
         * Lee `cyberia://pair?v=1&id=…&name=…&hosts=ip1,ip2&port=…&fp=…&code=…`.
         * @throws IllegalArgumentException si el texto no es un QR de Cyberia.
         */
        fun parse(text: String): PairingInfo {
            val uri = text.trim()
            require(uri.startsWith(PREFIX, ignoreCase = true)) { "Ese QR no es de Cyberia." }
            val params = uri.substring(PREFIX.length)
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { part ->
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', "")
                    key to URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
                }
            val version = params["v"]?.toIntOrNull() ?: 1
            require(version == 1) { "Este QR es de una versión más nueva de Cyberia. Actualizá la app del celular." }
            val fingerprint = params["fp"].orEmpty().lowercase()
            require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
                "El QR está incompleto: falta la huella del certificado."
            }
            val code = params["code"].orEmpty()
            require(code.isNotEmpty()) { "El QR está incompleto: falta el código." }
            val port = params["port"]?.toIntOrNull()
            require(port != null && port in 1..65535) { "El QR está incompleto: falta el puerto." }
            val pcId = params["id"].orEmpty()
            require(pcId.isNotEmpty()) { "El QR está incompleto: falta el identificador de la PC." }
            return PairingInfo(
                version = version,
                pcId = pcId,
                pcName = params["name"].orEmpty().ifBlank { "PC" },
                hosts = params["hosts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                port = port,
                fingerprint = fingerprint,
                code = code,
            )
        }
    }
}
