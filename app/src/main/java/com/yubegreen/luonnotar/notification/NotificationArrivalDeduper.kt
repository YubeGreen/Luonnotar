package com.yubegreen.luonnotar.notification

enum class NotificationArrivalKind {
    NEW,
    UPDATE,
    DUPLICATE
}

data class NotificationArrivalDecision(
    val kind: NotificationArrivalKind,
    val recentFingerprints: List<String>
)

object NotificationArrivalDeduper {
    private const val MAX_RECENT = 100

    fun classify(
        recent: List<String>,
        packageName: String,
        keyHash: String,
        postTime: Long
    ): NotificationArrivalDecision {
        val keyPrefix = "$packageName|$keyHash|"
        val fingerprint = "$keyPrefix$postTime"
        val kind = when {
            fingerprint in recent -> NotificationArrivalKind.DUPLICATE
            recent.any { it.startsWith(keyPrefix) } -> NotificationArrivalKind.UPDATE
            else -> NotificationArrivalKind.NEW
        }
        val updated = if (kind == NotificationArrivalKind.DUPLICATE) {
            recent
        } else {
            buildList {
                add(fingerprint)
                recent.asSequence()
                    .filterNot { it.startsWith(keyPrefix) }
                    .take(MAX_RECENT - 1)
                    .forEach(::add)
            }
        }
        return NotificationArrivalDecision(kind, updated)
    }

    fun removeKey(
        recent: List<String>,
        packageName: String,
        keyHash: String
    ): List<String> {
        val keyPrefix = "$packageName|$keyHash|"
        return recent.filterNot { it.startsWith(keyPrefix) }
    }
}
