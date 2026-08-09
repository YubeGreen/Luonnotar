package com.yubegreen.luonnotar.privileged.embedded

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** Mutable listener/token state used by transactional handoff. */
internal class EmbeddedGuardianServerControl(
    initialPort: Int,
    initialToken: String
) {
    private val listener = AtomicReference<ServerSocket?>(bind(initialPort))
    private val token = AtomicReference(initialToken)
    @Volatile var port: Int = initialPort
        private set

    fun token(): String = token.get()

    fun currentListener(): ServerSocket? = listener.get()

    @Synchronized
    fun closeListener() {
        listener.getAndSet(null)?.let { runCatching { it.close() } }
    }

    @Synchronized
    fun rebind(newPort: Int, newToken: String = token.get()) {
        require(newPort in 1024..65535) { "invalid listener port" }
        val replacement = bind(newPort)
        val old = listener.getAndSet(replacement)
        port = newPort
        token.set(newToken)
        runCatching { old?.close() }
    }

    fun close() = closeListener()

    private fun bind(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(EmbeddedGuardianProtocol.HOST, port), 16)
    }
}
