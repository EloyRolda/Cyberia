package app.cyberia.core

/**
 * BLAKE3 (hash de 32 bytes, sin clave), portado de la implementación de referencia.
 *
 * Está escrito a mano para no depender de commons-codec: en Android 8.x el sistema trae una copia
 * vieja de esa librería en el classpath de arranque, que tiene prioridad sobre la de la app.
 */
class Blake3 {
    private var key = IV.copyOf()
    private var chunk = ChunkState(key, 0)
    private val cvStack = Array(54) { IntArray(8) }
    private var cvStackLen = 0

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): Blake3 {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            if (chunk.len() == CHUNK_LEN) {
                val chunkCv = chunk.output().chainingValue()
                val totalChunks = chunk.chunkCounter + 1
                addChunkChainingValue(chunkCv, totalChunks)
                chunk = ChunkState(key, totalChunks)
            }
            val take = minOf(CHUNK_LEN - chunk.len(), end - pos)
            chunk.update(input, pos, take)
            pos += take
        }
        return this
    }

    fun digest(): ByteArray {
        var output = chunk.output()
        var remaining = cvStackLen
        while (remaining > 0) {
            remaining--
            output = parentOutput(cvStack[remaining], output.chainingValue(), key)
        }
        return output.rootBytes(OUT_LEN)
    }

    private fun addChunkChainingValue(newCvIn: IntArray, totalChunksIn: Long) {
        var newCv = newCvIn
        var totalChunks = totalChunksIn
        while (totalChunks and 1L == 0L) {
            cvStackLen--
            newCv = parentOutput(cvStack[cvStackLen], newCv, key).chainingValue()
            totalChunks = totalChunks shr 1
        }
        newCv.copyInto(cvStack[cvStackLen])
        cvStackLen++
    }

    private class Output(
        val inputCv: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int,
    ) {
        fun chainingValue(): IntArray {
            val state = IntArray(16)
            compress(inputCv, blockWords, counter, blockLen, flags, state)
            return state.copyOf(8)
        }

        fun rootBytes(length: Int): ByteArray {
            val out = ByteArray(length)
            val state = IntArray(16)
            var outputBlockCounter = 0L
            var pos = 0
            while (pos < length) {
                compress(inputCv, blockWords, outputBlockCounter, blockLen, flags or ROOT, state)
                for (word in state) {
                    for (b in 0 until 4) {
                        if (pos >= length) break
                        out[pos++] = (word ushr (8 * b)).toByte()
                    }
                }
                outputBlockCounter++
            }
            return out
        }
    }

    private class ChunkState(keyWords: IntArray, val chunkCounter: Long) {
        private val cv = keyWords.copyOf()
        private val block = ByteArray(BLOCK_LEN)
        private val words = IntArray(16)
        private val state = IntArray(16)
        private var blockLen = 0
        private var blocksCompressed = 0

        fun len(): Int = BLOCK_LEN * blocksCompressed + blockLen

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(input: ByteArray, offset: Int, length: Int) {
            var pos = offset
            val end = offset + length
            while (pos < end) {
                if (blockLen == BLOCK_LEN) {
                    wordsFromLeBytes(block, words)
                    compress(cv, words, chunkCounter, BLOCK_LEN, startFlag(), state)
                    state.copyInto(cv, 0, 0, 8)
                    blocksCompressed++
                    block.fill(0)
                    blockLen = 0
                }
                val take = minOf(BLOCK_LEN - blockLen, end - pos)
                System.arraycopy(input, pos, block, blockLen, take)
                blockLen += take
                pos += take
            }
        }

        fun output(): Output {
            val blockWords = IntArray(16)
            wordsFromLeBytes(block, blockWords)
            return Output(cv.copyOf(), blockWords, chunkCounter, blockLen, startFlag() or CHUNK_END)
        }
    }

    companion object {
        private const val OUT_LEN = 32
        private const val BLOCK_LEN = 64
        private const val CHUNK_LEN = 1024
        private const val CHUNK_START = 1
        private const val CHUNK_END = 2
        private const val PARENT = 4
        private const val ROOT = 8

        private val IV = intArrayOf(
            0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )

        private val PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

        /** Orden de las palabras del mensaje en cada una de las 7 rondas. */
        private val SCHEDULE: Array<IntArray> = run {
            val rounds = Array(7) { IntArray(16) }
            var current = IntArray(16) { it }
            for (r in 0 until 7) {
                rounds[r] = current
                current = IntArray(16) { i -> current[PERMUTATION[i]] }
            }
            rounds
        }

        fun hash(input: ByteArray): ByteArray = Blake3().update(input).digest()

        private fun parentOutput(left: IntArray, right: IntArray, key: IntArray): Output {
            val blockWords = IntArray(16)
            left.copyInto(blockWords, 0, 0, 8)
            right.copyInto(blockWords, 8, 0, 8)
            return Output(key, blockWords, 0, BLOCK_LEN, PARENT)
        }

        private fun wordsFromLeBytes(bytes: ByteArray, words: IntArray) {
            for (i in 0 until 16) {
                val j = i * 4
                words[i] = (bytes[j].toInt() and 0xff) or
                    ((bytes[j + 1].toInt() and 0xff) shl 8) or
                    ((bytes[j + 2].toInt() and 0xff) shl 16) or
                    ((bytes[j + 3].toInt() and 0xff) shl 24)
            }
        }

        private fun compress(cv: IntArray, m: IntArray, counter: Long, blockLen: Int, flags: Int, out: IntArray) {
            var s0 = cv[0]; var s1 = cv[1]; var s2 = cv[2]; var s3 = cv[3]
            var s4 = cv[4]; var s5 = cv[5]; var s6 = cv[6]; var s7 = cv[7]
            var s8 = IV[0]; var s9 = IV[1]; var s10 = IV[2]; var s11 = IV[3]
            var s12 = counter.toInt(); var s13 = (counter ushr 32).toInt(); var s14 = blockLen; var s15 = flags

            for (r in 0 until 7) {
                val p = SCHEDULE[r]
                // Columnas.
                s0 += s4 + m[p[0]]; s12 = (s12 xor s0).rotateRight(16); s8 += s12; s4 = (s4 xor s8).rotateRight(12)
                s0 += s4 + m[p[1]]; s12 = (s12 xor s0).rotateRight(8); s8 += s12; s4 = (s4 xor s8).rotateRight(7)
                s1 += s5 + m[p[2]]; s13 = (s13 xor s1).rotateRight(16); s9 += s13; s5 = (s5 xor s9).rotateRight(12)
                s1 += s5 + m[p[3]]; s13 = (s13 xor s1).rotateRight(8); s9 += s13; s5 = (s5 xor s9).rotateRight(7)
                s2 += s6 + m[p[4]]; s14 = (s14 xor s2).rotateRight(16); s10 += s14; s6 = (s6 xor s10).rotateRight(12)
                s2 += s6 + m[p[5]]; s14 = (s14 xor s2).rotateRight(8); s10 += s14; s6 = (s6 xor s10).rotateRight(7)
                s3 += s7 + m[p[6]]; s15 = (s15 xor s3).rotateRight(16); s11 += s15; s7 = (s7 xor s11).rotateRight(12)
                s3 += s7 + m[p[7]]; s15 = (s15 xor s3).rotateRight(8); s11 += s15; s7 = (s7 xor s11).rotateRight(7)
                // Diagonales.
                s0 += s5 + m[p[8]]; s15 = (s15 xor s0).rotateRight(16); s10 += s15; s5 = (s5 xor s10).rotateRight(12)
                s0 += s5 + m[p[9]]; s15 = (s15 xor s0).rotateRight(8); s10 += s15; s5 = (s5 xor s10).rotateRight(7)
                s1 += s6 + m[p[10]]; s12 = (s12 xor s1).rotateRight(16); s11 += s12; s6 = (s6 xor s11).rotateRight(12)
                s1 += s6 + m[p[11]]; s12 = (s12 xor s1).rotateRight(8); s11 += s12; s6 = (s6 xor s11).rotateRight(7)
                s2 += s7 + m[p[12]]; s13 = (s13 xor s2).rotateRight(16); s8 += s13; s7 = (s7 xor s8).rotateRight(12)
                s2 += s7 + m[p[13]]; s13 = (s13 xor s2).rotateRight(8); s8 += s13; s7 = (s7 xor s8).rotateRight(7)
                s3 += s4 + m[p[14]]; s14 = (s14 xor s3).rotateRight(16); s9 += s14; s4 = (s4 xor s9).rotateRight(12)
                s3 += s4 + m[p[15]]; s14 = (s14 xor s3).rotateRight(8); s9 += s14; s4 = (s4 xor s9).rotateRight(7)
            }

            out[0] = s0 xor s8; out[1] = s1 xor s9; out[2] = s2 xor s10; out[3] = s3 xor s11
            out[4] = s4 xor s12; out[5] = s5 xor s13; out[6] = s6 xor s14; out[7] = s7 xor s15
            out[8] = s8 xor cv[0]; out[9] = s9 xor cv[1]; out[10] = s10 xor cv[2]; out[11] = s11 xor cv[3]
            out[12] = s12 xor cv[4]; out[13] = s13 xor cv[5]; out[14] = s14 xor cv[6]; out[15] = s15 xor cv[7]
        }
    }
}
