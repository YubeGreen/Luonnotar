package com.yubegreen.luonnotar.service

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentHeartbeatHttpProtocolTest {
    @Test
    fun `reads reusable 204 response`() {
        val response = parse(
            "HTTP/1.1 204 No Content\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: keep-alive\r\n\r\n"
        )

        assertEquals(204, response.code)
        assertFalse(response.connectionClose)
    }

    @Test
    fun `drains fixed length body`() {
        val response = parse(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 4\r\n\r\n" +
                "test"
        )

        assertEquals(200, response.code)
        assertFalse(response.connectionClose)
    }

    @Test
    fun `drains chunked body and detects close`() {
        val response = parse(
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n" +
                "4\r\ntest\r\n0\r\n\r\n"
        )

        assertEquals(200, response.code)
        assertTrue(response.connectionClose)
    }

    @Test(expected = IOException::class)
    fun `rejects non success status`() {
        parse(
            "HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Length: 0\r\n\r\n"
        )
    }

    private fun parse(text: String): PersistentHeartbeatHttpResponse =
        PersistentHeartbeatHttpProtocol.readResponse(
            ByteArrayInputStream(
                text.toByteArray(StandardCharsets.US_ASCII)
            )
        )
}
