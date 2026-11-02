package app.cyberia

import android.content.Context
import androidx.room.Room
import app.cyberia.core.BlobStore
import app.cyberia.core.SyncEngine
import app.cyberia.data.AppDatabase
import app.cyberia.data.AppPrefs
import app.cyberia.data.RoomTrackStore
import app.cyberia.net.PcLocator
import app.cyberia.sync.SyncManager
import java.io.File

/** Objetos compartidos por la app, los servicios y las tareas en segundo plano. */
class AppGraph(context: Context) {
    private val app = context.applicationContext

    val prefs = AppPrefs(app)

    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "cyberia.db").build()

    val trackStore = RoomTrackStore(database.tracks())

    /** Audios en el almacenamiento propio de la app, sin pedir permisos (RNF-11). */
    val blobs = BlobStore(File(app.filesDir, "audio"))

    val engine = SyncEngine(
        tracks = trackStore,
        blobs = blobs,
        freeSpace = { app.filesDir.usableSpace },
    )

    val locator = PcLocator(app, prefs)

    val syncManager = SyncManager(app, prefs, engine, locator)
}
