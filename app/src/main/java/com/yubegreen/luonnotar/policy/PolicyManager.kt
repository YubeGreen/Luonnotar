package com.yubegreen.luonnotar.policy

import android.content.Context
import com.yubegreen.luonnotar.R
import java.security.MessageDigest
import java.time.Instant

object PolicyManager {
    const val VERSION = "1.3"
    private const val PREFS = "luonnotar_policy"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_HASH = "accepted_hash"
    private const val KEY_TIME = "accepted_at"

    fun text(context: Context): String =
        context.resources.openRawResource(R.raw.luonnotar_policy_zh)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText().trim() }

    fun isAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VERSION, null) == VERSION &&
            prefs.getString(KEY_HASH, null) == hash(text(context))
    }

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERSION, VERSION)
            .putString(KEY_HASH, hash(text(context)))
            .putString(KEY_TIME, Instant.now().toString())
            .apply()
    }

    fun reject(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VERSION)
            .remove(KEY_HASH)
            .remove(KEY_TIME)
            .commit()
    }

    fun audit(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "政策 ${prefs.getString(KEY_VERSION, "未同意")} · " +
            (prefs.getString(KEY_TIME, null) ?: "尚无确认记录")
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
