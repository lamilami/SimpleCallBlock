package com.datec.simplecallblocker

import android.content.Context
import java.io.File

/** All app state, stored locally (SharedPreferences + a small log file). No network, no cloud. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val logFile = File(context.applicationContext.filesDir, "blocked_log.tsv")

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Block numbers that are hidden or not in the contacts. */
    var blockUnknown: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_UNKNOWN, false)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_UNKNOWN, v).apply()

    /** Numbers / prefixes as entered by the user; normalized at match time. */
    val blacklist: Set<String> get() = getSet(KEY_BLACKLIST)
    val prefixes: Set<String> get() = getSet(KEY_PREFIXES)

    fun addToBlacklist(number: String) = editSet(KEY_BLACKLIST) { it += number.trim() }
    fun removeFromBlacklist(number: String) = editSet(KEY_BLACKLIST) { it -= number }
    fun addPrefix(prefix: String) = editSet(KEY_PREFIXES) { it += prefix.trim() }
    fun removePrefix(prefix: String) = editSet(KEY_PREFIXES) { it -= prefix }

    private fun getSet(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun editSet(key: String, change: (MutableSet<String>) -> Unit) {
        // The set returned by getStringSet must not be modified, so work on a copy.
        val set = getSet(key).toMutableSet()
        change(set)
        prefs.edit().putStringSet(key, set).apply()
    }

    // ---- Blocked-calls log: one "timestampMillis<TAB>number<TAB>reason" line per call, newest last ----

    data class LogEntry(val time: Long, val number: String, val reason: String)

    @Synchronized
    fun addLog(number: String, reason: String) {
        val lines = readLines() + "${System.currentTimeMillis()}\t${number.replace('\t', ' ')}\t$reason"
        logFile.writeText(lines.takeLast(MAX_LOG).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun readLog(): List<LogEntry> = readLines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 3) p[0].toLongOrNull()?.let { LogEntry(it, p[1], p[2]) } else null
    }.asReversed()

    @Synchronized
    fun clearLog() {
        logFile.delete()
    }

    private fun readLines(): List<String> =
        if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() } else emptyList()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_BLOCK_UNKNOWN = "block_unknown"
        const val KEY_BLACKLIST = "blacklist"
        const val KEY_PREFIXES = "prefixes"
        const val MAX_LOG = 200
    }
}
