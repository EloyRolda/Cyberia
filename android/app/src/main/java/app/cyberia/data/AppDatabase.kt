package app.cyberia.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.cyberia.core.LocalTrack
import app.cyberia.core.TrackStore
import kotlinx.coroutines.flow.Flow

/** Un audio descargado: la ruta que tiene en la PC y la huella de su contenido. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val path: String,
    val id: String,
    val playlist: String,
    val size: Long,
)

val TrackEntity.fileName: String get() = path.substringAfterLast('/')

/** Nombre del archivo sin extensión. Las etiquetas del archivo quedan para v1 (RF-39). */
val TrackEntity.title: String get() = fileName.substringBeforeLast('.', fileName)

@Dao
interface TrackDao {
    /** Playlists por subcarpeta y audios ordenados por nombre de archivo (RF-21). */
    @Query("SELECT * FROM tracks ORDER BY playlist COLLATE NOCASE, path COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY playlist COLLATE NOCASE, path COLLATE NOCASE")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE playlist = :playlist ORDER BY path COLLATE NOCASE")
    suspend fun byPlaylist(playlist: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun byPath(path: String): TrackEntity?

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun delete(paths: List<String>)
}

@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
}

/** La biblioteca del motor de sincronización, guardada en Room. */
class RoomTrackStore(private val dao: TrackDao) : TrackStore {
    override suspend fun all(): List<LocalTrack> =
        dao.all().map { LocalTrack(it.path, it.id, it.playlist, it.size) }

    override suspend fun upsert(tracks: List<LocalTrack>) {
        tracks.chunked(300).forEach { chunk ->
            dao.upsert(chunk.map { TrackEntity(it.path, it.id, it.playlist, it.size) })
        }
    }

    override suspend fun delete(paths: Collection<String>) {
        // SQLite acepta hasta 999 parámetros por consulta en Android viejos.
        paths.toList().chunked(300).forEach { dao.delete(it) }
    }
}
