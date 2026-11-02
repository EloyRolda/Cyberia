package app.cyberia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingUriTest {
    private val fp = "a57db4da95913cfdf14bab1e029d714213fe5f5d37ce2170072cac404512d4eb"

    @Test
    fun leeElQrDeLaPc() {
        val info = PairingInfo.parse(
            "cyberia://pair?v=1&id=a24c00c8&name=PC%20de%20Eloy&hosts=192.168.0.10%2C10.0.0.4&port=47474&fp=$fp&code=dUSi-Eiq_coRo",
        )
        assertEquals("PC de Eloy", info.pcName)
        assertEquals(listOf("192.168.0.10", "10.0.0.4"), info.hosts)
        assertEquals(47474, info.port)
        assertEquals("dUSi-Eiq_coRo", info.code)
        assertEquals(Endpoint("192.168.0.10", 47474), info.endpoints.first())
    }

    @Test
    fun rechazaQrsAjenos() {
        assertFailsWith<IllegalArgumentException> { PairingInfo.parse("https://example.com") }
        assertFailsWith<IllegalArgumentException> { PairingInfo.parse("cyberia://pair?v=1&id=x&port=1&code=c") }
        assertFailsWith<IllegalArgumentException> { PairingInfo.parse("cyberia://pair?v=2&id=x&port=1&fp=$fp&code=c") }
    }

    @Test
    fun endpointIpv6() {
        assertEquals("https://[fe80::1]:47474", Endpoint("fe80::1", 47474).baseUrl)
    }
}
