package app.cyberia.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** Avisos que manda la PC por `GET /v1/events`. */
sealed interface PcEvent {
    data class Version(val version: Long) : PcEvent
    data object Unpaired : PcEvent
}

/**
 * Cliente de la API HTTPS de la PC.
 *
 * Solo confía en el certificado cuya huella SHA-256 vino en el QR (RNF-06). Con
 * [socketFactory] se puede atar el tráfico a la red Wi-Fi para no usar nunca datos móviles
 * (RF-42).
 */
class CyberiaClient(
    fingerprint: String,
    socketFactory: SocketFactory? = null,
    connectTimeoutMs: Long = 4_000,
) {
    private val pin = fingerprint.lowercase()
    private val trustManager = PinnedTrustManager(pin)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .apply { if (socketFactory != null) socketFactory(socketFactory) }
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }.socketFactory,
            trustManager,
        )
        // El certificado se valida por huella; el nombre no importa porque la IP cambia.
        .hostnameVerifier(HostnameVerifier { _, session ->
            val leaf = session.peerCertificates.firstOrNull() ?: return@HostnameVerifier false
            Hashing.sha256Hex(leaf.encoded) == pin
        })
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Para la conexión de avisos: la PC manda una línea cada 60 s, así que se espera hasta 150 s. */
    private val eventsHttp: OkHttpClient = http.newBuilder()
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    /** Cambia el código del QR por un token propio de este celular (`POST /v1/pair`). */
    suspend fun pair(endpoint: Endpoint, request: PairRequest): PairResponse = call {
        val body = CyberiaJson.encodeToString(request).toRequestBody(jsonType)
        http.newCall(Request.Builder().url("${endpoint.baseUrl}/v1/pair").post(body).build()).execute().use { res ->
            when (res.code) {
                200 -> CyberiaJson.decodeFromString<PairResponse>(res.body!!.string())
                410, 429 -> throw CyberiaException.PairingExpired(errorMessage(res, "El código venció. Generá un QR nuevo en la PC."))
                403 -> throw CyberiaException.PairingRejected(errorMessage(res, "El código no es válido."))
                else -> throw serverError(res)
            }
        }
    }

    suspend fun info(endpoint: Endpoint, token: String): PcInfo = call {
        get(endpoint, token, "/v1/info").use { res -> CyberiaJson.decodeFromString(res.body!!.string()) }
    }

    suspend fun manifest(endpoint: Endpoint, token: String): Manifest = call {
        get(endpoint, token, "/v1/manifest").use { res -> CyberiaJson.decodeFromString(res.body!!.string()) }
    }

    suspend fun report(endpoint: Endpoint, token: String, report: SyncReport) = call {
        val body = CyberiaJson.encodeToString(report).toRequestBody(jsonType)
        val req = authed(endpoint, token, "/v1/report").post(body).build()
        http.newCall(req).execute().use { res -> checkOk(res) }
    }

    /** Desvincula este celular en la PC (`DELETE /v1/pair`, RF-07). */
    suspend fun unpair(endpoint: Endpoint, token: String) = call {
        val req = authed(endpoint, token, "/v1/pair").delete().build()
        http.newCall(req).execute().use { res -> checkOk(res) }
    }

    /**
     * Descarga el audio [id] en [target]. Si [target] ya tiene una parte, pide solo lo que falta
     * (RF-23). Devuelve la cantidad de bytes que tiene [target] al terminar.
     */
    suspend fun download(
        endpoint: Endpoint,
        token: String,
        id: String,
        target: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit = {},
    ): Long = downloadTo(endpoint, token, "/v1/files/$id", target, expectedSize, onProgress)

    /**
     * Baja la app del celular que trae la PC (`GET /v1/app/android`) en [target], retomando si
     * ya había una parte. Hay que verificar el SHA-256 antes de instalarla.
     */
    suspend fun downloadAndroidApp(
        endpoint: Endpoint,
        token: String,
        target: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit = {},
    ): Long = downloadTo(endpoint, token, "/v1/app/android", target, expectedSize, onProgress)

    private suspend fun downloadTo(
        endpoint: Endpoint,
        token: String,
        path: String,
        target: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ): Long = call {
        target.parentFile?.mkdirs()
        var existing = if (target.exists()) target.length() else 0L
        if (existing > expectedSize) {
            target.delete()
            existing = 0
        }
        if (existing == expectedSize && expectedSize > 0) return@call existing

        val builder = authed(endpoint, token, path)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        http.newCall(builder.build()).execute().use { res ->
            val append = when (res.code) {
                206 -> {
                    val start = res.header("Content-Range")
                        ?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (start != existing) {
                        target.delete()
                        throw CyberiaException.Server(206, "La PC respondió un rango inesperado")
                    }
                    true
                }
                200 -> false
                416 -> {
                    // La parte ya estaba completa o no sirve: se empieza de cero la próxima vez.
                    target.delete()
                    throw CyberiaException.Server(416, "La descarga parcial no coincide")
                }
                else -> {
                    checkOk(res)
                    false
                }
            }
            val body = res.body ?: throw CyberiaException.Server(res.code, "Respuesta vacía")
            var written = if (append) existing else 0L
            FileOutputStream(target, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= 512 * 1024) {
                            lastReport = written
                            onProgress(written)
                        }
                    }
                }
                out.fd.sync()
            }
            onProgress(written)
            written
        }
    }

    /**
     * Conexión de avisos (`GET /v1/events`). El flujo emite la versión actual al conectarse y
     * una nueva cada vez que se aprieta Sincronizar en la PC. Termina con [PcEvent.Unpaired] o
     * con una excepción si se corta.
     */
    fun events(endpoint: Endpoint, token: String): Flow<PcEvent> = callbackFlow {
        val request = authed(endpoint, token, "/v1/events")
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "version" -> {
                        val version = runCatching {
                            CyberiaJson.parseToJsonElement(data).jsonObject["version"]?.jsonPrimitive?.longOrNull
                        }.getOrNull()
                        if (version != null) trySend(PcEvent.Version(version))
                    }
                    "unpaired" -> {
                        trySend(PcEvent.Unpaired)
                        close()
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close(CyberiaException.Network(IOException("La PC cerró la conexión de avisos")))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response?.code == 401 -> CyberiaException.Unpaired()
                    t != null -> mapError(t)
                    response != null -> CyberiaException.Server(response.code, "La PC respondió ${response.code}")
                    else -> CyberiaException.Network(IOException("Se cortó la conexión de avisos"))
                }
                close(error)
            }
        }
        val source = EventSources.createFactory(eventsHttp).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    // ---- Internos ----

    private fun authed(endpoint: Endpoint, token: String, path: String): Request.Builder =
        Request.Builder()
            .url("${endpoint.baseUrl}$path")
            .header("Authorization", "Bearer $token")

    private fun get(endpoint: Endpoint, token: String, path: String): Response {
        val res = http.newCall(authed(endpoint, token, path).get().build()).execute()
        try {
            checkOk(res)
        } catch (e: Throwable) {
            res.close()
            throw e
        }
        return res
    }

    private fun checkOk(res: Response) {
        if (res.isSuccessful) return
        throw when (res.code) {
            401 -> CyberiaException.Unpaired()
            409 -> CyberiaException.FileChanged()
            404, 410 -> CyberiaException.FileMissing()
            else -> serverError(res)
        }
    }

    private fun serverError(res: Response): CyberiaException =
        CyberiaException.Server(res.code, errorMessage(res, "La PC respondió ${res.code}"))

    private fun errorMessage(res: Response, fallback: String): String = runCatching {
        CyberiaJson.decodeFromString<ApiError>(res.body!!.string()).message
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private suspend fun <T> call(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CyberiaException) {
            throw e
        } catch (e: IOException) {
            throw mapError(e)
        }
    }

    private fun mapError(t: Throwable): CyberiaException {
        if (t is CyberiaException) return t
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is CertificateException || cause is javax.net.ssl.SSLPeerUnverifiedException) {
                return CyberiaException.CertificateMismatch(t)
            }
            cause = cause.cause
        }
        if (t is SSLException && t.message?.contains("certificate", ignoreCase = true) == true) {
            return CyberiaException.CertificateMismatch(t)
        }
        return CyberiaException.Network(t)
    }
}

/** Acepta únicamente el certificado con la huella fijada. */
private class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("La PC no presentó certificado")
        val actual = Hashing.sha256Hex(leaf.encoded)
        if (!java.security.MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) {
            throw CertificateException("El certificado de la PC no coincide con el del QR")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("No se aceptan certificados de cliente")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
