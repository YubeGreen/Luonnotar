package com.yubegreen.luonnotar.monitor

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

data class VpnDnsProbeResult(
    val supported: Boolean,
    val succeeded: Boolean,
    val rttMs: Long,
    val answerCount: Int,
    val responseCode: Int,
    val error: String = ""
)

object VpnDnsProbe {
    private const val QUERY_NAME = "connectivitycheck.gstatic.com"
    private const val TIMEOUT_MS = 1_500L

    fun probe(
        network: Network,
        onCancellationChanged: (CancellationSignal?) -> Unit = {}
    ): VpnDnsProbeResult {
        if (Build.VERSION.SDK_INT < 29) {
            return VpnDnsProbeResult(
                supported = false,
                succeeded = false,
                rttMs = -1L,
                answerCount = 0,
                responseCode = -1,
                error = "DNS_RESOLVER_REQUIRES_API_29"
            )
        }
        return probeApi29(network, onCancellationChanged)
    }

    @androidx.annotation.RequiresApi(29)
    private fun probeApi29(
        network: Network,
        onCancellationChanged: (CancellationSignal?) -> Unit
    ): VpnDnsProbeResult {
        val cancellation = CancellationSignal()
        val latch = CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        var answerCount = 0
        var responseCode = -1
        var error = ""
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(
                answer: List<InetAddress>,
                rcode: Int
            ) {
                answerCount = answer.size
                responseCode = rcode
                latch.countDown()
            }

            override fun onError(dnsError: DnsResolver.DnsException) {
                error =
                    "${dnsError.javaClass.simpleName}:${dnsError.code}:${
                        dnsError.cause?.javaClass?.simpleName.orEmpty()
                    }"
                latch.countDown()
            }
        }
        return try {
            onCancellationChanged(cancellation)
            DnsResolver.getInstance().query(
                network,
                QUERY_NAME,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                Executor(Runnable::run),
                cancellation,
                callback
            )
            val completed = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                cancellation.cancel()
                error = "DnsResolverTimeout"
            }
            VpnDnsProbeResult(
                supported = true,
                succeeded =
                    completed &&
                        error.isBlank() &&
                        responseCode == 0 &&
                        answerCount > 0,
                rttMs = SystemClock.elapsedRealtime() - started,
                answerCount = answerCount,
                responseCode = responseCode,
                error = error
            )
        } catch (failure: Exception) {
            VpnDnsProbeResult(
                supported = true,
                succeeded = false,
                rttMs = SystemClock.elapsedRealtime() - started,
                answerCount = 0,
                responseCode = -1,
                error = failure.javaClass.simpleName
            )
        } finally {
            onCancellationChanged(null)
        }
    }
}
