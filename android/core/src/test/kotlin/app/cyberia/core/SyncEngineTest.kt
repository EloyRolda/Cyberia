package app.cyberia.core

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pruebas del motor sin red: las "descargas" copian desde un mapa en memoria. */
class SyncEngineTest {
    private val dir: File = Files.createTempDirectory("cyberia-engine").toFile()
    private val tracks = InMemoryTrackStore()
    private val blobs = BlobStore(File(dir, "audio"))
    private var free = 10L * 1024 * 1024 * 1024
    private val engine = SyncEngine(tracks, blobs, freeSpace = { free }, reserveBytes = 1024)
    private val contents = mutableMapOf<String, ByteArray>()
    private var version = 0L

    private fun file(path: String, text: String): ManifestFile {
        val bytes = text.toByteArray()
        val id = Hashing.bytesId(bytes)
        contents[id] = bytes
        val playlist = path.substringBeforeLast('/', "")
        return ManifestFile(id, path, playlist, bytes.size.toLong())
    }

    private fun sync(vararg files: ManifestFile, allowLarge: Boolean = false, failIds: Set<String> = emptySet()) = runBlocking {
        engine.apply(Manifest(++version, files.toList()), allowLarge, {}) { f, target, _ ->
            if (f.id in failIds) throw CyberiaException.Network(java.io.IOException("corte"))
            target.parentFile.mkdirs()
            target.writeBytes(contents.getValue(f.id))
            f.size
        }
    }

    private fun paths() = runBlocking { tracks.all().map { it.path }.sorted() }

    @Test
    fun descargaVerificaYArmaPlaylists() {
        val r = sync(file("Rock/a.mp3", "aaa"), file("Rock/b.mp3", "bbb"), file("c.ogg", "ccc"))
        assertEquals(3, r.downloaded.size)
        assertTrue(r.complete)
        assertEquals(listOf("Rock/a.mp3", "Rock/b.mp3", "c.ogg"), paths())
        assertEquals("Rock", runBlocking { tracks.all() }.first { it.path == "Rock/a.mp3" }.playlist)
        // Nada visible a medio copiar: no quedan parciales.
        assertEquals(0, File(dir, "audio/.partial").listFiles()!!.size)
    }

    @Test
    fun moverNoVuelveADescargar() {
        val a = file("Rock/a.mp3", "aaa")
        sync(a, file("c.ogg", "ccc"))
        val r = sync(a.copy(path = "Correr/a.mp3", playlist = "Correr"), file("c.ogg", "ccc"))
        assertEquals(0, r.downloaded.size)
        assertEquals(1, r.moved)
        assertNull(r.pendingDeletion)
        assertEquals(listOf("Correr/a.mp3", "c.ogg"), paths())
    }

    @Test
    fun huellaQueNoCoincideNoSeGuarda() {
        val bad = file("x.mp3", "xxx").copy(id = Hashing.bytesId("otra cosa".toByteArray()))
        contents[bad.id] = "xxx".toByteArray()
        val r = sync(bad)
        assertEquals(1, r.failed.size)
        assertTrue(paths().isEmpty())
        assertTrue(blobs.ids().isEmpty())
    }

    @Test
    fun borraRecienConTodoDescargado() {
        val a = file("a.mp3", "aaa")
        val b = file("b.mp3", "bbb")
        val c = file("c.mp3", "ccc")
        sync(a, b, c)
        val d = file("d.mp3", "ddd")
        // d falla: c no se borra todavía.
        val r1 = sync(a, b, d, failIds = setOf(d.id))
        assertEquals(1, r1.failed.size)
        assertTrue(r1.deleted.isEmpty())
        assertTrue("c.mp3" in paths())
        // Con todo descargado, c se borra (1 de 3 no es más de la mitad).
        val r2 = sync(a, b, d)
        assertEquals(listOf("c.mp3"), r2.deleted.map { it.path })
        assertEquals(listOf("a.mp3", "b.mp3", "d.mp3"), paths())
        assertEquals(3, blobs.ids().size)
    }

    @Test
    fun borradoGrandePideConfirmacion() {
        val a = file("a.mp3", "aaa")
        sync(a, file("b.mp3", "bbb"), file("c.mp3", "ccc"))
        // La carpeta de la PC quedó casi vacía (por ejemplo, un disco desconectado).
        val r = sync(a)
        val pending = assertNotNull(r.pendingDeletion)
        assertEquals(2, pending.paths.size)
        assertEquals(3, paths().size)
        assertEquals(2, r.toReport().pendingDeletions)
        val deleted = runBlocking { engine.confirmDeletion(pending) }
        assertEquals(2, deleted.size)
        assertEquals(listOf("a.mp3"), paths())
        assertEquals(1, blobs.ids().size)
    }

    @Test
    fun sinEspacioNoBorraNada() {
        val a = file("a.mp3", "aaa")
        sync(a, file("b.mp3", "bbb"))
        free = 0
        val r = sync(file("c.mp3", "ccc"))
        assertTrue(r.noSpace)
        assertTrue(r.deleted.isEmpty())
        assertEquals(listOf("a.mp3", "b.mp3"), paths())
    }
}
