package app.cyberia.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON compartido con la API de la PC. */
val CyberiaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Lista completa de audios publicada por la PC (`GET /v1/manifest`). */
@Serializable
data class Manifest(
    val version: Long,
    val files: List<ManifestFile>,
)

@Serializable
data class ManifestFile(
    /** Huella del contenido: `b3:` + BLAKE3 en hexadecimal. */
    val id: String,
    /** Ruta relativa a la carpeta de la PC, con `/`. */
    val path: String,
    /** Subcarpeta que contiene al audio (`""` en la raíz). */
    val playlist: String,
    val size: Long,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

@Serializable
data class PcInfo(
    val apiVersion: Int = 1,
    val pcId: String,
    val pcName: String,
    val manifestVersion: Long,
)

@Serializable
data class PairRequest(
    val code: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class PairResponse(
    val token: String,
    val pcId: String,
    val pcName: String,
    val apiVersion: Int = 1,
)

@Serializable
data class ApiError(
    val error: String = "",
    val message: String = "",
)

/** Resultado que el celular le informa a la PC (`POST /v1/report`, RF-25). */
@Serializable
data class SyncReport(
    val manifestVersion: Long,
    val downloaded: List<ReportItem> = emptyList(),
    val deleted: List<ReportItem> = emptyList(),
    val failed: List<FailedItem> = emptyList(),
    val pendingDeletions: Int = 0,
    val noSpace: Boolean = false,
    val freeBytes: Long? = null,
    val libraryCount: Int = 0,
    val libraryBytes: Long = 0,
    val complete: Boolean = false,
)

@Serializable
data class ReportItem(val id: String, val path: String)

@Serializable
data class FailedItem(val id: String, val path: String, val reason: String)

/** La PC vinculada, tal como la guarda el celular (RF-04). */
@Serializable
data class PairedPc(
    val pcId: String,
    val pcName: String,
    /** SHA-256 del certificado de la PC, fijado al escanear el QR (RNF-06). */
    val fingerprint: String,
    val token: String,
    /** Últimas direcciones conocidas, la más reciente primero. */
    val knownHosts: List<String>,
    val port: Int,
    val pairedAtMs: Long,
)

/** Dirección de la PC en la red local. */
data class Endpoint(val host: String, val port: Int) {
    val baseUrl: String
        get() = if (host.contains(':')) "https://[$host]:$port" else "https://$host:$port"

    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        /** IP escrita a mano: `192.168.0.10` o `192.168.0.10:47474`. `null` si no sirve. */
        fun parse(text: String, defaultPort: Int): Endpoint? {
            val clean = text.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (clean.isEmpty() || clean.count { it == ':' } > 1) return null
            val host = clean.substringBefore(':')
            val portText = clean.substringAfter(':', "")
            val port = if (portText.isEmpty()) defaultPort else portText.toIntOrNull() ?: return null
            val validHost = host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            if (!validHost || port !in 1..65535) return null
            return Endpoint(host, port)
        }
    }
}
