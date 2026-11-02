package app.cyberia.net

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.cyberia.core.CyberiaClient
import app.cyberia.core.CyberiaException
import app.cyberia.core.Endpoint
import app.cyberia.core.PairedPc
import app.cyberia.data.AppPrefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Encuentra la PC en la red aunque cambie su IP (RF-05): primero prueba las últimas direcciones
 * conocidas, después la busca por mDNS (`_cyberia._tcp`) y, como último recurso, usa la IP
 * escrita a mano.
 */
class PcLocator(private val context: Context, private val prefs: AppPrefs) {

    suspend fun locate(pc: PairedPc, network: Network): Endpoint? {
        val probe = CyberiaClient(pc.fingerprint, network.socketFactory, connectTimeoutMs = 2_000)
        val tried = mutableSetOf<Endpoint>()

        suspend fun tryEndpoint(endpoint: Endpoint): Boolean {
            if (!tried.add(endpoint)) return false
            return try {
                probe.info(endpoint, pc.token).pcId == pc.pcId
            } catch (e: CyberiaException.Unpaired) {
                throw e
            } catch (e: CyberiaException) {
                false
            }
        }

        for (host in pc.knownHosts) {
            val endpoint = Endpoint(host, pc.port)
            if (tryEndpoint(endpoint)) return endpoint
        }
        discover(pc.pcId, timeoutMs = 6_000)?.let { if (tryEndpoint(it)) return it }
        prefs.manualHost.value?.let { manual ->
            val endpoint = parseManual(manual, pc.port)
            if (endpoint != null && tryEndpoint(endpoint)) return endpoint
        }
        return null
    }

    /** Busca por mDNS la PC con id [pcId]. */
    suspend fun discover(pcId: String, timeoutMs: Long): Endpoint? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        return withTimeoutOrNull(timeoutMs) { nsdEndpoints(nsd, pcId).firstOrNull() }
    }

    private fun nsdEndpoints(nsd: NsdManager, pcId: String): Flow<Endpoint> = callbackFlow {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        // Se resuelve de a uno: las versiones viejas de Android no permiten resolver en paralelo.
        val resolver = launch {
            for (info in found) {
                val resolved = resolve(nsd, info) ?: continue
                val id = resolved.attributes["id"]?.toString(Charsets.UTF_8)
                @Suppress("DEPRECATION")
                val address = resolved.host
                if (id == pcId && address is Inet4Address) {
                    send(Endpoint(address.hostAddress ?: continue, resolved.port))
                }
            }
        }
        awaitClose {
            resolver.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            })
        }

    companion object {
        const val SERVICE_TYPE = "_cyberia._tcp"

        /** Acepta `192.168.0.10` o `192.168.0.10:47474`. */
        fun parseManual(text: String, defaultPort: Int): Endpoint? {
            val clean = text.trim()
            if (clean.isEmpty()) return null
            val host = clean.substringBefore(':')
            val port = clean.substringAfter(':', "").toIntOrNull() ?: defaultPort
            if (host.isEmpty() || port !in 1..65535) return null
            return Endpoint(host, port)
        }
    }
}
