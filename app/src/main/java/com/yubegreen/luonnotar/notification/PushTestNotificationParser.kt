package com.yubegreen.luonnotar.notification

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class PushTestNotification(
    val sequence: Long,
    val senderLocalTime: String,
    val senderEpochMs: Long,
    val senderZoneId: String
)

object PushTestNotificationParser {
    const val DEFAULT_SENDER_ZONE_ID = "Pacific/Auckland"
    private val pattern =
        Regex("""^PUSH_TEST_(\d+)\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})$""")
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        text: CharSequence?,
        senderZone: ZoneId = ZoneId.of(DEFAULT_SENDER_ZONE_ID)
    ): PushTestNotification? {
        val value = text?.toString()?.trim() ?: return null
        val match = pattern.matchEntire(value) ?: return null
        val sequence = match.groupValues[1].toLongOrNull() ?: return null
        val senderLocalTime = match.groupValues[2]
        val localDateTime = runCatching {
            LocalDateTime.parse(senderLocalTime, formatter)
        }.getOrNull() ?: return null
        return PushTestNotification(
            sequence = sequence,
            senderLocalTime = senderLocalTime,
            senderEpochMs = localDateTime.atZone(senderZone).toInstant().toEpochMilli(),
            senderZoneId = senderZone.id
        )
    }

    fun parseFirst(candidates: Iterable<CharSequence?>): PushTestNotification? =
        candidates.firstNotNullOfOrNull(::parse)
}
