package app.cyberia.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.cyberia.data.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado del reproductor para la interfaz. */
data class PlayerUi(
    val currentPath: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** Conexión de la interfaz con [PlaybackService] a través de un `MediaController`. */
class PlayerConnection(context: Context) {
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    private val controller: MediaController?
        get() = if (future.isDone && !future.isCancelled) runCatching { future.get() }.getOrNull() else null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        future.addListener({
            controller?.addListener(listener)
            refresh()
        }, ContextCompat.getMainExecutor(context))
    }

    fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _state.value = PlayerUi(
            currentPath = item?.mediaId,
            title = c.mediaMetadata.title?.toString() ?: item?.mediaId?.substringAfterLast('/'),
            subtitle = c.mediaMetadata.artist?.toString(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = item != null,
        )
    }

    /** Reproduce [tracks] desde [startIndex]. */
    fun play(tracks: List<TrackEntity>, startIndex: Int) {
        val c = controller ?: return
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.path)
                .setMediaMetadata(PlaybackService.mediaMetadata(track))
                .build()
        }
        c.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
        c.play()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }
}
