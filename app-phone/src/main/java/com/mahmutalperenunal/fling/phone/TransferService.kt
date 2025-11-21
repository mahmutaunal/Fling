package com.mahmutalperenunal.fling.phone

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.mahmutalperenunal.fling.core.NearbyProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import androidx.core.content.edit

/**
 * Foreground service on the phone side that sends files to the TV.
 * Uses Google Nearby Connections to discover the TV and stream file payloads.
 */
class TransferService : Service() {

    private val tag = "Fling-Service"
    private val channelId = "transfer"
    private val notifId = 42

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    /** Direction of a transfer on the phone side. */
    private enum class Direction { IN, OUT }

    /** Simple transfer log model stored in SharedPreferences. */
    private data class TransferLog(
        val dir: Direction,
        val name: String,
        val size: Long,
        val success: Boolean,
        val time: Long
    )

    /** Loads the last transfer logs for the phone from SharedPreferences. */
    private fun loadPhoneLogs(): List<TransferLog> {
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

    private fun savePhoneLogs(logs: List<TransferLog>) {
        val arr = JSONArray()
        logs.takeLast(100).forEach {
            arr.put(
                JSONObject()
                    .put("dir", if (it.dir == Direction.OUT) "OUT" else "IN")
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("success", it.success)
                    .put("time", it.time)
            )
        }
        prefs.edit { putString("phone_logs", arr.toString()) }
    }

    /** Convenience helper to append a single log entry from the service side. */
    private fun addPhoneLogFromService(log: TransferLog) {
        val current = loadPhoneLogs()
        savePhoneLogs(current + log)
    }

    private val strategy = Strategy.P2P_POINT_TO_POINT
    private var endpointId: String? = null
    private var uris: List<Uri> = emptyList()

    private var totalBytes: Long = 0L
    private var transferredBytes: Long = 0L
    private val payloadIdToSize = mutableMapOf<Long, Long>()
    private val payloadIdToName = mutableMapOf<Long, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(notifId, buildNotification(progress = 0, indeterminate = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        uris = intent?.getParcelableArrayListExtra("uris") ?: emptyList()
        if (uris.isEmpty()) {
            stopSelfSafely(getString(R.string.transfer_no_uris))
            return START_NOT_STICKY
        }
        // Collect basic metadata for each URI
        val files = uris.map { queryMeta(it) }
        totalBytes = files.sumOf { if (it.size > 0) it.size else 0L }
        // Start discovery → connect → send
        startDiscovery(files)
        return START_NOT_STICKY
    }

    // ---------- Nearby ----------
    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == NearbyProtocol.SERVICE_ID) {
                Nearby.getConnectionsClient(this@TransferService)
                    .requestConnection("Phone", id, connectionCb)
                    .addOnSuccessListener { log("requestConnection sent") }
                    .addOnFailureListener { e -> fail("requestConnection failed", e) }
            }
        }

        override fun onEndpointLost(id: String) {}
    }

    private val connectionCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(this@TransferService)
                .acceptConnection(id, payloadCb)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                endpointId = id
                sendAll()
            } else {
                fail("Connection failed: ${result.status.statusCode}", null)
            }
        }

        override fun onDisconnected(id: String) {
            endpointId = null
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val msg = payload.asBytes()!!.decodeToString()
                runCatching {
                    val jo = JSONObject(msg)
                    if (jo.optString("type") == "ACK") {
                        val pid = jo.optLong("payloadId", -1L)
                        val ok = jo.optBoolean("ok", false)
                        val name = jo.optString("name", payloadIdToName[pid] ?: "file")
                        if (ok) {
                            // Lightweight info only; overall progress is still based on SUCCESS updates.
                            updateNotification(
                                indeterminate = false,
                                sub = getString(R.string.transfer_ack_ok, name)
                            )
                        } else {
                            updateNotification(
                                indeterminate = false,
                                sub = getString(R.string.transfer_ack_error, name)
                            )
                        }
                    }
                }.onFailure {
                    log("BYTES from TV: $msg")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val pid = update.payloadId
            val pSize = payloadIdToSize[pid] ?: -1L
            // Estimate per-payload progress based on the bytes transferred so far.
            val pctPerPayload = if (update.totalBytes > 0) {
                (100 * update.bytesTransferred / update.totalBytes).toInt()
            } else -1

            val totalPct = if (totalBytes > 0) {
                val successBytes = transferredBytes
                val activeBytes = if (update.totalBytes > 0) update.bytesTransferred else 0L
                val pct =
                    (((successBytes + activeBytes).coerceAtLeast(0L) * 100L) / totalBytes).toInt()
                pct.coerceIn(0, 99)
            } else {
                0
            }

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    updateNotification(
                        progress = totalPct,
                        indeterminate = false,
                        sub = getString(
                            R.string.transfer_payload_progress,
                            pid,
                            pctPerPayload.coerceAtLeast(0)
                        )
                    )
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    // This payload is finished → add its size to the global total
                    transferredBytes += pSize.coerceAtLeast(update.totalBytes)
                    val pct = if (totalBytes > 0) ((transferredBytes * 100L) / totalBytes).toInt()
                        .coerceIn(0, 100) else 100
                    val displayName =
                        payloadIdToName[pid] ?: getString(R.string.transfer_fallback_filename)
                    updateNotification(
                        progress = pct,
                        indeterminate = false,
                        sub = getString(R.string.transfer_completed_file, displayName)
                    )
                    if (pct >= 100) {
                        done()
                    }
                }

                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    fail("Payload Error: $pid", null)
                }

                else -> {}
            }
        }
    }

    /**
     * Starts Nearby discovery and, once a matching endpoint is found,
     * connects and begins sending the provided files.
     */
    private fun startDiscovery(files: List<FileMeta>) {
        this.files = files
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(this).startDiscovery(
            NearbyProtocol.SERVICE_ID, discoveryCb, options
        ).addOnSuccessListener {
            log("Discovery started")
            updateNotification(
                indeterminate = true,
                sub = getString(R.string.transfer_searching_device)
            )
        }
            .addOnFailureListener { e -> fail("Discovery failed", e) }
    }

    private lateinit var files: List<FileMeta>

    private fun sendAll() {
        val id = endpointId ?: return

        // 1) Send HEADER with metadata for all files
        val arr = JSONArray()
        files.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("size", it.size)
                    .put("mime", it.mime ?: "application/octet-stream")
            )
        }
        val header =
            JSONObject().put("type", "HEADER").put("count", files.size).put("files", arr).toString()
        Nearby.getConnectionsClient(this)
            .sendPayload(id, Payload.fromBytes(header.encodeToByteArray()))

        // 2) For each file: send FILE_META then the actual file payload
        files.forEach { meta ->
            try {
                val pfd = contentResolver.openFileDescriptor(meta.uri, "r")
                    ?: throw FileNotFoundException("openFileDescriptor null")
                val filePayload = Payload.fromFile(pfd)
                val pid = filePayload.id
                payloadIdToSize[pid] = meta.size
                payloadIdToName[pid] = meta.name

                val metaJson = JSONObject()
                    .put("type", "FILE_META")
                    .put("payloadId", pid)
                    .put("name", meta.name)
                    .put("size", meta.size)
                    .put("mime", meta.mime ?: "application/octet-stream")
                    .toString()

                Nearby.getConnectionsClient(this)
                    .sendPayload(id, Payload.fromBytes(metaJson.encodeToByteArray()))
                Nearby.getConnectionsClient(this)
                    .sendPayload(id, filePayload)

            } catch (t: Throwable) {
                fail(getString(R.string.transfer_send_failed, meta.name), t)
            }
        }
        updateNotification(
            indeterminate = false,
            progress = if (totalBytes > 0) ((transferredBytes * 100L) / totalBytes).toInt() else 0,
            sub = "Sending start…"
        )
    }

    // ---------- Meta & helpers ----------
    /**
     * Minimal metadata for a file selected from the system picker.
     */
    data class FileMeta(val uri: Uri, val name: String, val size: Long, val mime: String?)

    private fun queryMeta(uri: Uri): FileMeta {
        var name = "file"
        var size = -1L
        val mime: String? = contentResolver.getType(uri)
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (iName >= 0) name = c.getString(iName) ?: name
                if (iSize >= 0) size = c.getLong(iSize)
            }
        }
        return FileMeta(uri, name, size, mime)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(
        progress: Int = 0,
        indeterminate: Boolean = false,
        sub: String? = null
    ): Notification {
        val cancelIntent = Intent(this, TransferService::class.java).apply { action = "CANCEL" }
        val pi = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.transfer_title_sending))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(0, getString(R.string.transfer_action_cancel), pi)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
            builder.setContentText(sub ?: getString(R.string.transfer_preparing))
        } else {
            val safeProgress = progress.coerceIn(0, 100)
            builder.setProgress(100, safeProgress, false)
            builder.setContentText(
                sub ?: getString(R.string.transfer_progress_percent, safeProgress)
            )
        }
        return builder.build()
    }

    private fun updateNotification(
        progress: Int = 0,
        indeterminate: Boolean = false,
        sub: String? = null
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, buildNotification(progress, indeterminate, sub))
    }

    private fun done() {
        updateNotification(
            progress = 100,
            indeterminate = false,
            sub = getString(R.string.transfer_done)
        )
        stopSelfSafely()
    }

    private fun stopSelfSafely(reason: String? = null) {
        log("stopSelf: ${reason ?: ""}")
        stopForeground(STOP_FOREGROUND_DETACH) // Compatible with Android 14 foreground service behavior
        stopSelf()
    }

    private fun fail(msg: String, e: Throwable?) {
        Log.e(tag, msg, e)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.transfer_failed_title))
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        nm.notify(notifId + 1, n)
        stopSelfSafely(msg)
    }

    private fun log(s: String) = Log.d(tag, s)

    override fun onStart(intent: Intent?, startId: Int) {
        super.onStart(intent, startId)
        if (intent?.action == "CANCEL") {
            fail(getString(R.string.transfer_user_canceled), null)
        }
    }
}