package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

enum class ThemePreference {
    DARK,
    LIGHT,
    SYSTEM
}

enum class BackgroundPreference {
    SOLID,
    SHAO_OU,
    CUSTOM_IMAGE
}

enum class BackgroundScale {
    FILL_CROP,
    FIT_CENTER
}

data class VisualPreferences(
    val theme: ThemePreference = ThemePreference.DARK,
    val background: BackgroundPreference = BackgroundPreference.SOLID,
    val backgroundScale: BackgroundScale = BackgroundScale.FILL_CROP
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .putString(KEY_BACKGROUND, background.name)
            .putString(KEY_BACKGROUND_SCALE, backgroundScale.name)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "luonnotar_visual"
        private const val KEY_THEME = "theme"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_BACKGROUND_SCALE = "background_scale"

        fun load(context: Context): VisualPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val loaded = load(prefs)
            return if (
                loaded.background == BackgroundPreference.CUSTOM_IMAGE &&
                !BackgroundImageStore.hasImage(context)
            ) {
                loaded.copy(background = BackgroundPreference.SOLID)
            } else {
                loaded
            }
        }

        fun load(prefs: SharedPreferences) = VisualPreferences(
            theme = enumValue(prefs.getString(KEY_THEME, null), ThemePreference.DARK),
            background = enumValue(prefs.getString(KEY_BACKGROUND, null), BackgroundPreference.SOLID),
            backgroundScale = enumValue(
                prefs.getString(KEY_BACKGROUND_SCALE, null),
                BackgroundScale.FILL_CROP
            )
        )

        fun nightMode(theme: ThemePreference): Int = when (theme) {
            ThemePreference.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemePreference.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemePreference.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
            runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
    }
}
