package app.cyberia.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cyberia.core.CyberiaException
import app.cyberia.core.PairingInfo
import app.cyberia.data.AppPrefs
import app.cyberia.data.TrackEntity
import app.cyberia.graph
import app.cyberia.player.PlaybackService
import app.cyberia.player.PlayerConnection
import app.cyberia.sync.StandbyService
import app.cyberia.sync.Trigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Una playlist: la subcarpeta de la PC y sus audios, ordenados por nombre de archivo (RF-21). */
data class PlaylistGroup(val name: String, val tracks: List<TrackEntity>) {
    val label: String get() = PlaybackService.playlistLabel(name)
    val bytes: Long get() = tracks.sumOf { it.size }
}

sealed interface PairingUi {
    data object Idle : PairingUi

    /** Un enlace `cyberia://pair` abrió la app: se pide confirmación antes de vincular. */
    data class Confirm(val info: PairingInfo, val replacing: String?) : PairingUi
    data class Working(val pcName: String) : PairingUi
    data class Error(val message: String) : PairingUi
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val prefs = graph.prefs
    private val syncManager = graph.syncManager

    val pairedPc = prefs.pairedPc
    val lastSync = prefs.lastSync
    val pendingDeletion = prefs.pendingDeletion
    val manualHost = prefs.manualHost
    val syncStatus = syncManager.status

    val playlists: StateFlow<List<PlaylistGroup>> = graph.database.tracks().observeAll()
        .map { tracks ->
            tracks.groupBy { it.playlist }
                .map { (name, list) -> PlaylistGroup(name, list) }
                // Los sueltos (raíz de la carpeta) al final.
                .sortedWith(compareBy({ it.name.isEmpty() }, { it.name.lowercase() }))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val player = PlayerConnection(app)
    val playerState = player.state

    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()

    var batteryTipDismissed: Boolean
        get() = prefs.batteryTipDismissed
        set(value) {
            prefs.batteryTipDismissed = value
        }

    /** Resultado del escáner de QR de la app (RF-03). */
    fun onScanned(text: String?) {
        val info = parsePairing(text) ?: return
        pair(info)
    }

    /**
     * Enlace `cyberia://pair` (por ejemplo, el QR leído con la cámara del sistema). Como lo puede
     * abrir cualquier app o página, se pide confirmación antes de vincular.
     */
    fun onPairingLink(text: String?, autoConfirm: Boolean = false) {
        val info = parsePairing(text) ?: return
        if (autoConfirm) {
            pair(info)
        } else {
            _pairing.value = PairingUi.Confirm(info, replacing = pairedPc.value?.pcName)
        }
    }

    fun confirmPairing() {
        val state = _pairing.value
        if (state is PairingUi.Confirm) pair(state.info)
    }

    private fun parsePairing(text: String?): PairingInfo? {
        if (text.isNullOrBlank()) return null
        return try {
            PairingInfo.parse(text)
        } catch (e: IllegalArgumentException) {
            _pairing.value = PairingUi.Error(e.message ?: "Ese QR no es de Cyberia.")
            null
        }
    }

    private fun pair(info: PairingInfo) {
        _pairing.value = PairingUi.Working(info.pcName)
        viewModelScope.launch {
            try {
                syncManager.pair(info)
                _pairing.value = PairingUi.Idle
                StandbyService.start(getApplication())
                syncManager.syncNow(Trigger.App)
            } catch (e: CyberiaException.Network) {
                _pairing.value = PairingUi.Error(
                    "No pude conectarme con ${info.pcName}. Revisá que el celular esté en el mismo Wi-Fi que la PC.",
                )
            } catch (e: Exception) {
                _pairing.value = PairingUi.Error(e.message ?: "No se pudo vincular.")
            }
        }
    }

    fun clearPairingError() {
        _pairing.value = PairingUi.Idle
    }

    /** Revisión inmediata al abrir la app o al tirar para actualizar (RF-37). */
    fun syncNow(trigger: Trigger = Trigger.Pull) {
        viewModelScope.launch { syncManager.syncNow(trigger) }
    }

    fun clearSyncError() = syncManager.clearError()

    fun unpair() {
        viewModelScope.launch { syncManager.unpair() }
    }

    fun confirmDeletion() {
        viewModelScope.launch { syncManager.confirmPendingDeletion() }
    }

    fun dismissDeletion() = syncManager.dismissPendingDeletion()

    fun setManualHost(text: String?) = prefs.setManualHost(text)

    // ---- Reproductor ----

    fun play(group: PlaylistGroup, track: TrackEntity) {
        prefs.lastQueue = group.name
        player.play(group.tracks, group.tracks.indexOf(track).coerceAtLeast(0))
    }

    fun playShuffled(group: PlaylistGroup) {
        if (group.tracks.isEmpty()) return
        prefs.lastQueue = group.name
        if (!playerState.value.shuffle) player.toggleShuffle()
        player.play(group.tracks, group.tracks.indices.random())
    }

    fun playAll(groups: List<PlaylistGroup>) {
        val all = groups.flatMap { it.tracks }
        if (all.isEmpty()) return
        prefs.lastQueue = AppPrefs.ALL_TRACKS
        player.play(all, 0)
    }

    fun togglePlay() = player.togglePlay()
    fun next() = player.next()
    fun previous() = player.previous()
    fun toggleShuffle() = player.toggleShuffle()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun refreshPlayer() = player.refresh()

    override fun onCleared() {
        player.release()
    }
}
