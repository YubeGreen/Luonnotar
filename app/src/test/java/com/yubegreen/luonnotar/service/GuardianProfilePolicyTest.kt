package com.yubegreen.luonnotar.service

import com.yubegreen.luonnotar.util.LuonnotarPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianProfilePolicyTest {
    @Test
    fun `vivo and iqoo default to cooperative profile`() {
        assertTrue(
            GuardianProfilePolicy.isVivoFamily(
                "vivo",
                "vivo"
            )
        )
        assertTrue(
            GuardianProfilePolicy.isVivoFamily(
                "unknown",
                "iQOO"
            )
        )
        assertEquals(
            GuardianRuntimeProfile.IQOO_COOPERATIVE,
            GuardianProfilePolicy.defaultProfile(true)
        )
    }

    @Test
    fun `iqoo defaults to screen off guard without wifi lock`() {
        val defaults = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.IQOO_COOPERATIVE
        )

        assertFalse(defaults.permanentCpuLock)
        assertTrue(defaults.screenOffCpuGuard)
        assertTrue(defaults.scopedCpuLock)
        assertFalse(defaults.highPerfWifiLock)
        assertFalse(defaults.screenEventProbe)
        assertFalse(defaults.periodicDns)
        assertFalse(defaults.periodicHttps)
        assertFalse(defaults.automaticMtalk)
        assertFalse(defaults.persistentNetworkLease)
        assertFalse(defaults.persistentHeartbeatSocket)
        assertFalse(defaults.frequentNotificationRefresh)
    }

    @Test
    fun `standard integrates screen off guard by default`() {
        val defaults = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.STANDARD
        )

        assertTrue(defaults.screenOffCpuGuard)
        assertTrue(defaults.scopedCpuLock)
        assertTrue(defaults.periodicDns)
        assertTrue(defaults.periodicHttps)
    }

    @Test
    fun `adb passive disables every active experiment`() {
        val defaults = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.ADB_PASSIVE
        )

        assertFalse(defaults.permanentCpuLock)
        assertFalse(defaults.screenOffCpuGuard)
        assertFalse(defaults.scopedCpuLock)
        assertFalse(defaults.highPerfWifiLock)
        assertFalse(defaults.screenEventProbe)
        assertFalse(defaults.periodicDns)
        assertFalse(defaults.periodicHttps)
        assertFalse(defaults.automaticMtalk)
        assertFalse(defaults.persistentNetworkLease)
        assertFalse(defaults.persistentHeartbeatSocket)
        assertFalse(defaults.frequentNotificationRefresh)
    }

    @Test
    fun `passive profile uses independent sixty second window`() {
        assertFalse(
            GuardianPassiveWindowPolicy.shouldClose(
                100_000L,
                159_999L
            )
        )
        assertTrue(
            GuardianPassiveWindowPolicy.shouldClose(
                100_000L,
                160_000L
            )
        )
        assertFalse(
            GuardianPassiveWindowPolicy.shouldClose(
                0L,
                999_999L
            )
        )
    }

    @Test
    fun `lab levels progressively enable screen guard`() {
        val level0 = GuardianProfilePolicy.labLevel(0)
        val level1 = GuardianProfilePolicy.labLevel(1)
        val level2 = GuardianProfilePolicy.labLevel(2)
        val level3 = GuardianProfilePolicy.labLevel(3)
        val level4 = GuardianProfilePolicy.labLevel(4)

        assertFalse(level0.scopedCpuLock)
        assertTrue(level1.scopedCpuLock)
        assertTrue(level1.screenOffCpuGuard)
        assertTrue(level2.screenEventProbe)
        assertTrue(level3.periodicDns)
        assertTrue(level3.periodicHttps)
        assertTrue(level4.permanentCpuLock)
        assertTrue(level4.highPerfWifiLock)
        assertTrue(level4.screenOffCpuGuard)
        assertFalse(level4.automaticMtalk)
    }

    @Test
    fun `quiet window ends exactly at deadline`() {
        assertTrue(
            GuardianProfilePolicy.quietWindowActive(
                119_999L,
                120_000L
            )
        )
        assertFalse(
            GuardianProfilePolicy.quietWindowActive(
                120_000L,
                120_000L
            )
        )
    }

    @Test
    fun `notification refresh defaults to ten minutes`() {
        val cooperative = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.IQOO_COOPERATIVE
        )

        assertEquals(
            10 * 60_000L,
            GuardianProfilePolicy
                .notificationRefreshInterval(cooperative)
        )
        assertEquals(
            60_000L,
            GuardianProfilePolicy.notificationRefreshInterval(
                cooperative.copy(
                    frequentNotificationRefresh = true
                )
            )
        )
    }

    @Test
    fun `high performance wifi remains opt in`() {
        assertFalse(
            GuardianProfilePolicy.defaults(
                GuardianRuntimeProfile.STANDARD
            ).highPerfWifiLock
        )
        assertFalse(
            GuardianProfilePolicy.defaults(
                GuardianRuntimeProfile.IQOO_COOPERATIVE
            ).highPerfWifiLock
        )
    }

    @Test
    fun `runtime config rejects lab-only options outside lab profile`() {
        val experiments = GuardianProfilePolicy.experimentKeys
            .associateWith { false }
            .toMutableMap()
        experiments[
            LuonnotarPreferences
                .KEY_EXPERIMENT_PERMANENT_CPU_LOCK
        ] = true

        assertEquals(
            "lab_profile_required:permanent_cpu_lock",
            GuardianProfilePolicy.runtimeConfigError(
                GuardianRuntimeProfile.IQOO_COOPERATIVE,
                experiments
            )
        )
    }

    @Test
    fun `adb passive rejects stale active experiment values`() {
        val experiments = GuardianProfilePolicy.experimentKeys
            .associateWith { false }
            .toMutableMap()
        experiments[
            LuonnotarPreferences
                .KEY_EXPERIMENT_SCOPED_CPU_LOCK
        ] = true

        assertEquals(
            "adb_passive_forbids:scoped_cpu_lock",
            GuardianProfilePolicy.runtimeConfigError(
                GuardianRuntimeProfile.ADB_PASSIVE,
                experiments
            )
        )
    }

    @Test
    fun `adb bridge exposes originos prevention controls`() {
        assertTrue(
            LuonnotarPreferences
                .KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK in
                GuardianProfilePolicy.adbMutableExperimentKeys
        )
        assertTrue(
            LuonnotarPreferences
                .KEY_EXPERIMENT_PERIODIC_DNS in
                GuardianProfilePolicy.adbMutableExperimentKeys
        )
        assertTrue(
            LuonnotarPreferences
                .KEY_EXPERIMENT_PERIODIC_HTTPS in
                GuardianProfilePolicy.adbMutableExperimentKeys
        )
        assertTrue(
            LuonnotarPreferences
                .KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE in
                GuardianProfilePolicy.adbMutableExperimentKeys
        )
        assertTrue(
            LuonnotarPreferences
                .KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET in
                GuardianProfilePolicy.adbMutableExperimentKeys
        )
    }

    @Test
    fun `sanitizer clears stale profile-incompatible bits`() {
        val raw = GuardianProfilePolicy.experimentKeys
            .associateWith { true }

        val passive = GuardianProfilePolicy.sanitizeExperiments(
            GuardianRuntimeProfile.ADB_PASSIVE,
            raw
        )

        assertFalse(
            passive[
                com.yubegreen.luonnotar.util.LuonnotarPreferences
                    .KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK
            ] == true
        )
        assertFalse(
            passive[
                com.yubegreen.luonnotar.util.LuonnotarPreferences
                    .KEY_EXPERIMENT_SCOPED_CPU_LOCK
            ] == true
        )
        assertTrue(
            passive[
                com.yubegreen.luonnotar.util.LuonnotarPreferences
                    .KEY_MONITOR_GMS
            ] == true
        )
    }

}
