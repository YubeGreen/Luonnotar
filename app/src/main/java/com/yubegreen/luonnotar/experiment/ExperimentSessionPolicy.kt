package com.yubegreen.luonnotar.experiment

object ExperimentSessionPolicy {
    const val MAX_SESSION_NAME_LENGTH = 80
    const val MAX_MARK_LABEL_LENGTH = 120

    fun normalizeSessionName(raw: String?): String? =
        normalizeLabel(raw, MAX_SESSION_NAME_LENGTH)

    fun normalizeMarkLabel(raw: String?): String? =
        normalizeLabel(raw, MAX_MARK_LABEL_LENGTH)

    fun newSessionId(
        wallTimeMs: Long,
        elapsedRealtimeMs: Long,
        processId: Int
    ): String = buildString {
        append(wallTimeMs.toString(36))
        append('-')
        append(elapsedRealtimeMs.toString(36))
        append('-')
        append(processId.coerceAtLeast(0).toString(36))
    }

    private fun normalizeLabel(raw: String?, maxLength: Int): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return null

        val normalized = buildString(input.length.coerceAtMost(maxLength)) {
            var previousWasSeparator = false
            input.forEach { character ->
                if (length >= maxLength) return@forEach
                when {
                    character.isLetterOrDigit() -> {
                        append(character)
                        previousWasSeparator = false
                    }
                    character == '.' || character == '-' || character == ':' -> {
                        append(character)
                        previousWasSeparator = false
                    }
                    character == '_' || character.isWhitespace() -> {
                        if (!previousWasSeparator && isNotEmpty()) {
                            append('_')
                            previousWasSeparator = true
                        }
                    }
                    else -> {
                        if (!previousWasSeparator && isNotEmpty()) {
                            append('_')
                            previousWasSeparator = true
                        }
                    }
                }
            }
        }.trim('_', '.', '-', ':')

        return normalized.takeIf(String::isNotEmpty)
    }
}
