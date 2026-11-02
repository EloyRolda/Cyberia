package app.cyberia.core

import java.io.File

/** Un audio de la biblioteca del celular: una ruta de la PC apuntando a un contenido. */
data class LocalTrack(
    val path: String,
    val id: String,
    val playlist: String,
    val size: Long,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** Lista de audios del celular. En Android la implementa Room; en las pruebas, memoria. */
interface TrackStore {
    suspend fun all(): List<LocalTrack>
    suspend fun upsert(tracks: List<LocalTrack>)
    suspend fun delete(paths: Collection<String>)
}

class InMemoryTrackStore : TrackStore {
    private val tracks = linkedMapOf<String, LocalTrack>()
    private val lock = Any()

    override suspend fun all(): List<LocalTrack> = synchronized(lock) { tracks.values.toList() }

    override suspend fun upsert(tracks: List<LocalTrack>) = synchronized(lock) {
        tracks.forEach { this.tracks[it.path] = it }
    }

    override suspend fun delete(paths: Collection<String>) = synchronized(lock) {
        paths.forEach { tracks.remove(it) }
    }
}

/**
 * Archivos de audio en el almacenamiento propio de la app (RNF-11), guardados por huella.
 *
 * Las descargas van a `.partial/` y recién pasan a su nombre final cuando llegaron completas y
 * verificadas, así nunca queda visible un audio a medio copiar (RNF-08).
 */
class BlobStore(val dir: File) {
    private val partialDir = File(dir, ".partial")

    init {
        dir.mkdirs()
        partialDir.mkdirs()
    }

    fun file(id: String): File = File(dir, key(id))

    fun partial(id: String): File = File(partialDir, key(id) + ".part")

    fun has(id: String): Boolean = file(id).isFile

    fun partialSize(id: String): Long = partial(id).let { if (it.isFile) it.length() else 0L }

    /** Pasa la descarga verificada a su nombre final. */
    fun commit(id: String): Boolean {
        val target = file(id)
        val part = partial(id)
        if (target.exists()) target.delete()
        return part.renameTo(target)
    }

    fun delete(id: String) {
        file(id).delete()
        partial(id).delete()
    }

    /** Huellas de los audios completos guardados. */
    fun ids(): Set<String> =
        dir.listFiles { f -> f.isFile && f.name.length == 64 }?.map { "b3:" + it.name }?.toSet() ?: emptySet()

    /** Borra descargas parciales de audios que ya no hacen falta. */
    fun cleanupPartials(keep: Set<String>) {
        val keepNames = keep.map { key(it) + ".part" }.toSet()
        partialDir.listFiles()?.forEach { if (it.name !in keepNames) it.delete() }
    }

    fun usedBytes(): Long = dir.listFiles { f -> f.isFile }?.sumOf { it.length() } ?: 0L

    private fun key(id: String): String {
        val hex = id.removePrefix("b3:")
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "huella inválida: $id" }
        return hex
    }
}
