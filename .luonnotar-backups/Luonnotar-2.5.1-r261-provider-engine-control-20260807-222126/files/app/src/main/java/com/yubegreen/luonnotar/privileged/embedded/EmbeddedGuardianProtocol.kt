package com.yubegreen.luonnotar.privileged.embedded

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

internal object EmbeddedGuardianProtocol {
    const val SCHEMA = 1
    const val ENGINE_REVISION = 260
    const val MIN_HANDOFF_ENGINE_REVISION = 260
    const val HOST = "127.0.0.1"
    const val MAX_LINE_CHARS = 512 * 1024

    const val OP_PING = "ping"
    const val OP_CONFIGURE = "configure"
    const val OP_STATUS = "status"
    const val OP_CYCLE = "cycle"
    const val OP_RECOVER_GMS = "recover_gms"
    const val OP_BACKGROUND_POLICY = "background_policy"
    const val OP_HANDOFF = "handoff"
    const val OP_STOP = "stop"
    const val OP_DESTROY = "destroy"

    fun request(token: String, operation: String, payload: String = ""): String =
        JSONObject()
            .put("schema", SCHEMA)
            .put("token", token)
            .put("operation", operation)
            .put("payload", payload)
            .toString()

    fun success(result: String): String = JSONObject()
        .put("schema", SCHEMA)
        .put("ok", true)
        .put("result", result)
        .toString()

    fun failure(error: Throwable): String = failure(
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    )

    fun failure(error: String): String = JSONObject()
        .put("schema", SCHEMA)
        .put("ok", false)
        .put("error", error.take(2_000))
        .toString()

    fun readLimitedLine(reader: BufferedReader): String? {
        val out = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return out.takeIf(StringBuilder::isNotEmpty)?.toString()
            if (value == '\n'.code) return out.toString()
            if (value != '\r'.code) out.append(value.toChar())
            if (out.length > MAX_LINE_CHARS) throw IOException("request exceeds limit")
        }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
