package com.yubegreen.luonnotar.privileged.embedded

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One-at-a-time asynchronous self-update state owned by the shell engine. */
internal object EmbeddedSelfUpdateCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-self-update").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val last = AtomicReference(
        JSONObject()
            .put("state", "idle")
            .put("ok", true)
            .toString()
    )

    fun start(payload: String): String {
        val request = JSONObject(payload)
        val apkPath = request.optString("apkPath").trim()
        require(apkPath.isNotBlank()) { "apkPath required" }
        if (!running.compareAndSet(false, true)) {
            return JSONObject(last.get())
                .put("accepted", false)
                .put("reason", "self_update_already_running")
                .toString()
        }
        last.set(
            JSONObject()
                .put("state", "running")
                .put("ok", true)
                .put("accepted", true)
                .put("apkName", java.io.File(apkPath).name)
                .toString()
        )
        executor.execute {
            try {
                val result = EmbeddedSelfUpdateInstaller.install(apkPath)
                last.set(
                    JSONObject(result.toJson())
                        .put("state", if (result.ok) "success" else "failure")
                        .toString()
                )
            } catch (error: Throwable) {
                last.set(
                    JSONObject()
                        .put("state", "failure")
                        .put("ok", false)
                        .put("code", "INSTALL_FAILED")
                        .put("message", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(800))
                        .toString()
                )
            } finally {
                running.set(false)
            }
        }
        return JSONObject(last.get()).put("accepted", true).toString()
    }

    fun status(): String = JSONObject(last.get())
        .put("running", running.get())
        .toString()

    fun shutdown() {
        EmbeddedSelfUpdateInstaller.abandonActiveSession()
    }
}
