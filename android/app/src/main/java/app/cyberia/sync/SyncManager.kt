package app.cyberia.sync

import android.content.Context
import android.os.Build
import app.cyberia.core.CyberiaClient
import app.cyberia.core.CyberiaException
import app.cyberia.core.Endpoint
import app.cyberia.core.PairRequest
import app.cyberia.core.PairedPc
import app.cyberia.core.PairingInfo
import app.cyberia.core.SyncEngine
import app.cyberia.core.SyncProgress
import app.cyberia.core.SyncResult
import app.cyberia.data.AppPrefs
import app.cyberia.data.LastSync
import app.cyberia.net.PcLocator
import app.cyberia.net.WifiNetworks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Lo que muestra la app sobre la sincronización. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Searching : SyncStatus
    data class Syncing(val progress: SyncProgress?) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** Por qué se sincroniza. Sirve para decidir cuánto insistir y qué avisar. */
enum class Trigger(val byUser: Boolean) {
    App(true),
    Pull(true),
    Push(false),
    Periodic(false),
    Charging(false),
}

/**
 * Coordina la vinculación y las sincronizaciones del celular: encuentra la PC, corre el motor y
 * avisa el resultado con notificaciones. Una sola sincronización a la vez.
 */
class SyncManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val engine: SyncEngine,
    private val locator: PcLocator,
) {
    private val running = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Vincula este celular con la PC del QR (RF-03). */
    suspend fun pair(info: PairingInfo): PairedPc {
        val network = WifiNetworks.current(context)
            ?: throw IllegalStateException("Conectate al Wi-Fi de casa para vincular.")
        val client = CyberiaClient(info.fingerprint, network.socketFactory)
        val request = PairRequest(info.code, prefs.deviceId, deviceName())
        val candidates = info.endpoints.toMutableList()
        var lastError: CyberiaException? = null
        var index = 0
        var searchedMdns = false
        while (true) {
            if (index >= candidates.size) {
                if (searchedMdns) break
                searchedMdns = true
                locator.discover(info.pcId, timeoutMs = 6_000)?.let { candidates += it }
                if (index >= candidates.size) break
            }
            val endpoint = candidates[index++]
            try {
                val response = client.pair(endpoint, request)
                val pc = PairedPc(
                    pcId = response.pcId,
                    pcName = response.pcName,
                    fingerprint = info.fingerprint,
                    token = response.token,
                    knownHosts = (listOf(endpoint.host) + info.hosts).distinct().take(4),
                    port = endpoint.port,
                    pairedAtMs = System.currentTimeMillis(),
                )
                prefs.setPairedPc(pc)
                prefs.setPendingDeletion(null)
                Notifications.cancel(context, Notifications.ID_UNPAIRED)
                return pc
            } catch (e: CyberiaException.Network) {
                lastError = e
            }
        }
        throw lastError ?: CyberiaException.Network(IllegalStateException("No encontré la PC"))
    }

    /** Desvincula desde el celular (RF-07). Avisa a la PC si está a mano; los audios quedan. */
    suspend fun unpair() {
        val pc = prefs.pairedPc.value ?: return
        val network = WifiNetworks.current(context)
        if (network != null) {
            runCatching {
                val endpoint = locator.locate(pc, network)
                if (endpoint != null) CyberiaClient(pc.fingerprint, network.socketFactory).unpair(endpoint, pc.token)
            }
        }
        prefs.setPairedPc(null)
        prefs.setPendingDeletion(null)
        _status.value = SyncStatus.Idle
    }

    /** La PC desvinculó este celular. */
    fun onUnpairedByPc() {
        if (prefs.pairedPc.value == null) return
        prefs.setPairedPc(null)
        prefs.setPendingDeletion(null)
        _status.value = SyncStatus.Idle
        Notifications.unpaired(context)
    }

    /** Pedido de sincronizar que llegó mientras otra estaba en curso. */
    @Volatile
    private var rerunRequested = false

    /**
     * Revisa si hay audios nuevos y los descarga. Si ya hay una sincronización en curso, anota el
     * pedido y la repite al terminar (por ejemplo, si se aprieta Sincronizar en la PC a mitad de
     * una descarga). Devuelve el resultado o `null` si no se pudo sincronizar.
     */
    suspend fun syncNow(trigger: Trigger, allowLargeDeletion: Boolean = false): SyncResult? {
        if (!running.tryLock()) {
            rerunRequested = true
            return null
        }
        try {
            var result: SyncResult?
            var attempts = 0
            do {
                rerunRequested = false
                result = syncOnce(trigger, allowLargeDeletion)
                attempts++
            } while (rerunRequested && result != null && attempts < 5)
            return result
        } finally {
            running.unlock()
        }
    }

    private suspend fun syncOnce(trigger: Trigger, allowLargeDeletion: Boolean): SyncResult? {
        try {
            val pc = prefs.pairedPc.value ?: return null
            val network = WifiNetworks.current(context)
            if (network == null) {
                if (trigger.byUser) {
                    _status.value = SyncStatus.Failed("Sin Wi-Fi. Cyberia solo sincroniza en el Wi-Fi de casa; los audios descargados se escuchan igual.")
                }
                return null
            }
            _status.value = SyncStatus.Searching
            val endpoint = locator.locate(pc, network)
            if (endpoint == null) {
                // En segundo plano no es un error: puede ser otro Wi-Fi o la PC apagada.
                _status.value = if (trigger.byUser) {
                    SyncStatus.Failed("No encuentro a ${pc.pcName}. ¿Está prendida, con Cyberia abierta y en el mismo Wi-Fi?")
                } else {
                    SyncStatus.Idle
                }
                return null
            }
            prefs.rememberHost(endpoint.host, endpoint.port)
            return runSync(pc, network, endpoint, allowLargeDeletion)
        } catch (e: CancellationException) {
            _status.value = SyncStatus.Idle
            throw e
        } catch (e: CyberiaException.Unpaired) {
            onUnpairedByPc()
            return null
        } catch (e: CyberiaException) {
            _status.value = if (trigger.byUser || e is CyberiaException.CertificateMismatch) {
                SyncStatus.Failed(e.message ?: "No se pudo sincronizar")
            } else {
                SyncStatus.Idle
            }
            return null
        }
    }

    private suspend fun runSync(
        pc: PairedPc,
        network: android.net.Network,
        endpoint: Endpoint,
        allowLargeDeletion: Boolean,
    ): SyncResult {
        _status.value = SyncStatus.Syncing(null)
        val client = CyberiaClient(pc.fingerprint, network.socketFactory)
        val result = engine.sync(client, endpoint, pc.token, allowLargeDeletion) { progress ->
            _status.value = SyncStatus.Syncing(progress)
        }
        afterSync(pc, result)
        _status.value = SyncStatus.Idle
        return result
    }

    private fun afterSync(pc: PairedPc, result: SyncResult) {
        prefs.setLastSync(
            LastSync(
                atMs = System.currentTimeMillis(),
                downloaded = result.downloaded.size,
                deleted = result.deleted.size,
                failed = result.failed.size,
                firstFailure = result.failed.firstOrNull()?.let { "${it.path.substringAfterLast('/')}: ${it.reason}" },
                noSpace = result.noSpace,
                complete = result.complete,
                manifestVersion = result.manifestVersion,
            ),
        )
        prefs.setPendingDeletion(result.pendingDeletion)
        Notifications.newAudios(context, result.downloaded.size, pc.pcName)
        if (result.noSpace) {
            Notifications.noSpace(context, result.failed.size)
        } else {
            Notifications.cancel(context, Notifications.ID_NO_SPACE)
        }
        val pending = result.pendingDeletion
        if (pending != null) {
            Notifications.confirmDeletion(context, pending.paths.size)
        } else {
            Notifications.cancel(context, Notifications.ID_CONFIRM_DELETE)
        }
    }

    /** El usuario confirmó un borrado grande (RF-20). Funciona sin conexión. */
    suspend fun confirmPendingDeletion() {
        val pending = prefs.pendingDeletion.value ?: return
        engine.confirmDeletion(pending)
        prefs.setPendingDeletion(null)
        Notifications.cancel(context, Notifications.ID_CONFIRM_DELETE)
    }

    fun dismissPendingDeletion() {
        prefs.setPendingDeletion(null)
        Notifications.cancel(context, Notifications.ID_CONFIRM_DELETE)
    }

    fun clearError() {
        if (_status.value is SyncStatus.Failed) _status.value = SyncStatus.Idle
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }
}
