package io.bbui.assistant

import android.content.Context
import java.io.File

/** Only fresh installs are introduced; updates must not interrupt established users. */
object OnboardingState {
    private const val PREFS = "onboarding"
    fun shouldShow(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean("introduced", false)) return false
        if (preferences.getBoolean("started", false)) return true
        val existing = listOf("model.enc", "conversations.json", "chat-display.json").any { File(context.noBackupFilesDir, it).exists() }
        if (existing) complete(context)
        else preferences.edit().putBoolean("started", true).apply()
        return !existing
    }
    fun complete(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("introduced", true).apply() }
    fun hasModel(context: Context): Boolean = runCatching {
        val config = SettingsStore(context).load()
        listOf("model", "baseUrl", "apiKey").all { config.optString(it).isNotBlank() }
    }.getOrDefault(false)
}
