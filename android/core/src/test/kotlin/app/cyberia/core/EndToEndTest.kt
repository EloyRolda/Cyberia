package app.cyberia.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Prueba de punta a punta contra el servidor Rust real (`cyberia-server`), con TLS, certificado
 * fijado, rangos y avisos SSE. Se saltea si el binario no está compilado.
 */
class EndToEndTest {
    private lateinit var root: File
    private lateinit var music: File
    private lateinit var server: ServerProcess

    @BeforeEach
    fun setUp() {
        val bin = File(System.getProperty("cyberia.server") ?: "")
        assumeTrue(bin.canExecute(), "falta el servidor: cargo build -p cyberia-server")
        root = Files.createTempDirectory("cyberia-e2e").toFile()
        music = File(root, "music").apply { mkdirs() }
        server = ServerProcess(bin, music, File(root, "pc-data"))
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun audio(path: String, size: Int): ByteArray {
        val bytes = Random(path.hashCode()).nextBytes(size)
        File(music, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        return bytes
    }

    @Test
    fun recorridoCompleto() = runBlocking {
        val tema1 = audio("Para correr/tema1.mp3", 700_000)
        val tema2 = audio("Para correr/tema2.mp3", 300_000)
        val intro = audio("intro.ogg", 50_000)
        File(music, "notas.txt").writeText("no es audio")
        assertTrue(server.sync().startsWith("SYNCED 1 true files=3"))

        // Emparejamiento.
        val info = PairingInfo.parse(server.pairUri())
        assertEquals(listOf("127.0.0.1"), info.hosts)
        assertEquals(server.fingerprint, info.fingerprint)
        val endpoint = info.endpoints.first()

        val impostor = CyberiaClient("00".repeat(32))
        assertFailsWith<CyberiaException.CertificateMismatch> {
            impostor.pair(endpoint, PairRequest(info.code, "dev-1", "Impostor"))
        }

        val client = CyberiaClient(info.fingerprint)
        val paired = client.pair(endpoint, PairRequest(info.code, "dev-1", "Pixel de prueba"))
        assertEquals(info.pcId, paired.pcId)
        assertFailsWith<CyberiaException.PairingExpired> {
            client.pair(endpoint, PairRequest(info.code, "dev-2", "Otro"))
        }
        val token = paired.token
        assertEquals(1, client.info(endpoint, token).manifestVersion)

        // Primera sincronización.
        val tracks = InMemoryTrackStore()
        val blobs = BlobStore(File(root, "phone/audio"))
        val engine = SyncEngine(tracks, blobs, freeSpace = { 50L * 1024 * 1024 * 1024 })
        val r1 = engine.sync(client, endpoint, token)
        assertEquals(3, r1.downloaded.size)
        assertTrue(r1.failed.isEmpty(), r1.failed.toString())
        assertTrue(r1.complete)
        assertTrue(r1.reported)
        val byPath = tracks.all().associateBy { it.path }
        assertContentEquals(tema1, blobs.file(byPath.getValue("Para correr/tema1.mp3").id).readBytes())
        assertContentEquals(tema2, blobs.file(byPath.getValue("Para correr/tema2.mp3").id).readBytes())
        assertContentEquals(intro, blobs.file(byPath.getValue("intro.ogg").id).readBytes())
        assertEquals("Para correr", byPath.getValue("Para correr/tema1.mp3").playlist)

        // La PC recibió el informe.
        val report = server.status()["lastReport"]!!.jsonObject
        assertEquals(3, report["downloaded"]!!.jsonArray.size)
        assertEquals("Pixel de prueba", report["deviceName"]!!.jsonPrimitive.content)

        // Sin cambios no se baja nada.
        assertTrue(engine.sync(client, endpoint, token).downloaded.isEmpty())

        // Descarga cortada: con una parte ya bajada, se pide solo lo que falta (206).
        val id1 = byPath.getValue("Para correr/tema1.mp3").id
        val partial = File(root, "partial.part")
        partial.writeBytes(ByteArray(400_000)) // ceros: si se usara el rango, quedan al principio
        val total = client.download(endpoint, token, id1, partial, tema1.size.toLong())
        assertEquals(tema1.size.toLong(), total)
        val resumed = partial.readBytes()
        assertContentEquals(ByteArray(400_000), resumed.copyOfRange(0, 400_000))
        assertContentEquals(tema1.copyOfRange(400_000, tema1.size), resumed.copyOfRange(400_000, resumed.size))

        // Y el motor completa una descarga cortada de verdad.
        blobs.delete(id1)
        blobs.partial(id1).writeBytes(tema1.copyOfRange(0, 250_000))
        val r2 = engine.sync(client, endpoint, token)
        assertEquals(listOf("Para correr/tema1.mp3"), r2.downloaded.map { it.path })
        assertContentEquals(tema1, blobs.file(id1).readBytes())

        // Conexión de avisos: primero la versión actual y después la nueva.
        val events = Channel<PcEvent>(Channel.UNLIMITED)
        val listening = launch(Dispatchers.IO) {
            runCatching { client.events(endpoint, token).collect { events.send(it) } }
        }
        assertEquals(PcEvent.Version(1), withTimeout(10_000) { events.receive() })

        // Mover un audio en la PC no lo vuelve a descargar.
        File(music, "Sueltos").mkdirs()
        File(music, "intro.ogg").renameTo(File(music, "Sueltos/intro.ogg"))
        assertTrue(server.sync().startsWith("SYNCED 2 true"))
        assertEquals(PcEvent.Version(2), withTimeout(10_000) { events.receive() })
        val r3 = engine.sync(client, endpoint, token)
        assertTrue(r3.downloaded.isEmpty())
        assertEquals(1, r3.moved)
        assertTrue(tracks.all().any { it.path == "Sueltos/intro.ogg" && it.playlist == "Sueltos" })
        assertTrue(tracks.all().none { it.path == "intro.ogg" })

        // Borrar uno de tres se aplica directo.
        File(music, "Para correr/tema2.mp3").delete()
        server.sync()
        val r4 = engine.sync(client, endpoint, token)
        assertEquals(listOf("Para correr/tema2.mp3"), r4.deleted.map { it.path })
        assertEquals(2, tracks.all().size)

        // Si la carpeta queda vacía (disco desconectado, por ejemplo) se pide confirmación.
        File(music, "Para correr/tema1.mp3").delete()
        File(music, "Sueltos/intro.ogg").delete()
        server.sync()
        val r5 = engine.sync(client, endpoint, token)
        val pending = assertNotNull(r5.pendingDeletion)
        assertEquals(2, pending.paths.size)
        assertEquals(2, tracks.all().size)
        assertEquals(2, server.status()["lastReport"]!!.jsonObject["pendingDeletions"]!!.jsonPrimitive.int)
        engine.confirmDeletion(pending)
        assertTrue(tracks.all().isEmpty())
        assertTrue(blobs.ids().isEmpty())

        // Desvincular desde la PC corta los avisos y anula el token.
        while (events.tryReceive().isSuccess) Unit
        server.send("unpair")
        val last = withTimeout(10_000) {
            var e = events.receive()
            while (e is PcEvent.Version) e = events.receive()
            e
        }
        assertEquals(PcEvent.Unpaired, last)
        assertFailsWith<CyberiaException.Unpaired> { client.manifest(endpoint, token) }
        listening.cancel()
    }

    @Test
    fun elCelularSeDesvincula() = runBlocking {
        audio("a.mp3", 1000)
        server.sync()
        val info = PairingInfo.parse(server.pairUri())
        val client = CyberiaClient(info.fingerprint)
        val endpoint = info.endpoints.first()
        val token = client.pair(endpoint, PairRequest(info.code, "dev-1", "Moto")).token
        client.unpair(endpoint, token)
        assertFailsWith<CyberiaException.Unpaired> { client.info(endpoint, token) }
        assertEquals("null", server.status()["device"].toString())
    }
}

/** Corre `cyberia-server` y habla con él por la entrada y salida estándar. */
class ServerProcess(bin: File, folder: File, dataDir: File) : AutoCloseable {
    private val process: Process = ProcessBuilder(
        bin.absolutePath,
        "--folder", folder.absolutePath,
        "--data-dir", dataDir.absolutePath,
        "--port", "0",
        "--bind", "127.0.0.1",
        "--no-mdns",
        "--no-qr",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    private val lines = LinkedBlockingQueue<String>()
    val port: Int
    val fingerprint: String

    init {
        Thread {
            process.inputStream.bufferedReader().forEachLine { lines.put(it) }
        }.apply { isDaemon = true }.start()
        port = expect("LISTENING ").substringAfterLast(':').toInt()
        fingerprint = expect("FINGERPRINT ")
    }

    fun send(command: String) {
        process.outputStream.write("$command\n".toByteArray())
        process.outputStream.flush()
    }

    fun expect(prefix: String, timeoutMs: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            check(left > 0) { "el servidor no respondió $prefix" }
            val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (line.startsWith("ERROR")) error("el servidor respondió: $line")
            if (line.startsWith(prefix)) return line.removePrefix(prefix)
        }
    }

    fun sync(): String {
        send("sync")
        return "SYNCED " + expect("SYNCED ")
    }

    fun pairUri(): String {
        send("pair")
        return expect("PAIR_URI ")
    }

    fun status() = run {
        send("status")
        CyberiaJson.parseToJsonElement(expect("STATUS ")).jsonObject
    }

    override fun close() {
        runCatching { send("quit") }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
