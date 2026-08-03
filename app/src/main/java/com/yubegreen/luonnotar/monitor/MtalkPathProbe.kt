package com.yubegreen.luonnotar.monitor

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

data class MtalkTcpResult(
    val host: String,
    val family: String,
    val address: String,
    val port: Int,
    val attempted: Boolean,
    val succeeded: Boolean,
    val rttMs: Long,
    val error: String = ""
)

data class MtalkPathProbeResult(
    val supported: Boolean,
    val ipv4Dns: Boolean,
    val ipv6Dns: Boolean,
    val tcpResults: List<MtalkTcpResult>,
    val elapsedMs: Long,
    val error: String = ""
) {
    fun portSucceeded(family: String, port: Int): Boolean =
        tcpResults.any {
            it.family == family && it.port == port && it.succeeded
        }
}

object MtalkPathProbe {
    val ports = listOf(5228, 443)
    private val hosts = listOf("mtalk.google.com", "mtalk4.google.com")
    private const val DNS_TIMEOUT_MS = 1_500L
    private const val TCP_TIMEOUT_MS = 1_500
    private const val TOTAL_DEADLINE_MS = 8_000L

    fun probe(
        network: Network,
        onCancellationChanged: (CancellationSignal?) -> Unit = {},
        onSocketChanged: (Socket?) -> Unit = {}
    ): MtalkPathProbeResult {
        if (Build.VERSION.SDK_INT < 29) {
            return MtalkPathProbeResult(
                supported = false,
                ipv4Dns = false,
                ipv6Dns = false,
                tcpResults = emptyList(),
                elapsedMs = -1L,
                error = "DNS_RESOLVER_REQUIRES_API_29"
            )
        }
        val started = SystemClock.elapsedRealtime()
        val addresses = LinkedHashMap<String, List<InetAddress>>()
        for (host in hosts) {
            addresses[host] = query(network, host, onCancellationChanged)
            if (SystemClock.elapsedRealtime() - started >= TOTAL_DEADLINE_MS) {
                break
            }
        }
        val addressCandidates = addresses.flatMap { (host, values) ->
            values.map { host to it }
        }
        val selected = listOfNotNull(
            addressCandidates.firstOrNull { it.second is Inet4Address }
                ?.let { Triple(it.first, "IPV4", it.second) },
            addressCandidates.firstOrNull { it.second is Inet6Address }
                ?.let { Triple(it.first, "IPV6", it.second) }
        )
        val results = ArrayList<MtalkTcpResult>()
        for ((host, family, address) in selected) {
            for (port in ports) {
                if (
                    SystemClock.elapsedRealtime() - started >=
                    TOTAL_DEADLINE_MS
                ) {
                    results += MtalkTcpResult(
                        host = host,
                        family = family,
                        address = address.hostAddress.orEmpty(),
                        port = port,
                        attempted = false,
                        succeeded = false,
                        rttMs = -1L,
                        error = "TOTAL_DEADLINE_REACHED"
                    )
                    continue
                }
                results += connect(
                    network,
                    host,
                    family,
                    address,
                    port,
                    onSocketChanged
                )
            }
        }
        val allAddresses = addresses.values.flatten()
        return MtalkPathProbeResult(
            supported = true,
            ipv4Dns = allAddresses.any { it is Inet4Address },
            ipv6Dns = allAddresses.any { it is Inet6Address },
            tcpResults = results,
            elapsedMs = SystemClock.elapsedRealtime() - started
        )
    }

    @androidx.annotation.RequiresApi(29)
    private fun query(
        network: Network,
        host: String,
        onCancellationChanged: (CancellationSignal?) -> Unit
    ): List<InetAddress> {
        val cancellation = CancellationSignal()
        val latch = CountDownLatch(1)
        var answer = emptyList<InetAddress>()
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(
                resolved: List<InetAddress>,
                rcode: Int
            ) {
                if (rcode == 0) answer = resolved
                latch.countDown()
            }

            override fun onError(error: DnsResolver.DnsException) {
                latch.countDown()
            }
        }
        return try {
            onCancellationChanged(cancellation)
            DnsResolver.getInstance().query(
                network,
                host,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                Executor { command -> command.run() },
                cancellation,
                callback
            )
            if (!latch.await(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancellation.cancel()
            }
            answer
        } catch (_: Exception) {
            emptyList()
        } finally {
            onCancellationChanged(null)
        }
    }

    private fun connect(
        network: Network,
        host: String,
        family: String,
        address: InetAddress,
        port: Int,
        onSocketChanged: (Socket?) -> Unit
    ): MtalkTcpResult {
        val started = SystemClock.elapsedRealtime()
        var socket: Socket? = null
        return try {
            socket = Socket()
            network.bindSocket(socket)
            onSocketChanged(socket)
            socket.connect(
                InetSocketAddress(address, port),
                TCP_TIMEOUT_MS
            )
            MtalkTcpResult(
                host = host,
                family = family,
                address = address.hostAddress.orEmpty(),
                port = port,
                attempted = true,
                succeeded = true,
                rttMs = SystemClock.elapsedRealtime() - started
            )
        } catch (failure: Exception) {
            MtalkTcpResult(
                host = host,
                family = family,
                address = address.hostAddress.orEmpty(),
                port = port,
                attempted = true,
                succeeded = false,
                rttMs = SystemClock.elapsedRealtime() - started,
                error = failure.javaClass.simpleName
            )
        } finally {
            onSocketChanged(null)
            runCatching { socket?.close() }
        }
    }
}
