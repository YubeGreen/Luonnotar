package com.yubegreen.luonnotar.privileged.embedded

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

class EmbeddedGuardianClient(
    private val port: Int,
    private val token: String,
    private val connectTimeoutMs: Int = 2_500,
    private val readTimeoutMs: Int = 60_000
) {
    fun ping(): String = call(EmbeddedGuardianProtocol.OP_PING)
    fun configure(configJson: String): String = call(EmbeddedGuardianProtocol.OP_CONFIGURE, configJson)
    fun status(): String = call(EmbeddedGuardianProtocol.OP_STATUS)
    fun cycle(): String = call(EmbeddedGuardianProtocol.OP_CYCLE)
    fun recoverGms(): String = call(EmbeddedGuardianProtocol.OP_RECOVER_GMS)
    fun applyBackgroundPolicy(requestJson: String): String =
        call(EmbeddedGuardianProtocol.OP_BACKGROUND_POLICY, requestJson)
    fun stop(): String = call(EmbeddedGuardianProtocol.OP_STOP)
    fun destroy(): String = call(EmbeddedGuardianProtocol.OP_DESTROY)

    private fun call(operation: String, payload: String = ""): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(EmbeddedGuardianProtocol.HOST, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().bufferedWriter().use { writer ->
                writer.write(EmbeddedGuardianProtocol.request(token, operation, payload))
                writer.newLine()
                writer.flush()
                val line = EmbeddedGuardianProtocol.readLimitedLine(socket.getInputStream().bufferedReader())
                    ?: error("empty response")
                val response = JSONObject(line)
                if (response.optInt("schema", -1) != EmbeddedGuardianProtocol.SCHEMA) {
                    error("embedded protocol schema mismatch")
                }
                if (!response.optBoolean("ok", false)) {
                    error(response.optString("error", "embedded engine error"))
                }
                return response.optString("result")
            }
        }
    }
}
