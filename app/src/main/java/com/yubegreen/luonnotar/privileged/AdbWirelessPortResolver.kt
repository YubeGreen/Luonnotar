package com.yubegreen.luonnotar.privileged

/**
 * Parses Android's `service call adb` Parcel output without depending on hidden
 * framework classes. IAdbManager has kept getAdbWirelessPort() as transaction
 * 10 and isAdbWifiSupported() as transaction 12; both calls are read-only.
 *
 * The target OriginOS / SDK 36 device was also live-verified with tx10 returning
 * 0x8349 (=33609) while that exact port accepted an authenticated ADB shell.
 */
internal object AdbWirelessPortResolver {
    const val GET_WIRELESS_PORT_TRANSACTION = 10
    const val WIFI_SUPPORTED_TRANSACTION = 12

    fun parseBooleanParcel(output: String): Boolean? {
        val words = parcelWords(output)
        if (words.size < 2 || words[0] != 0L) return null
        return when (words[1]) {
            0L -> false
            1L -> true
            else -> null
        }
    }

    fun parsePortParcel(output: String): Int? {
        val words = parcelWords(output)
        if (words.size < 2 || words[0] != 0L) return null
        return words[1].toInt().takeIf { it in 1..65535 && it != AdbTcpPortHealthPolicy.PORT }
    }

    private fun parcelWords(output: String): List<Long> =
        WORD.findAll(output)
            .mapNotNull { match -> match.groupValues[1].toLongOrNull(16) }
            .toList()

    private val WORD = Regex("\\b([0-9A-Fa-f]{8})\\b")
}
