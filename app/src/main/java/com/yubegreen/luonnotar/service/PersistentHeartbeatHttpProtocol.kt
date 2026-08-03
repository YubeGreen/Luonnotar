package com.yubegreen.luonnotar.service

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class PersistentHeartbeatHttpResponse(
    val code: Int,
    val connectionClose: Boolean
)

object PersistentHeartbeatHttpProtocol {
    fun readResponse(input: InputStream): PersistentHeartbeatHttpResponse {
        val statusLine = readAsciiLine(input)
            ?: throw EOFException("eof_before_status")
        val statusParts = statusLine.split(' ', limit = 3)
        if (statusParts.size < 2) {
            throw IOException("invalid_http_status")
        }
        val code = statusParts[1].toIntOrNull()
            ?: throw IOException("invalid_http_code")

        var contentLength = 0L
        var chunked = false
        var connectionClose =
            statusParts[0].uppercase(Locale.US) == "HTTP/1.0"
        var headerCount = 0

        while (true) {
            val line = readAsciiLine(input)
                ?: throw EOFException("eof_in_headers")
            if (line.isEmpty()) break
            headerCount += 1
            if (headerCount > MAX_HEADER_COUNT) {
                throw IOException("too_many_headers")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator)
                .trim()
                .lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            when (name) {
                "content-length" -> {
                    contentLength = value.toLongOrNull()
                        ?: throw IOException("invalid_content_length")
                }
                "transfer-encoding" -> {
                    chunked = value.lowercase(Locale.US)
                        .contains("chunked")
                }
                "connection" -> {
                    connectionClose = value.lowercase(Locale.US)
                        .contains("close")
                }
            }
        }

        when {
            chunked -> drainChunkedBody(input)
            contentLength > 0L -> drainExact(input, contentLength)
        }

        if (code !in setOf(200, 204)) {
            throw IOException("http_$code")
        }
        return PersistentHeartbeatHttpResponse(
            code = code,
            connectionClose = connectionClose
        )
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (buffer.size() == 0) {
                    null
                } else {
                    buffer.toString(StandardCharsets.US_ASCII.name())
                }
            }
            when (value) {
                '\n'.code -> {
                    return buffer.toString(
                        StandardCharsets.US_ASCII.name()
                    )
                }
                '\r'.code -> Unit
                else -> {
                    if (buffer.size() >= MAX_HEADER_LINE_BYTES) {
                        throw IOException("header_line_too_long")
                    }
                    buffer.write(value)
                }
            }
        }
    }

    private fun drainExact(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(4_096)
        while (remaining > 0L) {
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt()
            )
            if (read < 0) throw EOFException("eof_in_body")
            remaining -= read.toLong()
        }
    }

    private fun drainChunkedBody(input: InputStream) {
        while (true) {
            val sizeLine = readAsciiLine(input)
                ?: throw EOFException("eof_before_chunk_size")
            val chunkSize = sizeLine.substringBefore(';')
                .trim()
                .toLongOrNull(16)
                ?: throw IOException("invalid_chunk_size")
            if (chunkSize == 0L) {
                while (true) {
                    val trailer = readAsciiLine(input)
                        ?: throw EOFException("eof_in_trailers")
                    if (trailer.isEmpty()) return
                }
            }
            drainExact(input, chunkSize)
            val ending = readAsciiLine(input)
                ?: throw EOFException("eof_after_chunk")
            if (ending.isNotEmpty()) {
                throw IOException("invalid_chunk_ending")
            }
        }
    }

    private const val MAX_HEADER_COUNT = 64
    private const val MAX_HEADER_LINE_BYTES = 8_192
}
