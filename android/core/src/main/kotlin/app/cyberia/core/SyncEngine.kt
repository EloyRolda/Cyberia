package app.cyberia.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Avance de una sincronización, para la notificación y la interfaz. */
data class SyncProgress(
    val index: Int,
    val total: Int,
    val path: String,
    val bytesDone: Long,
    val bytesTotal: Long,
)

/** Borrado grande que espera confirmación del usuario (RF-20). */
@Serializable
data class PendingDeletion(
    val manifestVersion: Long,
    val paths: List<String>,
)

data class SyncResult(
    val manifestVersion: Long,
    val downloaded: List<ManifestFile>,
    val deleted: List<LocalTrack>,
    val moved: Int,
    val failed: List<FailedItem>,
    val pendingDeletion: PendingDeletion?,
    val noSpace: Boolean,
    val freeBytes: Long,
    val libraryCount: Int,
    val libraryBytes: Long,
    /** El celular tiene todos los audios del manifiesto. */
    val complete: Boolean,
    /** Se pudo informar el resultado a la PC. */
    val reported: Boolean,
) {
    fun toReport(): SyncReport = SyncReport(
        manifestVersion = manifestVersion,
        downloaded = downloaded.map { ReportItem(it.id, it.path) },
        deleted = deleted.map { ReportItem(it.id, it.path) },
        failed = failed,
        pendingDeletions = pendingDeletion?.paths?.size ?: 0,
        noSpace = noSpace,
        freeBytes = freeBytes,
        libraryCount = libraryCount,
        libraryBytes = libraryBytes,
        complete = complete,
    )
}

/**
 * Sincronización en una sola dirección, de la PC al celular (RF-17):
 *
 * 1. Pide el manifiesto y lo compara con la biblioteca local.
 * 2. Descarga lo nuevo o cambiado, de a un archivo, a un temporal; retoma lo cortado (RF-18, RF-23).
 * 3. Verifica tamaño y huella y recién ahí le pone el nombre final (RNF-08).
 * 4. Con todo descargado, borra lo que ya no está (RF-19); si es más de la mitad, pide
 *    confirmación (RF-20).
 * 5. Informa el resultado a la PC (RF-25).
 *
 * Los audios se identifican por huella: si en la PC se mueven o renombran, en el celular solo
 * cambia la ruta y no se vuelven a descargar.
 */
class SyncEngine(
    private val tracks: TrackStore,
    private val blobs: BlobStore,
    /** Bytes libres en el almacenamiento de la app. */
    private val freeSpace: () -> Long,
    /** Espacio que se deja siempre libre en el celular. */
    private val reserveBytes: Long = 200L * 1024 * 1024,
) {
    private val mutex = Mutex()

    /** `true` mientras corre una sincronización. */
    val isRunning: Boolean get() = mutex.isLocked

    suspend fun sync(
        client: CyberiaClient,
        endpoint: Endpoint,
        token: String,
        allowLargeDeletion: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = mutex.withLock {
        val manifest = client.manifest(endpoint, token)
        val result = apply(manifest, allowLargeDeletion, onProgress) { file, target, progress ->
            client.download(endpoint, token, file.id, target, file.size, progress)
        }
        val reported = try {
            client.report(endpoint, token, result.toReport())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: CyberiaException) {
            false
        }
        result.copy(reported = reported)
    }

    /**
     * Aplica un borrado grande que el usuario confirmó (RF-20). Funciona sin conexión.
     */
    suspend fun confirmDeletion(pending: PendingDeletion): List<LocalTrack> = mutex.withLock {
        val current = tracks.all()
        val toDelete = current.filter { it.path in pending.paths.toSet() }
        tracks.delete(toDelete.map { it.path })
        removeOrphanBlobs(keep = emptySet())
        toDelete
    }

    /** Núcleo de la sincronización, separado de la red para poder probarlo. */
    internal suspend fun apply(
        manifest: Manifest,
        allowLargeDeletion: Boolean,
        onProgress: (SyncProgress) -> Unit,
        download: suspend (ManifestFile, java.io.File, (Long) -> Unit) -> Long,
    ): SyncResult {
        val manifestIds = manifest.files.map { it.id }.toSet()
        val local = tracks.all()

        // 1. Qué contenidos faltan (una sola vez por huella).
        val missing = manifest.files
            .distinctBy { it.id }
            .filter { !blobs.has(it.id) }
            .sortedWith(compareBy({ it.playlist.lowercase() }, { it.path.lowercase() }))

        val downloaded = mutableListOf<ManifestFile>()
        val failed = mutableListOf<FailedItem>()
        var noSpace = false
        var networkDown = false

        // 2 y 3. Descargas de a una, verificadas.
        for ((index, file) in missing.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (networkDown) {
                failed += FailedItem(file.id, file.path, "Se cortó la conexión con la PC")
                continue
            }
            val needed = file.size - blobs.partialSize(file.id)
            if (freeSpace() - needed < reserveBytes) {
                noSpace = true
                failed += FailedItem(file.id, file.path, "No hay espacio en el celular")
                continue
            }
            val part = blobs.partial(file.id)
            try {
                onProgress(SyncProgress(index + 1, missing.size, file.path, blobs.partialSize(file.id), file.size))
                download(file, part) { done ->
                    onProgress(SyncProgress(index + 1, missing.size, file.path, done, file.size))
                }
                val reason = verify(file, part)
                if (reason != null) {
                    part.delete()
                    failed += FailedItem(file.id, file.path, reason)
                    continue
                }
                if (!blobs.commit(file.id)) {
                    failed += FailedItem(file.id, file.path, "No se pudo guardar en el celular")
                    continue
                }
                downloaded += file
                // El audio aparece en cuanto llegó completo y verificado.
                tracks.upsert(manifest.files.filter { it.id == file.id }.map { it.toTrack() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: CyberiaException.Unpaired) {
                throw e
            } catch (e: CyberiaException.CertificateMismatch) {
                throw e
            } catch (e: CyberiaException.FileChanged) {
                part.delete()
                failed += FailedItem(file.id, file.path, "Cambió en la PC; volvé a sincronizar")
            } catch (e: CyberiaException.FileMissing) {
                part.delete()
                failed += FailedItem(file.id, file.path, "Ya no está en la carpeta de la PC")
            } catch (e: CyberiaException.Network) {
                networkDown = true
                failed += FailedItem(file.id, file.path, "Se cortó la conexión con la PC")
            } catch (e: java.io.IOException) {
                val lowSpace = freeSpace() < reserveBytes
                noSpace = noSpace || lowSpace
                failed += FailedItem(
                    file.id,
                    file.path,
                    if (lowSpace) "No hay espacio en el celular" else (e.message ?: "Error al guardar"),
                )
            }
        }

        // Rutas nuevas o movidas de contenidos que ya estaban: solo cambia la ruta.
        val localByPath = local.associateBy { it.path }
        val routeUpdates = manifest.files
            .filter { blobs.has(it.id) }
            .map { it.toTrack() }
            .filter { localByPath[it.path] != it }
        if (routeUpdates.isNotEmpty()) tracks.upsert(routeUpdates)
        val downloadedIds = downloaded.map { it.id }.toSet()
        val moved = routeUpdates.count { it.id !in downloadedIds && localByPath[it.path] == null }

        // 4. Borrados, recién con todo descargado.
        val manifestPaths = manifest.files.map { it.path }.toSet()
        val stalePaths = local.filter { it.path !in manifestPaths }
        // Rutas viejas de audios que siguen en la PC con otra ruta: se quitan sin preguntar.
        val movedAway = stalePaths.filter { it.id in manifestIds }
        // Audios que desaparecieron de la PC.
        val removed = stalePaths.filter { it.id !in manifestIds }
        if (movedAway.isNotEmpty()) tracks.delete(movedAway.map { it.path })

        var deleted = emptyList<LocalTrack>()
        var pending: PendingDeletion? = null
        if (removed.isNotEmpty()) {
            val allDownloaded = failed.isEmpty()
            val libraryBefore = local.size
            val large = removed.size * 2 > libraryBefore
            when {
                !allDownloaded -> Unit // RF-19: se borra recién cuando todo llegó.
                large && !allowLargeDeletion -> pending = PendingDeletion(manifest.version, removed.map { it.path })
                else -> {
                    tracks.delete(removed.map { it.path })
                    deleted = removed
                }
            }
        }

        // Limpieza de contenidos sin rutas y descargas parciales que ya no hacen falta.
        removeOrphanBlobs(keep = manifestIds)
        blobs.cleanupPartials(keep = manifestIds - blobs.ids())

        val finalTracks = tracks.all()
        val haveIds = blobs.ids()
        return SyncResult(
            manifestVersion = manifest.version,
            downloaded = downloaded,
            deleted = deleted,
            moved = moved,
            failed = failed,
            pendingDeletion = pending,
            noSpace = noSpace,
            freeBytes = freeSpace(),
            libraryCount = finalTracks.size,
            libraryBytes = finalTracks.distinctBy { it.id }.sumOf { it.size },
            complete = manifestIds.all { it in haveIds },
            reported = false,
        )
    }

    private suspend fun removeOrphanBlobs(keep: Set<String>) {
        val referenced = tracks.all().map { it.id }.toSet() + keep
        blobs.ids().filter { it !in referenced }.forEach { blobs.delete(it) }
    }

    private fun verify(file: ManifestFile, part: java.io.File): String? {
        if (!part.isFile || part.length() != file.size) {
            return "Llegó incompleto (${part.length()} de ${file.size} bytes)"
        }
        if (Hashing.fileId(part) != file.id) {
            return "La huella no coincide; volvé a sincronizar"
        }
        return null
    }
}

private fun ManifestFile.toTrack() = LocalTrack(path = path, id = id, playlist = playlist, size = size)
