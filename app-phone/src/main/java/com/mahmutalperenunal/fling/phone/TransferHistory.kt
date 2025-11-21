package com.mahmutalperenunal.fling.phone

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Direction of a transfer on the phone side. */
enum class Direction { IN, OUT }

/** Transfer log entry shown in the "Last Transfers" screen. */
data class TransferLog(
    val dir: Direction,
    val name: String,
    val size: Long,
    val success: Boolean,
    val time: Long
)

/**
 * Manages phone-side transfer history, backed by SharedPreferences.
 */
class PhoneTransferHistory(
    private val prefs: SharedPreferences
) {

    /** In-memory list used directly by the UI (last 100 entries). */
    var logs by mutableStateOf(loadLogs())
        private set

    /** Loads transfer logs from SharedPreferences. */
    private fun loadLogs(): List<TransferLog> {
        val s = prefs.getString("phone_logs", "[]") ?: "[]"
        val arr = org.json.JSONArray(s)
        val out = mutableListOf<TransferLog>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += TransferLog(
                dir = if (o.optString("dir") == "OUT") Direction.OUT else Direction.IN,
                name = o.optString("name", "file"),
                size = o.optLong("size", -1),
                success = o.optBoolean("success", false),
                time = o.optLong("time", System.currentTimeMillis())
            )
        }
        return out
    }

    /** Persists the latest logs to SharedPreferences (max 100 entries). */
    private fun saveLogs() {
        val arr = org.json.JSONArray()
        logs.takeLast(100).forEach {
            arr.put(
                org.json.JSONObject()
                    .put("dir", if (it.dir == Direction.OUT) "OUT" else "IN")
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("success", it.success)
                    .put("time", it.time)
            )
        }
        prefs.edit { putString("phone_logs", arr.toString()) }
    }

    /** Appends a new log entry and updates both memory + disk. */
    fun add(log: TransferLog) {
        logs = logs + log
        saveLogs()
    }
}