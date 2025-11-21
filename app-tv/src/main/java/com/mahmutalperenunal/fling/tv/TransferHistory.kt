package com.mahmutalperenunal.fling.tv

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Direction of a transfer on the TV side. */
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
 * Manages TV-side transfer history, backed by SharedPreferences.
 */
class TvTransferHistory(
    private val prefs: SharedPreferences
) {

    /** In-memory list used directly by the UI (last 100 entries). */
    var logs by mutableStateOf(loadLogs())
        private set

    /** Loads transfer logs from SharedPreferences. */
    private fun loadLogs(): List<TransferLog> {
        val s = prefs.getString("logs", "[]") ?: "[]"
        val arr = JSONArray(s)
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
        val arr = JSONArray()
        logs.takeLast(100).forEach { log ->
            arr.put(
                JSONObject()
                    .put("dir", if (log.dir == Direction.OUT) "OUT" else "IN")
                    .put("name", log.name)
                    .put("size", log.size)
                    .put("success", log.success)
                    .put("time", log.time)
            )
        }
        prefs.edit { putString("logs", arr.toString()) }
    }

    /** Appends a new log entry and updates both memory + disk. */
    fun add(log: TransferLog) {
        logs = logs + log
        saveLogs()
    }
}