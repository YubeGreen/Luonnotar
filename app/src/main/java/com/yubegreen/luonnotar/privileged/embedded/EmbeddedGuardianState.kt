package com.yubegreen.luonnotar.privileged.embedded

enum class EmbeddedSetupState {
    IDLE,
    DISCOVERING,
    WAITING_PAIRING_CODE,
    STARTING,
    FAILED
}

enum class EmbeddedConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEAD
}

data class EmbeddedGuardianRuntimeState(
    val featureEnabled: Boolean,
    val setupState: EmbeddedSetupState,
    val connectionState: EmbeddedConnectionState,
    val reportedUid: Int,
    val generation: Long
) {
    val liveConnected: Boolean
        get() = setupState == EmbeddedSetupState.IDLE &&
            connectionState == EmbeddedConnectionState.CONNECTED &&
            reportedUid == SHELL_UID

    companion object {
        const val SHELL_UID = 2_000
    }
}

data class EmbeddedGuardianPresentation(
    val summary: String,
    val setupButtonText: String,
    val stopButtonText: String,
    val setupEnabled: Boolean,
    val stopEnabled: Boolean,
    val privilegedOperationsEnabled: Boolean
)

data class EmbeddedGuardianDisablePlan(
    val attemptRemoteStop: Boolean,
    val stopLocalSetupService: Boolean = true,
    val cancelSetupNotification: Boolean = true,
    val cancelRebootNotification: Boolean = true
)

object EmbeddedGuardianStatePolicy {
    fun normalizePersisted(
        featureEnabled: Boolean,
        setupState: EmbeddedSetupState,
        connectionState: EmbeddedConnectionState,
        reportedUid: Int,
        generation: Long,
        runtimeOwnerIsCurrent: Boolean
    ): EmbeddedGuardianRuntimeState {
        if (!featureEnabled) {
            return EmbeddedGuardianRuntimeState(
                featureEnabled = false,
                setupState = EmbeddedSetupState.IDLE,
                connectionState = EmbeddedConnectionState.DISCONNECTED,
                reportedUid = -1,
                generation = generation
            )
        }
        if (!runtimeOwnerIsCurrent) {
            return EmbeddedGuardianRuntimeState(
                featureEnabled = true,
                setupState = EmbeddedSetupState.IDLE,
                connectionState = EmbeddedConnectionState.DISCONNECTED,
                reportedUid = -1,
                generation = generation
            )
        }
        if (connectionState == EmbeddedConnectionState.CONNECTED && reportedUid == EmbeddedGuardianRuntimeState.SHELL_UID) {
            return EmbeddedGuardianRuntimeState(
                featureEnabled = true,
                setupState = EmbeddedSetupState.IDLE,
                connectionState = connectionState,
                reportedUid = reportedUid,
                generation = generation
            )
        }
        return EmbeddedGuardianRuntimeState(
            featureEnabled = true,
            setupState = setupState,
            connectionState = if (connectionState == EmbeddedConnectionState.CONNECTED) {
                EmbeddedConnectionState.DEAD
            } else {
                connectionState
            },
            reportedUid = -1,
            generation = generation
        )
    }

    fun acceptsAsyncUpdate(
        featureEnabled: Boolean,
        expectedGeneration: Long,
        currentGeneration: Long
    ): Boolean = featureEnabled && expectedGeneration == currentGeneration

    fun acceptsLiveHandshake(pingUid: Int, statusUid: Int, running: Boolean): Boolean =
        pingUid == EmbeddedGuardianRuntimeState.SHELL_UID &&
            statusUid == EmbeddedGuardianRuntimeState.SHELL_UID &&
            running

    fun disabledState(state: EmbeddedGuardianRuntimeState): EmbeddedGuardianRuntimeState =
        EmbeddedGuardianRuntimeState(
            featureEnabled = false,
            setupState = EmbeddedSetupState.IDLE,
            connectionState = EmbeddedConnectionState.DISCONNECTED,
            reportedUid = -1,
            generation = state.generation + 1L
        )

    fun presentation(state: EmbeddedGuardianRuntimeState): EmbeddedGuardianPresentation {
        val running = state.liveConnected
        val summary = when {
            !state.featureEnabled ->
                "未启用。首次及重启后通过本机无线 ADB 拉起 shell UID 引擎。"
            running ->
                "运行中 · shell UID 2000 · 内置 app_process 引擎 · 不依赖 Shizuku"
            state.setupState == EmbeddedSetupState.DISCOVERING ->
                "功能已启用 · 正在发现无线调试端口"
            state.setupState == EmbeddedSetupState.WAITING_PAIRING_CODE ->
                "功能已启用 · 正在等待无线调试配对码"
            state.setupState == EmbeddedSetupState.STARTING ->
                "功能已启用 · 正在配对或启动引擎"
            state.setupState == EmbeddedSetupState.FAILED ->
                "功能已启用 · 引擎启动失败"
            state.connectionState == EmbeddedConnectionState.DEAD ->
                "功能已启用 · 连接已失效"
            state.connectionState == EmbeddedConnectionState.CONNECTING ->
                "功能已启用 · 正在验证实时引擎连接"
            else ->
                "功能已启用 · 引擎未运行"
        }
        return EmbeddedGuardianPresentation(
            summary = summary,
            setupButtonText = if (running) {
                "内置特权引擎已连接"
            } else {
                "配对 / 启动内置特权引擎"
            },
            stopButtonText = if (running) {
                "停止并关闭内置特权引擎"
            } else {
                "关闭内置特权引擎功能"
            },
            setupEnabled = !running,
            stopEnabled = state.featureEnabled,
            privilegedOperationsEnabled = running
        )
    }

    fun disablePlan(
        state: EmbeddedGuardianRuntimeState,
        identityAvailable: Boolean
    ): EmbeddedGuardianDisablePlan = EmbeddedGuardianDisablePlan(
        attemptRemoteStop = state.liveConnected || identityAvailable
    )
}
