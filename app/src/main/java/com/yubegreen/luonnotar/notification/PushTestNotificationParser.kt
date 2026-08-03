package com.yubegreen.luonnotar.notification

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

data class PushTestNotification(
    val sequence: Long,
    val senderLocalTime: String,
    val senderEpochMs: Long,
    val senderZoneId: String,
    val senderPrecisionMs: Long
)

enum class PushTestCandidateSource(
    val diagnosticName: String,
    val deliveryEvidence: Boolean
) {
    EXTRA_TEXT("EXTRA_TEXT", true),
    EXTRA_BIG_TEXT("EXTRA_BIG_TEXT", true),
    EXTRA_TEXT_LINES("EXTRA_TEXT_LINES", true),
    EXTRA_MESSAGES("EXTRA_MESSAGES", true),
    EXTRA_HISTORIC_MESSAGES("EXTRA_HISTORIC_MESSAGES", true),
    EXTRA_SUB_TEXT("EXTRA_SUB_TEXT", false),
    EXTRA_TITLE("EXTRA_TITLE", false)
}

data class PushTestCandidate(
    val source: PushTestCandidateSource,
    val text: CharSequence?
)

data class PushTestParseDiagnostic(
    val notification: PushTestNotification?,
    val matchedSource: PushTestCandidateSource?,
    val candidateSourcesPresent: List<String>,
    val messageCandidateCount: Int,
    val controlledPrefixObserved: Boolean,
    val rejectionReason: String
)

object PushTestNotificationParser {
    const val DEFAULT_SENDER_ZONE_ID = "Pacific/Auckland"
    private val pattern =
        Regex(
            """^PUSH_TEST_(\d+)\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})(?:\.(\d{3}))?$"""
        )
    private val formatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
        .optionalEnd()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        text: CharSequence?,
        senderZone: ZoneId = ZoneId.of(DEFAULT_SENDER_ZONE_ID)
    ): PushTestNotification? {
        val value = normalizeForMatching(text) ?: return null
        val match = pattern.matchEntire(value) ?: return null
        val sequence = match.groupValues[1].toLongOrNull() ?: return null
        val senderBaseTime = match.groupValues[2]
        val senderMillis = match.groupValues[3]
        val senderLocalTime =
            if (senderMillis.isBlank()) senderBaseTime else "$senderBaseTime.$senderMillis"
        val senderPrecisionMs = if (senderMillis.isBlank()) 1000L else 1L
        val localDateTime = runCatching {
            LocalDateTime.parse(senderLocalTime, formatter)
        }.getOrNull() ?: return null
        return PushTestNotification(
            sequence = sequence,
            senderLocalTime = senderLocalTime,
            senderEpochMs = localDateTime.atZone(senderZone).toInstant().toEpochMilli(),
            senderZoneId = senderZone.id,
            senderPrecisionMs = senderPrecisionMs
        )
    }

    fun parseFirst(candidates: Iterable<CharSequence?>): PushTestNotification? =
        candidates.firstNotNullOfOrNull(::parse)

    /**
     * Notification MessagingStyle arrays are commonly ordered oldest to newest.
     * A backlog can therefore expose several historical PUSH_TEST messages in a
     * single notification update. Select the newest controlled timestamp so a
     * host watchdog can safely treat all earlier sends as delivered as well.
     */
    fun parseLatest(candidates: Iterable<CharSequence?>): PushTestNotification? =
        parseLatestCandidates(
            candidates.map { PushTestCandidate(PushTestCandidateSource.EXTRA_TEXT, it) }
        ).notification

    fun parseLatestCandidates(
        candidates: Iterable<PushTestCandidate>,
        senderZone: ZoneId = ZoneId.of(DEFAULT_SENDER_ZONE_ID)
    ): PushTestParseDiagnostic {
        val prepared = candidates.mapNotNull { candidate ->
            val normalized = normalizeForMatching(candidate.text) ?: return@mapNotNull null
            candidate to normalized
        }
        val candidateSources = prepared
            .map { it.first.source.diagnosticName }
            .distinct()
        val controlledPrefixObserved = prepared.any {
            it.second.contains("PUSH_TEST_", ignoreCase = false)
        }
        val eligibleMatches = prepared.mapNotNull { (candidate, _) ->
            if (!candidate.source.deliveryEvidence) return@mapNotNull null
            parse(candidate.text, senderZone)?.let { candidate.source to it }
        }
        val latest = eligibleMatches.maxWithOrNull(
            compareBy<Pair<PushTestCandidateSource, PushTestNotification>> {
                it.second.senderEpochMs
            }.thenBy { it.second.sequence }
        )
        if (latest != null) {
            return PushTestParseDiagnostic(
                notification = latest.second,
                matchedSource = latest.first,
                candidateSourcesPresent = candidateSources,
                messageCandidateCount = prepared.count {
                    it.first.source.deliveryEvidence
                },
                controlledPrefixObserved = controlledPrefixObserved,
                rejectionReason = ""
            )
        }

        val diagnosticOnlyMatch = prepared.any { (candidate, _) ->
            !candidate.source.deliveryEvidence && parse(candidate.text, senderZone) != null
        }
        val eligiblePresent = prepared.any { it.first.source.deliveryEvidence }
        val reason = when {
            diagnosticOnlyMatch -> "valid_pattern_in_diagnostic_only_field"
            controlledPrefixObserved -> "controlled_prefix_without_valid_message_format"
            eligiblePresent -> "no_controlled_push_test_in_message_fields"
            prepared.isNotEmpty() -> "diagnostic_fields_only"
            else -> "no_supported_text_fields"
        }
        return PushTestParseDiagnostic(
            notification = null,
            matchedSource = null,
            candidateSourcesPresent = candidateSources,
            messageCandidateCount = prepared.count {
                it.first.source.deliveryEvidence
            },
            controlledPrefixObserved = controlledPrefixObserved,
            rejectionReason = reason
        )
    }

    internal fun normalizeForMatching(text: CharSequence?): String? {
        val value = text?.toString() ?: return null
        val normalized = StringBuilder(value.length)
        var previousWasSpace = false
        value.forEach { character ->
            val type = Character.getType(character)
            val isSpace =
                Character.isWhitespace(character) ||
                    Character.isSpaceChar(character)
            val shouldDrop =
                type == Character.FORMAT.toInt() ||
                    (
                        type == Character.CONTROL.toInt() &&
                            character != '\r' &&
                            character != '\n' &&
                            character != '\t'
                        )
            when {
                shouldDrop -> Unit
                isSpace -> {
                    if (!previousWasSpace && normalized.isNotEmpty()) {
                        normalized.append(' ')
                    }
                    previousWasSpace = true
                }
                else -> {
                    normalized.append(character)
                    previousWasSpace = false
                }
            }
        }
        return normalized.toString().trim().takeIf { it.isNotEmpty() }
    }
}
