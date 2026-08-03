package com.yubegreen.luonnotar.privileged

data class GuardianProcess(
    val pid: Int,
    val name: String,
    val raw: String
)

object GuardianProcessParser {
    private val PID = Regex("^\\d+$")

    /** Accepts toybox `ps -A -o PID,NAME,ARGS` and common vendor `ps -A` layouts. */
    fun parse(output: String): List<GuardianProcess> = output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !isHeader(it) }
        .mapNotNull(::parseLine)
        .distinctBy { it.pid }
        .toList()

    fun matching(
        processes: List<GuardianProcess>,
        targets: Collection<String>
    ): List<GuardianProcess> {
        val safeTargets = targets.filter(GuardianEngineConfig::isSafeProcessName)
        return processes.filter { process ->
            safeTargets.any { target ->
                process.name == target || process.name.startsWith("$target:")
            }
        }
    }

    private fun parseLine(line: String): GuardianProcess? {
        val columns = line.split(Regex("\\s+"))
        if (columns.size < 2) return null

        // Explicit PID-first layout. Prefer ARGS over NAME because vendor toybox can
        // truncate NAME while ARGS still contains the complete Android process name.
        if (PID.matches(columns[0])) {
            val pid = columns[0].toIntOrNull() ?: return null
            val name = columns.drop(2).firstOrNull(::looksLikeProcessName)
                ?: columns.getOrNull(1)?.takeIf(::looksLikeProcessName)
                ?: return null
            return GuardianProcess(pid, name, line)
        }

        // Typical Android layout: USER PID PPID ... NAME/ARGS.
        val pidIndex = columns.indexOfFirst(PID::matches)
        if (pidIndex < 0) return null
        val pid = columns[pidIndex].toIntOrNull() ?: return null
        val name = columns.asReversed().firstOrNull(::looksLikeProcessName) ?: return null
        return GuardianProcess(pid, name, line)
    }

    private fun isHeader(line: String): Boolean {
        val upper = line.uppercase()
        return upper.startsWith("PID ") || upper.startsWith("USER ") || upper == "PID"
    }

    private fun looksLikeProcessName(value: String): Boolean =
        GuardianEngineConfig.isSafeProcessName(value)
}
