package app.cyberia.core

import java.io.File
import java.security.MessageDigest

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        chars[i * 2] = digits[v ushr 4]
        chars[i * 2 + 1] = digits[v and 0x0f]
    }
    return String(chars)
}

object Hashing {
    /** Huella de un archivo en el mismo formato que la PC: `b3:` + BLAKE3 en hexadecimal. */
    fun fileId(file: File): String {
        val hasher = Blake3()
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hasher.update(buffer, 0, n)
            }
        }
        return "b3:" + hasher.digest().toHex()
    }

    fun bytesId(bytes: ByteArray): String = "b3:" + Blake3.hash(bytes).toHex()

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }
}
