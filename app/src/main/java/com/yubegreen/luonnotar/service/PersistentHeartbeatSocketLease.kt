package com.yubegreen.luonnotar.service

import android.content.Context
import android.net.Network
import android.os.Build
import android.os.SystemClock
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * App-owned persistent TLS/HTTP heartbeat experiment.
 *
 * This deliberately does not connect to mtalk and does not speak MCS. It keeps
 * an ordinary HTTPS connection active with explicit application-level
 * heartbeats so the experiment can distinguish a real long-lived channel from
 * the old raw-mtalk connect/EOF/retry loop.
 */
class PersistentHeartbeatSocketLease(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-persistent-heartbeat").apply {
            isDaemon = true
        }
    }
    private val enabled = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    @Volatile private var network: Network? = null
    @Volatile private var networkHandle = -1L
    @Volatile private var socket: Socket? = null

    fun reconcile(enabledNow: Boolean, currentNetwork: Network?) {
        if (!enabledNow) {
            stop("disabled")
            return
        }
        if (currentNetwork == null) {
            stop("network_unavailable", state = "WAITING_NETWORK")
            return
        }

        val handle = currentNetwork.networkHandle
        if (enabled.get() && networkHandle == handle) return

        stop("network_or_config_changed")
        enabled.set(true)
        network = currentNetwork
        networkHandle = handle
        val owner = generation.incrementAndGet()
        resetSessionDiagnostics(handle)
        executor.execute { runLease(owner, currentNetwork, handle) }
    }

    fun stop(reason: String, state: String = "STOPPED") {
        if (
            !enabled.get() &&
            socket == null &&
            network == null &&
            networkHandle == -1L
        ) {
            persist(
                state = state,
                handle = -1L,
                reason = reason,
                connectionStartedElapsed = 0L,
                backoffMs = 0L
            )
            return
        }

        enabled.set(false)
        generation.incrementAndGet()
        runCatching { socket?.close() }
        socket = null
        network = null
        networkHandle = -1L
        persist(
            state = state,
            handle = -1L,
            reason = reason,
            connectionStartedElapsed = 0L,
            backoffMs = 0L
        )
    }

    fun shutdown() {
        stop("service_destroyed")
        executor.shutdownNow()
    }

    private fun runLease(
        owner: Long,
        leasedNetwork: Network,
        handle: Long
    ) {
        val reconnectAttempts = ArrayDeque<Long>()
        var consecutiveFailures = 0
        var sessionConnectCount = 0
        var sessionHeartbeatCount = 0

        while (isOwnerCurrent(owner)) {
            val now = SystemClock.elapsedRealtime()
            while (
                reconnectAttempts.isNotEmpty() &&
                now - reconnectAttempts.first() >
                    PersistentHeartbeatBackoffPolicy
                        .RECONNECT_STORM_WINDOW_MS
            ) {
                reconnectAttempts.removeFirst()
            }

            if (
                PersistentHeartbeatBackoffPolicy.isReconnectStorm(
                    reconnectAttempts,
                    now
                )
            ) {
                persist(
                    state = "COOLDOWN",
                    handle = handle,
                    reason = "reconnect_storm",
                    sessionConnectCount = sessionConnectCount,
                    sessionHeartbeatCount = sessionHeartbeatCount,
                    connectionStartedElapsed = 0L,
                    consecutiveFailures = consecutiveFailures,
                    backoffMs = PersistentHeartbeatBackoffPolicy
                        .RECONNECT_STORM_COOLDOWN_MS
                )
                LogManager.event(
                    appContext,
                    "persistent_heartbeat_socket_cooldown",
                    mapOf(
                        "networkHandle" to handle,
                        "attemptsInWindow" to reconnectAttempts.size,
                        "cooldownMs" to PersistentHeartbeatBackoffPolicy
                            .RECONNECT_STORM_COOLDOWN_MS
                    )
                )
                if (
                    !sleepWhileCurrent(
                        owner,
                        PersistentHeartbeatBackoffPolicy
                            .RECONNECT_STORM_COOLDOWN_MS
                    )
                ) return
                reconnectAttempts.clear()
                continue
            }

            reconnectAttempts.addLast(now)
            var candidate: SSLSocket? = null
            var connectedElapsed = 0L

            try {
                persist(
                    state = "CONNECTING",
                    handle = handle,
                    reason = "",
                    sessionConnectCount = sessionConnectCount,
                    sessionHeartbeatCount = sessionHeartbeatCount,
                    consecutiveFailures = consecutiveFailures,
                    backoffMs = 0L
                )
                candidate = createTlsSocket(leasedNetwork)
                if (!isOwnerCurrent(owner)) {
                    candidate.close()
                    return
                }

                socket = candidate
                connectedElapsed = SystemClock.elapsedRealtime()
                sessionConnectCount += 1
                incrementTotalConnectCount()

                persist(
                    state = "CONNECTED",
                    handle = handle,
                    reason = "tls_ready",
                    sessionConnectCount = sessionConnectCount,
                    sessionHeartbeatCount = sessionHeartbeatCount,
                    connectionStartedElapsed = connectedElapsed,
                    consecutiveFailures = consecutiveFailures,
                    backoffMs = 0L
                )
                LogManager.timeline(
                    appContext,
                    "persistent_heartbeat_socket_connected",
                    mapOf(
                        "networkHandle" to handle,
                        "host" to HEARTBEAT_HOST,
                        "sessionConnectCount" to sessionConnectCount
                    )
                )

                while (isOwnerCurrent(owner) && !candidate.isClosed) {
                    val code = performHeartbeat(candidate)
                    val heartbeatElapsed = SystemClock.elapsedRealtime()
                    sessionHeartbeatCount += 1
                    incrementTotalHeartbeatCount()
                    val connectionAge =
                        (heartbeatElapsed - connectedElapsed)
                            .coerceAtLeast(0L)

                    if (
                        connectionAge >=
                        PersistentHeartbeatBackoffPolicy
                            .STABLE_CONNECTION_MS
                    ) {
                        consecutiveFailures = 0
                    }

                    persist(
                        state = "CONNECTED",
                        handle = handle,
                        reason = "http_$code",
                        sessionConnectCount = sessionConnectCount,
                        sessionHeartbeatCount = sessionHeartbeatCount,
                        connectionStartedElapsed = connectedElapsed,
                        lastHeartbeatElapsed = heartbeatElapsed,
                        consecutiveFailures = consecutiveFailures,
                        backoffMs = 0L
                    )
                    LogManager.timeline(
                        appContext,
                        "persistent_heartbeat_socket_heartbeat",
                        mapOf(
                            "networkHandle" to handle,
                            "httpCode" to code,
                            "connectionAgeMs" to connectionAge,
                            "sessionHeartbeatCount" to
                                sessionHeartbeatCount
                        )
                    )

                    if (
                        !sleepWhileCurrent(
                            owner,
                            HEARTBEAT_INTERVAL_MS
                        )
                    ) return
                }
            } catch (error: Throwable) {
                if (!isOwnerCurrent(owner)) return

                val nowElapsed = SystemClock.elapsedRealtime()
                val connectionAge =
                    if (connectedElapsed > 0L) {
                        (nowElapsed - connectedElapsed)
                            .coerceAtLeast(0L)
                    } else {
                        0L
                    }
                consecutiveFailures =
                    if (
                        connectionAge >=
                        PersistentHeartbeatBackoffPolicy
                            .STABLE_CONNECTION_MS
                    ) {
                        1
                    } else {
                        consecutiveFailures + 1
                    }

                val reason = failureReason(error)
                val retryDelay =
                    PersistentHeartbeatBackoffPolicy.retryDelayMs(
                        consecutiveFailures
                    )
                persist(
                    state = "RETRYING",
                    handle = handle,
                    reason = reason,
                    sessionConnectCount = sessionConnectCount,
                    sessionHeartbeatCount = sessionHeartbeatCount,
                    connectionStartedElapsed = 0L,
                    consecutiveFailures = consecutiveFailures,
                    backoffMs = retryDelay
                )
                LogManager.event(
                    appContext,
                    "persistent_heartbeat_socket_failed",
                    mapOf(
                        "networkHandle" to handle,
                        "reason" to reason,
                        "connectionAgeMs" to connectionAge,
                        "consecutiveFailures" to consecutiveFailures,
                        "retryDelayMs" to retryDelay
                    )
                )
                runCatching { candidate?.close() }
                if (socket === candidate) socket = null
                if (!sleepWhileCurrent(owner, retryDelay)) return
            } finally {
                runCatching { candidate?.close() }
                if (socket === candidate) socket = null
            }
        }
    }

    private fun createTlsSocket(network: Network): SSLSocket {
        val raw = network.socketFactory.createSocket()
        raw.keepAlive = true
        raw.tcpNoDelay = true
        raw.connect(
            InetSocketAddress(HEARTBEAT_HOST, HEARTBEAT_PORT),
            CONNECT_TIMEOUT_MS.toInt()
        )
        raw.soTimeout = IO_TIMEOUT_MS.toInt()

        val tls = (
            SSLSocketFactory.getDefault() as SSLSocketFactory
        ).createSocket(
            raw,
            HEARTBEAT_HOST,
            HEARTBEAT_PORT,
            true
        ) as SSLSocket
        tls.keepAlive = true
        tls.tcpNoDelay = true
        tls.soTimeout = IO_TIMEOUT_MS.toInt()
        tls.sslParameters = tls.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                applicationProtocols = arrayOf("http/1.1")
            }
        }
        tls.startHandshake()
        return tls
    }

    private fun performHeartbeat(socket: SSLSocket): Int {
        val output = BufferedOutputStream(socket.outputStream)
        val input = BufferedInputStream(socket.inputStream)
        output.write(HEARTBEAT_REQUEST)
        output.flush()

        val response =
            PersistentHeartbeatHttpProtocol.readResponse(input)
        if (response.connectionClose) {
            throw java.io.IOException("server_requested_close")
        }
        return response.code
    }

    private fun isOwnerCurrent(owner: Long): Boolean =
        enabled.get() && generation.get() == owner

    private fun sleepWhileCurrent(
        owner: Long,
        durationMs: Long
    ): Boolean {
        val deadline =
            SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L)
        while (isOwnerCurrent(owner)) {
            val remaining =
                deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return true
            try {
                Thread.sleep(minOf(remaining, SLEEP_POLL_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun resetSessionDiagnostics(handle: Long) {
        LuonnotarPreferences.deviceProtected(appContext).edit()
            .putString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_STATE,
                "STARTING"
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_HANDLE,
                handle
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_EVENT_ELAPSED,
                SystemClock.elapsedRealtime()
            )
            .putString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_REASON,
                ""
            )
            .putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_CONNECT_COUNT,
                0
            )
            .putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_HEARTBEAT_COUNT,
                0
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONNECTION_STARTED_ELAPSED,
                0L
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_HEARTBEAT_ELAPSED,
                0L
            )
            .putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONSECUTIVE_FAILURES,
                0
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_BACKOFF_MS,
                0L
            )
            .apply()
    }

    private fun incrementTotalConnectCount() {
        val prefs = LuonnotarPreferences.deviceProtected(appContext)
        prefs.edit()
            .putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_CONNECT_COUNT,
                prefs.getInt(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_CONNECT_COUNT,
                    0
                ) + 1
            )
            .apply()
    }

    private fun incrementTotalHeartbeatCount() {
        val prefs = LuonnotarPreferences.deviceProtected(appContext)
        prefs.edit()
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_HEARTBEAT_COUNT,
                prefs.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_HEARTBEAT_COUNT,
                    0L
                ) + 1L
            )
            .apply()
    }

    private fun persist(
        state: String,
        handle: Long,
        reason: String,
        sessionConnectCount: Int? = null,
        sessionHeartbeatCount: Int? = null,
        connectionStartedElapsed: Long? = null,
        lastHeartbeatElapsed: Long? = null,
        consecutiveFailures: Int? = null,
        backoffMs: Long? = null
    ) {
        val editor = LuonnotarPreferences.deviceProtected(appContext)
            .edit()
            .putString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_STATE,
                state
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_HANDLE,
                handle
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_EVENT_ELAPSED,
                SystemClock.elapsedRealtime()
            )
            .putString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_REASON,
                reason
            )

        sessionConnectCount?.let {
            editor.putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_CONNECT_COUNT,
                it
            )
        }
        sessionHeartbeatCount?.let {
            editor.putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_HEARTBEAT_COUNT,
                it
            )
        }
        connectionStartedElapsed?.let {
            editor.putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONNECTION_STARTED_ELAPSED,
                it
            )
        }
        lastHeartbeatElapsed?.let {
            editor.putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_HEARTBEAT_ELAPSED,
                it
            )
        }
        consecutiveFailures?.let {
            editor.putInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONSECUTIVE_FAILURES,
                it
            )
        }
        backoffMs?.let {
            editor.putLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_BACKOFF_MS,
                it
            )
        }
        editor.apply()
    }

    private fun failureReason(error: Throwable): String {
        val message = error.message
            ?.replace(';', '_')
            ?.replace('=', '_')
            ?.take(80)
            .orEmpty()
        val type = error.javaClass.simpleName.ifBlank {
            error.javaClass.name.substringAfterLast('.')
        }
        return if (message.isBlank()) type else "$type:$message"
    }

    companion object {
        private const val HEARTBEAT_HOST =
            "connectivitycheck.gstatic.com"
        private const val HEARTBEAT_PORT = 443
        private const val HEARTBEAT_PATH = "/generate_204"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val IO_TIMEOUT_MS = 15_000L
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private const val SLEEP_POLL_MS = 1_000L

        private val HEARTBEAT_REQUEST = (
            "GET $HEARTBEAT_PATH HTTP/1.1\r\n" +
                "Host: $HEARTBEAT_HOST\r\n" +
                "User-Agent: Luonnotar/1.7.17\r\n" +
                "Accept: */*\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
    }
}
