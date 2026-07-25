package com.yubegreen.luonnotar.policy

import android.content.Context

object PolicyGate {
    fun allowsMainUi(context: Context): Boolean = PolicyManager.isAccepted(context)
}
