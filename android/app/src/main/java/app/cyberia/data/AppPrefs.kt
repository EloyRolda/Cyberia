package app.cyberia.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.cyberia.core.CyberiaJson
import app.cyberia.core.PairedPc
import app.cyberia.core.PendingDeletion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Resumen de la última sincronización, para mostrar en la app. */
@Serializable
data class LastSync(
    val atMs: Long,
    val downloaded: Int,
    val deleted: Int,
    val failed: Int,
    val firstFailure: String? = null,
    val noSpace: Boolean = false,
    val complete: Boolean = true,
    val manifestVersion: Long = 0,
)

/** Configuración y estado chico de la app, en SharedPreferences. */
class AppPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("cyberia", Context.MODE_PRIVATE)

    private val _pairedPc = MutableStateFlow(readJson<PairedPc>(KEY_PC))
    /** La PC vinculada (RF-04). `null` si no hay. */
    val pairedPc: StateFlow<PairedPc?> = _pairedPc.asStateFlow()

    private val _lastSync = MutableStateFlow(readJson<LastSync>(KEY_LAST_SYNC))
    val lastSync: StateFlow<LastSync?> = _lastSync.asStateFlow()

    private val _pendingDeletion = MutableStateFlow(readJson<PendingDeletion>(KEY_PENDING_DELETION))
    val pendingDeletion: StateFlow<PendingDeletion?> = _pendingDeletion.asStateFlow()

    private val _manualHost = MutableStateFlow(sp.getString(KEY_MANUAL_HOST, null))
    val manualHost: StateFlow<String?> = _manualHost.asStateFlow()

    fun setPairedPc(pc: PairedPc?) {
        writeJson(KEY_PC, pc)
        _pairedPc.value = pc
    }

    /** Recuerda la dirección donde respondió la PC, para probarla primero la próxima vez. */
    fun rememberHost(host: String, port: Int) {
        val pc = _pairedPc.value ?: return
        val hosts = (listOf(host) + pc.knownHosts.filter { it != host }).take(4)
        if (hosts != pc.knownHosts || port != pc.port) setPairedPc(pc.copy(knownHosts = hosts, port = port))
    }

    fun setLastSync(value: LastSync) {
        writeJson(KEY_LAST_SYNC, value)
        _lastSync.value = value
    }

    fun setPendingDeletion(value: PendingDeletion?) {
        writeJson(KEY_PENDING_DELETION, value)
        _pendingDeletion.value = value
    }

    /** IP de la PC escrita a mano, por si el router bloquea el descubrimiento (mDNS). */
    fun setManualHost(value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        sp.edit { putString(KEY_MANUAL_HOST, clean) }
        _manualHost.value = clean
    }

    /** Identificador estable de este celular, sin datos personales. */
    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit { putString(KEY_DEVICE_ID, it) }
        }

    // ---- Reproducción (RF-36) ----

    /** Playlist que se estaba escuchando. [ALL_TRACKS] para toda la biblioteca. */
    var lastQueue: String?
        get() = sp.getString(KEY_QUEUE, null)
        set(value) = sp.edit { putString(KEY_QUEUE, value) }

    var lastTrackPath: String?
        get() = sp.getString(KEY_TRACK, null)
        set(value) = sp.edit { putString(KEY_TRACK, value) }

    var lastPositionMs: Long
        get() = sp.getLong(KEY_POSITION, 0)
        set(value) = sp.edit { putLong(KEY_POSITION, value) }

    var shuffle: Boolean
        get() = sp.getBoolean(KEY_SHUFFLE, false)
        set(value) = sp.edit { putBoolean(KEY_SHUFFLE, value) }

    var batteryTipDismissed: Boolean
        get() = sp.getBoolean(KEY_BATTERY_TIP, false)
        set(value) = sp.edit { putBoolean(KEY_BATTERY_TIP, value) }

    private inline fun <reified T> readJson(key: String): T? =
        sp.getString(key, null)?.let { runCatching { CyberiaJson.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> writeJson(key: String, value: T?) {
        sp.edit {
            if (value == null) remove(key) else putString(key, CyberiaJson.encodeToString(value))
        }
    }

    companion object {
        const val ALL_TRACKS = "*"
        private const val KEY_PC = "paired_pc"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_PENDING_DELETION = "pending_deletion"
        private const val KEY_MANUAL_HOST = "manual_host"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_QUEUE = "last_queue"
        private const val KEY_TRACK = "last_track"
        private const val KEY_POSITION = "last_position"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_BATTERY_TIP = "battery_tip_dismissed"
    }
}
