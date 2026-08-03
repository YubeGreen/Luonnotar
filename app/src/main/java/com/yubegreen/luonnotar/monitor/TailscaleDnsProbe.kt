package com.yubegreen.luonnotar.monitor

import android.net.Network
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

data class TailscaleDnsProbeResult(
    val attempted: Boolean,
    val succeeded: Boolean,
    val rttMs: Long,
    val server: String,
    val error: String = "",
    val ipv4Succeeded: Boolean? = null,
    val ipv6Succeeded: Boolean? = null
)

object TailscaleDnsProbe {
    private const val DNS_PORT = 53
    private const val TIMEOUT_MS = 1_500
    private val quad100V4 = byteArrayOf(100, 100, 100, 100)
    private val quad100V6 =
        InetAddress.getByName("fd7a:115c:a1e0::53").address

    fun probe(
        network: Network,
        dnsServers: List<InetAddress>,
        onSocketChanged: (DatagramSocket?) -> Unit = {}
    ): TailscaleDnsProbeResult {
        val servers = dnsServers.filter(::isTailscaleDns).distinctBy {
            it.hostAddress
        }
        if (servers.isEmpty()) {
            return TailscaleDnsProbeResult(
                attempted = false,
                succeeded = false,
                rttMs = -1L,
                server = "",
                error = "TAILSCALE_DNS_NOT_ADVERTISED"
            )
        }
        val started = SystemClock.elapsedRealtime()
        val results = servers.map { server ->
            probeOne(network, server, onSocketChanged)
        }
        val succeeded = results.any { it.second }
        val v4 = results.filter { it.first is Inet4Address }
            .takeIf { it.isNotEmpty() }
            ?.any { it.second }
        val v6 = results.filter { it.first is Inet6Address }
            .takeIf { it.isNotEmpty() }
            ?.any { it.second }
        return TailscaleDnsProbeResult(
            attempted = true,
            succeeded = succeeded,
            rttMs = SystemClock.elapsedRealtime() - started,
            server = results.joinToString(",") {
                it.first.hostAddress.orEmpty()
            },
            error = if (succeeded) "" else results.joinToString(",") {
                it.third
            },
            ipv4Succeeded = v4,
            ipv6Succeeded = v6
        )
    }

    private fun probeOne(
        network: Network,
        server: InetAddress,
        onSocketChanged: (DatagramSocket?) -> Unit
    ): Triple<InetAddress, Boolean, String> {
        val transactionId = ThreadLocalRandom.current().nextInt(0, 65_536)
        val payload = query(transactionId)
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket(null)
            socket.soTimeout = TIMEOUT_MS
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(0))
            onSocketChanged(socket)
            socket.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetSocketAddress(server, DNS_PORT)
                )
            )
            val response = ByteArray(1_232)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            val responseId =
                if (packet.length >= 2) {
                    ((response[0].toInt() and 0xff) shl 8) or
                        (response[1].toInt() and 0xff)
                } else {
                    -1
                }
            Triple(
                server,
                responseId == transactionId,
                if (responseId == transactionId) {
                    ""
                } else {
                    "DNS_TRANSACTION_MISMATCH"
                }
            )
        } catch (error: Exception) {
            Triple(server, false, error.javaClass.simpleName)
        } finally {
            onSocketChanged(null)
            socket?.close()
        }
    }

    fun isTailscaleDns(address: InetAddress): Boolean =
        address.address.contentEquals(quad100V4) ||
            address.address.contentEquals(quad100V6)

    private fun query(transactionId: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeShort(transactionId)
            output.writeShort(0x0100)
            output.writeShort(1)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(0)
            "luonnotar.invalid".split('.').forEach { label ->
                val encoded = label.toByteArray(Charsets.US_ASCII)
                output.writeByte(encoded.size)
                output.write(encoded)
            }
            output.writeByte(0)
            output.writeShort(1)
            output.writeShort(1)
        }
        return bytes.toByteArray()
    }
}
