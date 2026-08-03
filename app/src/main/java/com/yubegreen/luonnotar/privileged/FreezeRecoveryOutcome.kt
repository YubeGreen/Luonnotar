package com.yubegreen.luonnotar.privileged

enum class FreezeRecoveryVerdict {
    NOT_NEEDED,
    VERIFIED_THAWED,
    STILL_FROZEN,
    STATE_UNOBSERVABLE,
    NO_SAFE_COMMAND_PATH,
    COMMAND_FAILED
}

data class FreezeRecoveryOutcome(
    val ownerPackage: String?,
    val beforeFrozen: Boolean?,
    val afterFrozen: Boolean?,
    val activityManagerAttempted: Boolean,
    val activityManagerAccepted: Boolean,
    val directCgroupAttempted: Boolean,
    val directCgroupAccepted: Boolean,
    val verdict: FreezeRecoveryVerdict,
    val detail: String
) {
    val commandAttempted: Boolean
        get() = activityManagerAttempted || directCgroupAttempted

    val commandAccepted: Boolean
        get() = activityManagerAccepted || directCgroupAccepted

    val thawVerified: Boolean?
        get() = when (afterFrozen) {
            false -> true
            true -> false
            null -> null
        }
}

/** Pure result classification used by the engine and unit tests. */
object FreezeRecoveryClassifier {
    fun shouldTryDirectCgroup(
        enabled: Boolean,
        hasControlFile: Boolean,
        beforeFrozen: Boolean?,
        afterActivityManagerFrozen: Boolean?,
        activityManagerAttempted: Boolean,
        activityManagerAccepted: Boolean
    ): Boolean =
        enabled &&
            hasControlFile &&
            beforeFrozen == true &&
            afterActivityManagerFrozen != false &&
            (
                !activityManagerAttempted ||
                    !activityManagerAccepted ||
                    afterActivityManagerFrozen == true
                )

    fun classify(
        beforeFrozen: Boolean?,
        afterFrozen: Boolean?,
        commandAttempted: Boolean,
        commandAccepted: Boolean
    ): FreezeRecoveryVerdict = when {
        beforeFrozen == false -> FreezeRecoveryVerdict.NOT_NEEDED
        beforeFrozen == true && afterFrozen == false -> FreezeRecoveryVerdict.VERIFIED_THAWED
        afterFrozen == false -> FreezeRecoveryVerdict.STATE_UNOBSERVABLE
        commandAttempted && !commandAccepted -> FreezeRecoveryVerdict.COMMAND_FAILED
        afterFrozen == true && commandAttempted -> FreezeRecoveryVerdict.STILL_FROZEN
        afterFrozen == true -> FreezeRecoveryVerdict.NO_SAFE_COMMAND_PATH
        commandAttempted -> FreezeRecoveryVerdict.STATE_UNOBSERVABLE
        else -> FreezeRecoveryVerdict.NO_SAFE_COMMAND_PATH
    }
}
