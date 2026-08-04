package com.yubegreen.luonnotar.privileged

internal data class GmsImportanceFenceStatus(
    val active: Boolean,
    val anyConnected: Boolean,
    val bothConnected: Boolean,
    val generation: Long,
    val mainState: String,
    val mainAction: String,
    val mainComponent: String,
    val persistentState: String,
    val persistentAction: String,
    val persistentComponent: String,
    val rawData: String
)

internal object GmsImportanceFenceStatusParser {
    private val dataPattern = Regex("data=\\\"([^\\\"]*)\\\"")

    fun parseCommandOutput(output: String): GmsImportanceFenceStatus? {
        val data = dataPattern.find(output)?.groupValues?.getOrNull(1)
            ?: output.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("ok=") }
            ?: return null
        val values = data.split(';')
            .mapNotNull { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) null else entry.substring(0, index) to entry.substring(index + 1)
            }
            .toMap()
        if (values["ok"]?.toBooleanStrictOrNull() != true) return null
        return GmsImportanceFenceStatus(
            active = values["active"]?.toBooleanStrictOrNull() == true,
            anyConnected = values["anyConnected"]?.toBooleanStrictOrNull() == true,
            bothConnected = values["bothConnected"]?.toBooleanStrictOrNull() == true,
            generation = values["generation"]?.toLongOrNull() ?: 0L,
            mainState = values["mainState"].orEmpty(),
            mainAction = values["mainAction"].orEmpty(),
            mainComponent = values["mainComponent"].orEmpty(),
            persistentState = values["persistentState"].orEmpty(),
            persistentAction = values["persistentAction"].orEmpty(),
            persistentComponent = values["persistentComponent"].orEmpty(),
            rawData = data
        )
    }
}
