package com.yubegreen.luonnotar.policy

object PolicyReadGate {
    fun isAtEnd(
        scrollY: Int,
        viewportHeight: Int,
        contentHeight: Int,
        tolerancePx: Int
    ): Boolean {
        if (viewportHeight <= 0 || contentHeight <= 0) return false
        return contentHeight <= viewportHeight + tolerancePx ||
            scrollY + viewportHeight >= contentHeight - tolerancePx
    }
}
