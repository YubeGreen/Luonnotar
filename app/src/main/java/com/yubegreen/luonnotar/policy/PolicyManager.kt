package com.yubegreen.luonnotar.policy

import android.content.Context
import com.yubegreen.luonnotar.R
import com.yubegreen.luonnotar.ui.i18n.AppLanguageStore
import com.yubegreen.luonnotar.ui.i18n.UiText
import java.security.MessageDigest
import java.time.Instant

object PolicyManager {
    const val VERSION = "1.3"
    private const val PREFS = "luonnotar_policy"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_HASH = "accepted_hash"
    private const val KEY_TIME = "accepted_at"

    fun text(context: Context): String =
        readPolicy(
            context,
            if (AppLanguageStore.isEnglish(context)) {
                R.raw.luonnotar_policy_en
            } else {
                R.raw.luonnotar_policy_zh
            }
        )

    private fun canonicalText(context: Context): String =
        readPolicy(context, R.raw.luonnotar_policy_zh)

    private fun readPolicy(context: Context, rawResource: Int): String =
        context.resources.openRawResource(rawResource)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText().trim() }

    fun isAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VERSION, null) == VERSION &&
            prefs.getString(KEY_HASH, null) == hash(canonicalText(context))
    }

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERSION, VERSION)
            .putString(KEY_HASH, hash(canonicalText(context)))
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
        val version = prefs.getString(KEY_VERSION, null)
        val acceptedAt = prefs.getString(KEY_TIME, null)
        return UiText.choose(
            context,
            "政策 ${version ?: "未同意"} · ${acceptedAt ?: "尚无确认记录"}",
            "Policy ${version ?: "not accepted"} · ${acceptedAt ?: "no acceptance record"}"
        ).toString()
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
