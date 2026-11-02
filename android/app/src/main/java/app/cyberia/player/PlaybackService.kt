package app.cyberia.player

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.cyberia.R
import app.cyberia.data.AppPrefs
import app.cyberia.data.TrackEntity
import app.cyberia.graph
import app.cyberia.sync.Notifications
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reproductor sin conexión (RF-30) con Media3: sigue sonando con la pantalla apagada (RF-32), se
 * controla desde la pantalla bloqueada y la notificación (RF-33), responde a los botones de
 * auriculares con cable y Bluetooth (RF-34), pausa al desconectarlos (RF-35) y retoma el último
 * audio donde quedó (RF-36).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var saveJob: Job? = null
    private val prefs: AppPrefs get() = applicationContext.graph.prefs

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.shuffleModeEnabled = prefs.shuffle
        player.addListener(playerListener)

        session = MediaSession.Builder(this, player)
            .setSessionActivity(Notifications.openAppIntent(this))
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_cyberia)
            },
        )

        scope.launch { restore(player) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = savePosition()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            savePosition()
            saveJob?.cancel()
            if (isPlaying) {
                saveJob = scope.launch {
                    while (isActive) {
                        delay(5_000)
                        savePosition()
                    }
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            prefs.shuffle = shuffleModeEnabled
        }
    }

    private fun savePosition() {
        val player = session?.player ?: return
        val item = player.currentMediaItem ?: return
        prefs.lastTrackPath = item.mediaId
        prefs.lastPositionMs = player.currentPosition.coerceAtLeast(0)
    }

    /** Arma la cola guardada: la misma playlist, el mismo audio y la misma posición. */
    private suspend fun savedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val queue = prefs.lastQueue ?: return null
        val path = prefs.lastTrackPath ?: return null
        val dao = applicationContext.graph.database.tracks()
        val tracks = if (queue == AppPrefs.ALL_TRACKS) dao.all() else dao.byPlaylist(queue)
        val items = tracks.mapNotNull { toMediaItem(it) }
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.mediaId == path }
        return if (index >= 0) {
            MediaSession.MediaItemsWithStartPosition(items, index, prefs.lastPositionMs)
        } else {
            MediaSession.MediaItemsWithStartPosition(items, 0, 0)
        }
    }

    private suspend fun restore(player: ExoPlayer) {
        val saved = savedQueue() ?: return
        if (player.mediaItemCount > 0) return
        player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
        player.prepare()
    }

    private suspend fun resolve(mediaId: String): MediaItem? {
        val track = applicationContext.graph.database.tracks().byPath(mediaId) ?: return null
        return toMediaItem(track)
    }

    private fun toMediaItem(track: TrackEntity): MediaItem? {
        val file = applicationContext.graph.blobs.file(track.id)
        if (!file.isFile) return null
        return MediaItem.Builder()
            .setMediaId(track.path)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(mediaMetadata(track))
            .build()
    }

    private inner class SessionCallback : MediaSession.Callback {
        /** La app manda solo la ruta de cada audio; acá se le pone el archivo local. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                try {
                    future.set(mediaItems.mapNotNull { resolve(it.mediaId) }.toMutableList())
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        /** Botón de reproducir de los auriculares o del sistema con la app cerrada. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val saved = runCatching { savedQueue() }.getOrNull()
                if (saved != null) {
                    future.set(saved)
                } else {
                    future.setException(UnsupportedOperationException("No hay nada para retomar"))
                }
            }
            return future
        }
    }

    companion object {
        fun playlistLabel(playlist: String): String = playlist.ifEmpty { "Sueltos" }

        fun mediaMetadata(track: TrackEntity): MediaMetadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(playlistLabel(track.playlist))
            .setAlbumTitle(playlistLabel(track.playlist))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    }
}
