package app.cyberia.sync

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.cyberia.core.CyberiaClient
import app.cyberia.core.CyberiaException
import app.cyberia.core.PairedPc
import app.cyberia.core.PcEvent
import app.cyberia.graph
import app.cyberia.net.WifiNetworks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Modo espera (RF-40): un servicio en primer plano tipo `connectedDevice`, con una notificación
 * fija, que mientras el celular carga y está en el Wi-Fi mantiene abierta la conexión de avisos
 * con la PC y descarga al instante. Sin cargar, la conexión se cierra y quedan las revisiones
 * periódicas (RNF-03).
 *
 * El servicio queda siempre activo; lo que se abre y cierra con la carga es la conexión, porque
 * Android no deja arrancar un servicio en primer plano desde segundo plano al enchufar.
 */
class StandbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val charging = MutableStateFlow(false)
    private val onWifi = MutableStateFlow(false)
    private val link = MutableStateFlow<Link>(Link.Idle)
    private var current: android.app.Notification? = null

    private sealed interface Link {
        data object Idle : Link
        data object Searching : Link
        data class Connected(val pcName: String) : Link
        data class NotFound(val pcName: String) : Link
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> charging.value = true
                Intent.ACTION_POWER_DISCONNECTED -> {
                    charging.value = false
                    // Si Android cerrara el servicio, igual se revisa al volver a cargar.
                    SyncWorker.scheduleWhenCharging(context)
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onWifi.value = true
        }

        override fun onLost(network: Network) {
            onWifi.value = WifiNetworks.isConnected(this@StandbyService)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!enterForeground()) {
            stopSelf()
            return
        }
        charging.value = isCharging()
        onWifi.value = WifiNetworks.isConnected(this)

        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
        )

        val graph = applicationContext.graph
        scope.launch {
            combine(graph.prefs.pairedPc, charging, onWifi) { pc, isCharging, wifi -> Triple(pc, isCharging, wifi) }
                .distinctUntilChanged()
                .collectLatest { (pc, isCharging, wifi) ->
                    when {
                        pc == null -> stopSelf()
                        isCharging && wifi -> listen(pc)
                        else -> link.value = Link.Idle
                    }
                }
        }

        scope.launch {
            combine(link, graph.syncManager.status, charging, onWifi) { link, status, isCharging, wifi ->
                notificationText(link, status, isCharging, wifi)
            }.distinctUntilChanged().collect { (title, text, progress) ->
                val notification = Notifications.standby(this@StandbyService, title, text, progress)
                current = notification
                getSystemService(android.app.NotificationManager::class.java)
                    ?.notify(Notifications.ID_STANDBY, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cada startForegroundService() espera un startForeground(); se repite con el aviso vigente.
        if (!enterForeground()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notification = current ?: Notifications.standby(this, "Cyberia en espera", "Preparando…")
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notifications.ID_STANDBY, notification, type)
        true
    } catch (e: Exception) {
        // Por ejemplo, si Android no permite arrancarlo desde segundo plano.
        Log.w(TAG, "no se pudo pasar a primer plano", e)
        false
    }

    /** Conexión de avisos con la PC, con reintentos, mientras siga cargando. */
    private suspend fun listen(pc: PairedPc) {
        val graph = applicationContext.graph
        var backoffMs = 5_000L
        while (true) {
            val network = WifiNetworks.current(this)
            if (network == null) {
                link.value = Link.Idle
                delay(backoffMs)
                continue
            }
            link.value = Link.Searching
            try {
                val endpoint = graph.locator.locate(pc, network)
                if (endpoint == null) {
                    link.value = Link.NotFound(pc.pcName)
                } else {
                    graph.prefs.rememberHost(endpoint.host, endpoint.port)
                    link.value = Link.Connected(pc.pcName)
                    backoffMs = 5_000L
                    val client = CyberiaClient(pc.fingerprint, network.socketFactory)
                    // Al conectarse llega la versión actual: se baja lo pendiente (RF-22).
                    client.events(endpoint, pc.token).conflate().collect { event ->
                        when (event) {
                            is PcEvent.Version -> graph.syncManager.syncNow(Trigger.Push)
                            PcEvent.Unpaired -> graph.syncManager.onUnpairedByPc()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CyberiaException.Unpaired) {
                graph.syncManager.onUnpairedByPc()
                return
            } catch (e: Exception) {
                Log.i(TAG, "se cortó la conexión de avisos: ${e.message}")
            }
            link.value = if (link.value is Link.Connected) Link.Searching else link.value
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun notificationText(
        link: Link,
        status: SyncStatus,
        isCharging: Boolean,
        wifi: Boolean,
    ): Triple<String, String, Pair<Int, Int>?> {
        if (status is SyncStatus.Syncing) {
            val p = status.progress
            return if (p == null || p.total == 0) {
                Triple("Sincronizando", "Revisando qué hay de nuevo…", 0 to 0)
            } else {
                Triple("Descargando ${p.index} de ${p.total}", p.path.substringAfterLast('/'), p.index - 1 to p.total)
            }
        }
        return when {
            !isCharging -> Triple(
                "Cyberia en espera",
                "Al cargar en el Wi-Fi de casa, descarga al instante. Mientras tanto revisa cada 15 minutos.",
                null,
            )
            !wifi -> Triple("Cyberia en espera", "Esperando el Wi-Fi de casa.", null)
            link is Link.Connected -> Triple("Conectado a ${link.pcName}", "Los audios nuevos se descargan al instante.", null)
            link is Link.NotFound -> Triple("Cyberia en espera", "No encuentro a ${link.pcName}. Vuelvo a probar en un rato.", null)
            else -> Triple("Cyberia en espera", "Buscando la PC…", null)
        }
    }

    /** Enchufado, aunque la batería ya esté llena. */
    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    companion object {
        private const val TAG = "CyberiaStandby"

        /** Arranca el modo espera si hay una PC vinculada. */
        fun start(context: Context) {
            if (context.applicationContext.graph.prefs.pairedPc.value == null) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, StandbyService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo arrancar el modo espera", e)
            }
        }
    }
}
