package app.cyberia.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HashingTest {
    @Test
    fun blake3IgualQueEnLaPc() {
        // Vector oficial de BLAKE3 para la entrada vacía.
        assertEquals(
            "b3:af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            Hashing.bytesId(ByteArray(0)),
        )
    }
}
