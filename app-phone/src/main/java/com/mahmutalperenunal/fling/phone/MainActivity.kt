package com.mahmutalperenunal.fling.phone

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.mahmutalperenunal.fling.core.NearbyProtocol
import com.mahmutalperenunal.fling.phone.lan.LanClient
import com.mahmutalperenunal.fling.phone.lan.LanServer
import com.mahmutalperenunal.fling.phone.ui.PhoneHomeScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.mahmutalperenunal.fling.phone.ui.theme.FlingTheme

/**
 * Main activity for the phone app.
 *
 * - Handles Nearby discovery/connection and LAN helpers
 * - Manages transfer history and SAF target folder
 * - Wires all state into the Compose UI (PhoneHomeScreen)
 */
class MainActivity : ComponentActivity() {

    // Core Nearby configuration
    private val tag = "Fling-Phone"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private var connectedEndpointId: String? = null

    // UI scope for launching coroutines from callbacks
    private val uiScope = MainScope()

    // LAN server state for TV → Phone transfers
    private var lanServer: LanServer? = null
    private var lanServerRunning by mutableStateOf(false)

    // ===== Preferences & device identity =====

    private lateinit var prefs: SharedPreferences

    /** Phone-side transfer history used by the UI. */
    private lateinit var phoneHistory: PhoneTransferHistory

    private fun getOrCreateDeviceId(): String {
        val k = "device_id"
        val cur = prefs.getString(k, null)
        if (cur != null) return cur
        val gen = UUID.randomUUID().toString()
        prefs.edit { putString(k, gen) }
        return gen
    }

    /** Stable identifier for this phone used in Nearby/LAN handshakes. */
    private val myDeviceId by lazy { getOrCreateDeviceId() }

    private fun loadTrusted(): MutableSet<String> {
        val s = prefs.getString("trusted_tv_ids", "") ?: ""
        return if (s.isBlank()) mutableSetOf() else s.split(",").filter { it.isNotBlank() }
            .toMutableSet()
    }

    private fun saveTrusted() {
        prefs.edit { putString("trusted_tv_ids", trustedTvIds.joinToString(",")) }
    }

    /** Set of TV ids that were approved at least once by the user. */
    private lateinit var trustedTvIds: MutableSet<String>

    // Last HELLO info from TV for trust / confirmation decision
    private var lastTvDeviceId: String? = null
    private var lastTvName: String? = null

    // Authentication token for the current Nearby session
    private var currentToken: String? = null

    // Simple PIN dialog state (if you later add a UI dialog)
    private var showPinDialog by mutableStateOf(false)

    // ===== SAF target folder & incoming progress =====

    // Folder where incoming files from TV will be stored
    private var targetTreeUri by mutableStateOf<Uri?>(null)

    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit { putString("phone_tree", uri.toString()) }
                targetTreeUri = uri
                lastMessage.value = getString(R.string.target_folder_set)
            }
        }

    private fun ensureTargetDirChosenPhone() {
        val saved = prefs.getString("phone_tree", null)
        targetTreeUri = saved?.toUri()
    }

    // Total progress for TV → Phone transfers (Nearby or LAN)
    private var totalBytesExpectedIn by mutableLongStateOf(0L)
    private var totalBytesCompletedIn by mutableLongStateOf(0L)
    private var filesExpectedIn by mutableIntStateOf(0)
    private var filesReceivedIn by mutableIntStateOf(0)
    private val inFlightIn = mutableMapOf<Long, Long>()
    private val metaIn =
        mutableMapOf<Long, Triple<String, String, Long>>() // id -> (name,mime,size)

    // ===== Permissions & notification helpers =====

    private val requiredPerms by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user can retry from UI */ }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ===== Nearby discovery & connection callbacks =====

    private var discovering = mutableStateOf(false)
    private val lastMessage = mutableStateOf("—")

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(tag, "Found: ${info.endpointName} (${info.serviceId})")
            Nearby.getConnectionsClient(this@MainActivity).requestConnection(
                "Phone", endpointId, connectionLifecycle
            ).addOnSuccessListener { Log.d(tag, "requestConnection sent") }
                .addOnFailureListener { Log.e(tag, "requestConnection failed", it) }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(tag, "Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Receive authentication token for this session (same as on TV)
            currentToken = info.authenticationToken
            // Accept connection in order to receive payloads
            Nearby.getConnectionsClient(this@MainActivity)
                .acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                // Send our HELLO message
                val hello = JSONObject()
                    .put("type", "HELLO")
                    .put("deviceId", myDeviceId)
                    .put("deviceName", "Phone")
                    .toString()
                Nearby.getConnectionsClient(this@MainActivity)
                    .sendPayload(endpointId, Payload.fromBytes(hello.encodeToByteArray()))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) connectedEndpointId = null
            currentToken = null
            showPinDialog = false
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val msg = payload.asBytes()!!.decodeToString()
                    runCatching {
                        val jo = JSONObject(msg)
                        when (jo.optString("type")) {
                            "HELLO" -> {
                                lastTvDeviceId = jo.optString("deviceId", "")
                                lastTvName = jo.optString("deviceName", "TV-Box")
                                val token = currentToken ?: ""
                                val isTrusted =
                                    !lastTvDeviceId.isNullOrEmpty() && trustedTvIds.contains(
                                        lastTvDeviceId
                                    )
                                if (isTrusted) sendConfirm(true, token) else showPinDialog = true
                                return
                            }

                            "HEADER" -> {
                                filesExpectedIn = jo.optInt("count", 0)
                                totalBytesExpectedIn = sumSizesFromHeader(jo.optJSONArray("files"))
                                totalBytesCompletedIn = 0L
                                inFlightIn.clear()
                                lastMessage.value = "TV → Phone: $filesExpectedIn"
                                return
                            }

                            "FILE_META" -> {
                                val pid = jo.getLong("payloadId")
                                val name = jo.getString("name")
                                val size = jo.optLong("size", -1L)
                                val mime =
                                    jo.optString("mime", "application/octet-stream")
                                metaIn[pid] = Triple(name, mime, size)
                                return
                            }
                        }
                    }.onFailure {
                        // Not JSON, just log for debugging
                        Log.d(tag, "BYTES from TV: $msg")
                    }
                }

                Payload.Type.FILE -> {
                    val tree = targetTreeUri ?: run {
                        lastMessage.value = getString(R.string.target_folder_not_selected)
                        return
                    }
                    val received = payload.asFile() ?: return
                    val pfd = received.asParcelFileDescriptor()
                    val ins: InputStream =
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)

                    val pid = payload.id
                    val meta = metaIn[pid]
                    val name =
                        meta?.first ?: "received_${System.currentTimeMillis()}"
                    val mime = meta?.second ?: "application/octet-stream"
                    val unique = resolveUniqueNamePhone(tree, name)
                    val docUri = android.provider.DocumentsContract.createDocument(
                        contentResolver,
                        tree,
                        mime,
                        unique
                    )
                    if (docUri == null) {
                        lastMessage.value = getString(R.string.document_create_failed)
                        return
                    }
                    val out: OutputStream? =
                        contentResolver.openOutputStream(docUri)
                    if (out == null) {
                        lastMessage.value = getString(R.string.write_open_failed)
                        return
                    }

                    ins.use { i -> out.use { o -> i.copyTo(o, 64 * 1024) } }
                    filesReceivedIn++
                    metaIn.remove(pid)
                    inFlightIn.remove(pid)
                    lastMessage.value = getString(
                        R.string.saved_progress,
                        filesReceivedIn,
                        filesExpectedIn,
                        unique
                    )

                    // Append to transfer history
                    phoneHistory.add(
                        TransferLog(
                            dir = Direction.IN,
                            name = unique,
                            size = meta?.third!!,
                            success = true,
                            time = System.currentTimeMillis()
                        )
                    )

                    val ack = JSONObject()
                        .put("type", "ACK")
                        .put("payloadId", pid)
                        .put("ok", true)
                        .put("name", unique)
                        .toString()
                    val ep = connectedEndpointId
                    if (ep != null) {
                        Nearby.getConnectionsClient(this@MainActivity)
                            .sendPayload(ep, Payload.fromBytes(ack.encodeToByteArray()))
                    }
                }

                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            val pid = update.payloadId
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val bytesNow =
                        if (update.totalBytes > 0) update.bytesTransferred else 0L
                    inFlightIn[pid] = bytesNow
                    val pct =
                        if (update.totalBytes > 0) (100 * update.bytesTransferred / update.totalBytes).toInt() else -1
                    if (pct >= 0) lastMessage.value =
                        "TV → Phone id=$pid: %$pct"
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    val declared = metaIn[pid]?.third ?: -1L
                    val completed = when {
                        declared > 0 -> declared
                        update.totalBytes > 0 -> update.totalBytes
                        else -> inFlightIn[pid] ?: 0L
                    }
                    totalBytesCompletedIn += completed
                    inFlightIn.remove(pid)
                }

                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    inFlightIn.remove(pid)
                    lastMessage.value = getString(
                        R.string.tv_phone_transfer_error,
                        pid.toString()
                    )
                }

                else -> Unit
            }
        }
    }

    private fun sendConfirm(agree: Boolean, token: String) {
        val id = connectedEndpointId ?: return
        val confirm = JSONObject()
            .put("type", "CONFIRM")
            .put("agree", agree)
            .put("token", token)
            .toString()
        Nearby.getConnectionsClient(this)
            .sendPayload(id, Payload.fromBytes(confirm.encodeToByteArray()))
            .addOnSuccessListener {
                if (agree) {
                    // First time trust: persist TV id
                    val tvId = lastTvDeviceId
                    if (!tvId.isNullOrEmpty()) {
                        trustedTvIds.add(tvId)
                        saveTrusted()
                    }
                    showPinDialog = false
                }
            }
    }

    // ===== Share intents & foreground transfer service =====

    private var pendingUris: MutableList<Uri> = mutableListOf()

    private fun extractShareUris(intent: Intent?) {
        pendingUris.clear()
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                    pendingUris.add(it)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list =
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) {
                    pendingUris.addAll(list)
                } else {
                    val clip: ClipData? = intent.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { pendingUris.add(it) }
                        }
                    }
                }
            }
        }
        Log.d(tag, "Extracted ${pendingUris.size} URIs from share intent")
    }

    private data class FileMeta(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String?
    )

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

    private fun startDiscovery() {
        permLauncher.launch(requiredPerms)
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        Nearby.getConnectionsClient(this).startDiscovery(
            NearbyProtocol.SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            discovering.value = true
            Log.d(tag, "Discovery started")
        }.addOnFailureListener {
            discovering.value = false
            Log.e(tag, "Discovery failed", it)
        }
    }

    private fun stopDiscovery() {
        Nearby.getConnectionsClient(this).stopDiscovery()
        discovering.value = false
    }

    private fun sendHeaderAndFiles(files: List<FileMeta>) {
        val id = connectedEndpointId ?: run {
            lastMessage.value = getString(R.string.connect_tv_first)
            return
        }

        // 1) HEADER (BYTES): summary for all files
        val arr = JSONArray()
        files.forEach {
            val obj = JSONObject()
                .put("name", it.name)
                .put("size", it.size)
                .put("mime", it.mime ?: "application/octet-stream")
            arr.put(obj)
        }
        val header = JSONObject()
            .put("type", "HEADER")
            .put("count", files.size)
            .put("files", arr)
            .toString()

        Nearby.getConnectionsClient(this)
            .sendPayload(id, Payload.fromBytes(header.encodeToByteArray()))
            .addOnSuccessListener { Log.d(tag, "HEADER sent") }
            .addOnFailureListener { e ->
                Log.e(tag, "HEADER failed", e)
                lastMessage.value = getString(R.string.header_send_failed)
            }

        // 2) For each file: first FILE_META (BYTES), then FILE (FILE)
        files.forEach { meta ->
            try {
                val pfd = contentResolver.openFileDescriptor(meta.uri, "r")
                    ?: throw FileNotFoundException("openFileDescriptor null")
                val filePayload = Payload.fromFile(pfd)
                val payloadId = filePayload.id

                val fileMetaJson = JSONObject()
                    .put("type", "FILE_META")
                    .put("payloadId", payloadId)
                    .put("name", meta.name)
                    .put("size", meta.size)
                    .put("mime", meta.mime ?: "application/octet-stream")
                    .toString()

                // Send meta first, then FILE payload
                Nearby.getConnectionsClient(this)
                    .sendPayload(id, Payload.fromBytes(fileMetaJson.encodeToByteArray()))
                    .addOnSuccessListener {
                        Log.d(tag, "FILE_META sent: ${meta.name} (id=$payloadId)")
                    }
                    .addOnFailureListener {
                        Log.e(tag, "FILE_META failed: ${meta.name}", it)
                    }

                Nearby.getConnectionsClient(this)
                    .sendPayload(id, filePayload)
                    .addOnSuccessListener { Log.d(tag, "FILE sent: ${meta.name}") }
                    .addOnFailureListener {
                        Log.e(tag, "FILE failed: ${meta.name}", it)
                    }
            } catch (t: Throwable) {
                Log.e(tag, "Failed to send: ${meta.name}", t)
            }
        }
        lastMessage.value = getString(
            R.string.transfer_started_count,
            files.size
        )
    }

    private fun maybeSendSharedFiles() {
        if (pendingUris.isEmpty()) return
        val metas = pendingUris.map { queryMeta(it) }
        sendHeaderAndFiles(metas)
        pendingUris.clear()
    }

    private fun startTransferServiceIfAny() {
        if (pendingUris.isEmpty()) return
        val i = Intent(this, TransferService::class.java).apply {
            putParcelableArrayListExtra("uris", ArrayList(pendingUris))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        }
        lastMessage.value = getString(
            R.string.background_transfer_started,
            pendingUris.size
        )
        pendingUris.clear()
    }

    private fun startLanPhoneServer() {
        val tree = targetTreeUri ?: run {
            lastMessage.value = getString(R.string.select_phone_target_folder_first)
            return
        }

        lanServer = LanServer(
            ctx = this,
            deviceId = myDeviceId,
            deviceName = "Phone",
            isTrusted = { remoteId ->
                trustedTvIds.contains(remoteId)
            },
            trustNow = { remoteId ->
                trustedTvIds.add(remoteId)
                saveTrusted()
            },
            onPinRequired = { pin ->
                lastMessage.value = getString(
                    R.string.lan_pin_with_hint,
                    pin
                )
            },
            onHeader = { count, total ->
                filesExpectedIn = count
                totalBytesExpectedIn = total
                totalBytesCompletedIn = 0L
                inFlightIn.clear()
                lastMessage.value = getString(
                    R.string.lan_tv_phone_incoming,
                    count,
                    humanSize(total)
                )
            },
            onFileMeta = { pid, name, mime, size ->
                metaIn[pid] = Triple(name, mime, size)
            },
            onFileData = { pid, length, input ->
                val meta = metaIn[pid]
                val name = meta?.first ?: "received_${System.currentTimeMillis()}"
                val mime = meta?.second ?: "application/octet-stream"
                val unique = resolveUniqueNamePhone(tree, name)
                val docUri = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    tree,
                    mime,
                    unique
                )
                val out: OutputStream? =
                    contentResolver.openOutputStream(docUri!!)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                input.use { ins ->
                    out.use { o ->
                        while (remaining > 0) {
                            val r = ins.read(
                                buf,
                                0,
                                kotlin.math.min(buf.size.toLong(), remaining).toInt()
                            )
                            if (r < 0) break
                            o!!.write(buf, 0, r)
                            remaining -= r
                        }
                    }
                }
                filesReceivedIn++
                totalBytesCompletedIn += (meta?.third ?: length)
                metaIn.remove(pid)
                inFlightIn.remove(pid)
                unique
            },
            onAckSent = { _ ->
                // No-op for now; you may add extra logging here
            },
            log = { s -> Log.d("LanServerPhone", s) }
        )
        uiScope.launch {
            lanServer?.start(this)
            lanServerRunning = true
        }
    }

    private fun stopLanPhoneServer() {
        lanServer?.stop()
        lanServerRunning = false
    }

    private fun humanSize(size: Long): String {
        if (size <= 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            size >= gb -> String.format(Locale.US, "%.2f GB", size / gb)
            size >= mb -> String.format(Locale.US, "%.2f MB", size / mb)
            size >= kb -> String.format(Locale.US, "%.2f KB", size / kb)
            else -> "$size B"
        }
    }

    private fun sumSizesFromHeader(arr: JSONArray?): Long {
        if (arr == null) return 0L
        var s = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val size = o.optLong("size", -1L)
            if (size > 0) s += size
        }
        return s
    }

    private fun splitName(name: String): Pair<String, String?> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) {
            name.take(dot) to name.substring(dot + 1)
        } else {
            name to null
        }
    }

    private fun resolveUniqueNamePhone(parentTree: Uri, desiredName: String): String {
        val parent = DocumentFile.fromTreeUri(this, parentTree) ?: return desiredName
        var tryName = desiredName
        val (base, ext) = splitName(desiredName)
        var counter = 1
        while (true) {
            parent.findFile(tryName) ?: return tryName
            tryName = if (ext != null) "$base ($counter).$ext" else "$base ($counter)"
            counter++
        }
    }

    private fun sendOverLanFromPendingUris() {
        if (pendingUris.isEmpty()) {
            lastMessage.value = getString(R.string.select_file_from_share_menu_first)
            return
        }

        val metas = pendingUris.map { uri ->
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
            LanClient.FileMeta(uri, name, size, mime)
        }

        val client = LanClient(
            ctx = this,
            myDeviceId = myDeviceId,
            myDeviceName = "Phone",
            isTrustedTv = { tvId -> trustedTvIds.contains(tvId) },
            trustTvNow = { tvId ->
                trustedTvIds.add(tvId)
                saveTrusted()
            }
        )

        uiScope.launch {
            lastMessage.value = getString(R.string.lan_searching_tv)
            val ok = client.discoverAndSend(
                files = metas,
                onPinDialog = { pin ->
                    lastMessage.value = getString(
                        R.string.lan_pin_must_match_tv,
                        pin
                    )
                    true
                },
                onProgress = { msg ->
                    lastMessage.value = msg

                    val approvedPrefix = getString(R.string.approved_prefix)
                    if (msg.startsWith(approvedPrefix)) {
                        val name = msg.removePrefix(approvedPrefix).trim()
                        val meta = metas.find { it.name == name }
                        phoneHistory.add(
                            TransferLog(
                                dir = Direction.OUT,
                                name = name,
                                size = meta?.size ?: -1L,
                                success = true,
                                time = System.currentTimeMillis()
                            )
                        )
                    }
                }
            )
            lastMessage.value = if (ok) {
                getString(R.string.lan_transfer_complete)
            } else {
                getString(R.string.lan_error_occurred)
            }
        }
    }

    @Composable
    fun SetupSystemBars() {
        val systemUiController = rememberSystemUiController()
        val useDarkIcons = !isSystemInDarkTheme()

        SideEffect {
            systemUiController.setStatusBarColor(
                color = Color.Transparent,
                darkIcons = useDarkIcons
            )
            systemUiController.setNavigationBarColor(
                color = Color.Transparent,
                darkIcons = useDarkIcons
            )
        }
    }

    // ===== Lifecycle & UI wiring =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences and dependent state after context is available
        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        phoneHistory = PhoneTransferHistory(prefs)
        trustedTvIds = loadTrusted()

        ensureTargetDirChosenPhone()
        ensureNotifPermission()
        extractShareUris(intent)

        setContent {
            FlingTheme {
                SetupSystemBars()
                PhoneHomeScreen(
                    discovering = discovering.value,
                    lanServerRunning = lanServerRunning,
                    targetTreeUri = targetTreeUri,
                    lastMessage = lastMessage.value,
                    totalBytesExpectedIn = totalBytesExpectedIn,
                    totalBytesCompletedIn = totalBytesCompletedIn,
                    filesExpectedIn = filesExpectedIn,
                    filesReceivedIn = filesReceivedIn,
                    phoneLogs = phoneHistory.logs,
                    onToggleNearbyDiscovery = {
                        if (discovering.value) stopDiscovery() else startDiscovery()
                    },
                    onSendLanFromPending = { sendOverLanFromPendingUris() },
                    onPickTargetFolder = { pickDir.launch(targetTreeUri) },
                    onToggleLanServer = {
                        if (lanServerRunning) stopLanPhoneServer() else startLanPhoneServer()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShareUris(intent)
        maybeSendSharedFiles()
        startTransferServiceIfAny()
    }
}