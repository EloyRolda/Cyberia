package app.cyberia.core

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class HashingTest {
    @Test
    fun vectorOficialEntradaVacia() {
        assertEquals(
            "b3:af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            Hashing.bytesId(ByteArray(0)),
        )
    }

    /** Los largos de los vectores oficiales de BLAKE3, con la entrada `i % 251`. */
    @Test
    fun igualQueCommonsCodec() {
        val lengths = listOf(
            0, 1, 63, 64, 65, 127, 128, 129, 1023, 1024, 1025, 2048, 2049, 3072, 3073, 4096, 4097,
            5120, 5121, 6144, 6145, 7168, 7169, 8192, 8193, 16384, 31744, 102400, 1_000_003,
        )
        for (len in lengths) {
            val input = ByteArray(len) { (it % 251).toByte() }
            val expected = org.apache.commons.codec.digest.Blake3.hash(input).toHex()
            assertEquals(expected, Blake3.hash(input).toHex(), "largo $len")
        }
    }

    @Test
    fun actualizacionesEnPartesDanLoMismo() {
        val input = Random(7).nextBytes(300_000)
        val hasher = Blake3()
        var pos = 0
        val rnd = Random(1)
        while (pos < input.size) {
            val n = minOf(rnd.nextInt(1, 5000), input.size - pos)
            hasher.update(input, pos, n)
            pos += n
        }
        assertEquals(Blake3.hash(input).toHex(), hasher.digest().toHex())

        val file: File = Files.createTempFile("cyberia", ".bin").toFile()
        file.writeBytes(input)
        assertEquals("b3:" + Blake3.hash(input).toHex(), Hashing.fileId(file))
        file.delete()
    }
}
