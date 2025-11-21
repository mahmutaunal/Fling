package com.mahmutalperenunal.fling.tv

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.mahmutalperenunal.fling.core.NearbyProtocol
import com.mahmutalperenunal.fling.tv.lan.LanClientTv
import com.mahmutalperenunal.fling.tv.lan.LanServer
import com.mahmutalperenunal.fling.tv.ui.TvHomeScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.max
import androidx.core.content.edit
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    private val tag = "TVDrop-TV"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private lateinit var prefs: SharedPreferences

    // Shared PIN dialog state
    private var showPinDialog by mutableStateOf(false)
    private var pinValue by mutableStateOf<String?>(null)
    private var pinSource by mutableStateOf<PinSource?>(null)

    // For LAN_OUT (TV -> Phone) we return a boolean decision via callback
    private var pinDecisionCallback: ((Boolean) -> Unit)? = null

    // ===== Permissions =====
    private val requiredPerms by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // ===== SAF target folder (receiver) =====
    private var targetTreeUri by mutableStateOf<Uri?>(null)
    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit { putString("tree", uri.toString()) }
                targetTreeUri = uri
                uiFolder = uri.toString()
            }
        }

    private fun ensureTargetDirChosen() {
        val saved = prefs.getString("tree", null)
        targetTreeUri = saved?.toUri()
    }

    // ===== Identity & trust =====
    private fun getOrCreateDeviceId(): String {
        val k = "device_id"
        val cur = prefs.getString(k, null)
        if (cur != null) return cur
        val gen = UUID.randomUUID().toString()
        prefs.edit { putString(k, gen) }
        return gen
    }

    private val myDeviceId by lazy { getOrCreateDeviceId() }

    private fun loadTrusted(): MutableMap<String, String> {
        val s = prefs.getString("trusted_map", "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        runCatching {
            val jo = JSONObject(s)
            jo.keys().forEach { k -> map[k] = jo.optString(k) }
        }
        return map
    }

    private fun saveTrusted() {
        val jo = JSONObject()
        trustedMap.forEach { (k, v) -> jo.put(k, v) }
        prefs.edit { putString("trusted_map", jo.toString()) }
    }

    private lateinit var trustedMap: MutableMap<String, String>

    // ===== UI state =====
    private var advertising by mutableStateOf(false)
    private var uiStatus by mutableStateOf("—")
    private var uiFolder by mutableStateOf("—")
    private var currentToken by mutableStateOf<String?>(null)
    private var pendingEndpointId: String? = null
    private var pendingTrustedDecisionDone = false
    private var lastHelloDeviceId: String? = null
    private var lastHelloDeviceName: String? = null

    // ===== Transfer progress (receiver) =====
    private var filesExpected = 0
    private var filesReceived = 0

    data class FileMeta(val name: String, val mime: String, val size: Long)

    private val metaMap = ConcurrentHashMap<Long, FileMeta>()
    private var totalBytesExpected by mutableLongStateOf(0L)
    private var totalBytesCompleted by mutableLongStateOf(0L)
    private val inFlightBytes = ConcurrentHashMap<Long, Long>()
    private val progressText: String
        get() {
            val inFlightSum = inFlightBytes.values.sum()
            val denom = max(totalBytesExpected, 1L)
            val pct =
                (((totalBytesCompleted + inFlightSum) * 100L) / denom).toInt().coerceIn(0, 100)
            return "$pct%"
        }
    private val progressFloat: Float
        get() {
            val inFlightSum = inFlightBytes.values.sum()
            val denom = max(totalBytesExpected, 1L).toFloat()
            return ((totalBytesCompleted + inFlightSum).toFloat() / denom).coerceIn(0f, 1f)
        }

    // ===== TV → Phone SEND (outgoing) =====
    private var selectedUris: List<Uri> = emptyList()
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach { u ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                selectedUris = uris
                sendFilesToPhone()
            }
        }

    data class OutMeta(val uri: Uri, val name: String, val size: Long, val mime: String?)

    private fun queryMeta(uri: Uri): OutMeta {
        var name = "file"
        var size = -1L
        val mime = contentResolver.getType(uri)
        contentResolver.query(
            uri,
            arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE
            ),
            null,
            null,
            null
        )?.use { c ->
            val iName = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (iName >= 0) name = c.getString(iName) ?: name
                if (iSize >= 0) size = c.getLong(iSize)
            }
        }
        return OutMeta(uri, name, size, mime)
    }

    // ===== “Last Transfers” log (TV side) =====
    private val tvHistory by lazy { TvTransferHistory(prefs) }

    private val outPending = ConcurrentHashMap<Long, Pair<String, Long>>()

    private val uiScope = MainScope()
    private var lanServer: LanServer? = null
    private var lanRunning by mutableStateOf(false)

    // ===== Helpers =====
    private fun splitName(name: String): Pair<String, String?> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) {
            name.take(dot) to name.substring(dot + 1)
        } else name to null
    }

    private fun resolveUniqueName(parentTree: Uri, desiredName: String): String {
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

    private fun createDoc(parentTree: Uri, mime: String, name: String): Uri? {
        val unique = resolveUniqueName(parentTree, name)
        return DocumentsContract.createDocument(contentResolver, parentTree, mime, unique)
    }

    private fun sumSizesFromHeader(arr: JSONArray?): Long {
        if (arr == null) return 0L
        var sum = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optLong("size", -1L)
            if (s > 0) sum += s
        }
        return sum
    }

    // ===== Payload callback (receiver + PIN) =====
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val msg = payload.asBytes()!!.decodeToString()
                    runCatching {
                        val jo = JSONObject(msg)
                        when (jo.optString("type")) {
                            "HELLO" -> {
                                lastHelloDeviceId = jo.optString("deviceId", "")
                                lastHelloDeviceName = jo.optString("deviceName", "Phone")
                                val displayName = lastHelloDeviceName ?: "Phone"
                                if (trustedMap.containsKey(lastHelloDeviceId)) {
                                    pendingTrustedDecisionDone = true
                                    uiStatus = getString(R.string.tv_status_trusted_named, displayName)
                                } else {
                                    uiStatus = getString(R.string.tv_status_pairing_pending_named, displayName)
                                }
                                return
                            }

                            "CONFIRM" -> {
                                val agree = jo.optBoolean("agree", false)
                                val tokenFromPhone = jo.optString("token", "")
                                val token = currentToken ?: ""
                                if (!pendingTrustedDecisionDone) {
                                    if (agree && tokenFromPhone == token && token.isNotEmpty()) {
                                        val devId = lastHelloDeviceId
                                        val devName = lastHelloDeviceName ?: "Phone"
                                        if (!devId.isNullOrEmpty()) {
                                            trustedMap[devId] = devName
                                            saveTrusted()
                                            uiStatus = getString(R.string.tv_status_paired_and_trusted_named, devName)
                                        } else {
                                            uiStatus = getString(R.string.tv_status_paired)
                                        }
                                        pendingTrustedDecisionDone = true
                                    } else {
                                        uiStatus = getString(R.string.tv_status_pin_mismatch_disconnected)
                                        pendingEndpointId?.let {
                                            Nearby.getConnectionsClient(this@MainActivity)
                                                .disconnectFromEndpoint(it)
                                        }
                                        currentToken = null
                                        pendingEndpointId = null
                                    }
                                }
                                return
                            }

                            "HEADER" -> {
                                filesExpected = jo.optInt("count", 0)
                                totalBytesExpected = sumSizesFromHeader(jo.optJSONArray("files"))
                                totalBytesCompleted = 0L
                                inFlightBytes.clear()
                                uiStatus = getString(R.string.tv_status_header_received_count, filesExpected)
                                Nearby.getConnectionsClient(this@MainActivity)
                                    .sendPayload(
                                        endpointId,
                                        Payload.fromBytes("HEADER_OK".encodeToByteArray())
                                    )
                                return
                            }

                            "FILE_META" -> {
                                val pid = jo.getLong("payloadId")
                                val name = jo.getString("name")
                                val size = jo.optLong("size", -1L)
                                val mime = jo.optString("mime", "application/octet-stream")
                                metaMap[pid] = FileMeta(name, mime, size)
                                return
                            }

                            "ACK" -> {
                                val pid = jo.optLong("payloadId", -1L)
                                val ok = jo.optBoolean("ok", false)
                                val nameFromAck = jo.optString("name", null)
                                val pending = outPending.remove(pid)
                                val displayName = nameFromAck ?: pending?.first ?: "file"
                                val size = pending?.second ?: -1L

                                tvHistory.add(
                                    TransferLog(
                                        dir = Direction.OUT,
                                        name = displayName,
                                        size = size,
                                        success = ok,
                                        time = System.currentTimeMillis()
                                    )
                                )
                                uiStatus =
                                    if (ok) "ACK OK: $displayName" else "ACK Fail: $displayName"
                                return
                            }
                        }
                    }.onFailure {
                        if (msg == NearbyProtocol.PING) {
                            Nearby.getConnectionsClient(this@MainActivity)
                                .sendPayload(
                                    endpointId,
                                    Payload.fromBytes(NearbyProtocol.PONG.encodeToByteArray())
                                )
                        }
                    }
                }

                Payload.Type.FILE -> {
                    val tree = targetTreeUri ?: run {
                        uiStatus = getString(R.string.tv_status_target_folder_not_selected)
                        return
                    }
                    val received = payload.asFile() ?: return
                    val pfd = received.asParcelFileDescriptor()
                    val ins: InputStream =
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)

                    val pid = payload.id
                    val meta = metaMap[pid]
                    val name = meta?.name ?: "received_${System.currentTimeMillis()}"
                    val mime = meta?.mime ?: "application/octet-stream"

                    val docUri = createDoc(tree, mime, name) ?: run {
                        uiStatus = getString(R.string.document_create_failed)
                        return
                    }
                    val out: OutputStream? = contentResolver.openOutputStream(docUri)
                    if (out == null) {
                        uiStatus = getString(R.string.write_open_failed)
                        return
                    }

                    ins.use { i -> out.use { o -> i.copyTo(o, 64 * 1024) } }
                    filesReceived++
                    metaMap.remove(pid)
                    inFlightBytes.remove(pid)
                    uiStatus = getString(R.string.saved_progress, filesReceived, filesExpected, name)
                    tvHistory.add(
                        TransferLog(
                            dir = Direction.IN,
                            name = name,
                            size = meta?.size ?: -1,
                            success = true,
                            time = System.currentTimeMillis()
                        )
                    )

                    val ack = JSONObject()
                        .put("type", "ACK")
                        .put("payloadId", pid)
                        .put("ok", true)
                        .put("name", name)
                        .toString()
                    Nearby.getConnectionsClient(this@MainActivity)
                        .sendPayload(endpointId, Payload.fromBytes(ack.encodeToByteArray()))
                }

                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val pid = update.payloadId
            val declaredSize = metaMap[pid]?.size ?: -1L
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val bytesNow =
                        if (update.totalBytes > 0) update.bytesTransferred else 0L
                    inFlightBytes[pid] = bytesNow
                    if (update.totalBytes > 0) {
                        val percent = (100 * update.bytesTransferred / update.totalBytes).toInt()
                        uiStatus = getString(R.string.tv_status_transfer_in_progress, pid, percent)
                    }
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    val completed = when {
                        declaredSize > 0 -> declaredSize
                        update.totalBytes > 0 -> update.totalBytes
                        else -> inFlightBytes[pid] ?: 0L
                    }
                    totalBytesCompleted += completed
                    inFlightBytes.remove(pid)
                    uiStatus = getString(R.string.tv_status_transfer_completed_progress, pid, progressText)
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    inFlightBytes.remove(pid)
                    uiStatus = getString(R.string.tv_status_transfer_error_id, pid)
                }

                else -> Unit
            }
        }
    }

    // ===== Connection lifecycle =====
    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val token = info.authenticationToken
            currentToken = token
            pendingEndpointId = endpointId
            pendingTrustedDecisionDone = false

            Nearby.getConnectionsClient(this@MainActivity)
                .acceptConnection(endpointId, payloadCallback)

            // Show PIN as info to the user
            pinValue = token
            pinSource = PinSource.NEARBY
            showPinDialog = true

            uiStatus = getString(R.string.tv_status_nearby_pin_waiting_token, token)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val hello = JSONObject()
                    .put("type", "HELLO")
                    .put("deviceId", myDeviceId)
                    .put("deviceName", "TV-Box")
                    .toString()
                Nearby.getConnectionsClient(this@MainActivity)
                    .sendPayload(endpointId, Payload.fromBytes(hello.encodeToByteArray()))
            } else {
                uiStatus = getString(R.string.tv_status_connection_failed)
            }
        }

        override fun onDisconnected(endpointId: String) {
            currentToken = null
            pendingEndpointId = null
        }
    }

    private fun startAdvertising() {
        permLauncher.launch(requiredPerms)
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(this).startAdvertising(
            "TV-Box", NearbyProtocol.SERVICE_ID, connectionLifecycle, options
        ).addOnSuccessListener {
            advertising = true
            uiStatus = getString(R.string.tv_status_advertising)
        }.addOnFailureListener {
            advertising = false
            uiStatus = getString(R.string.tv_status_advertising_failed)
            Log.e(tag, "Advertising failed", it)
        }
    }

    private fun stopAdvertising() {
        Nearby.getConnectionsClient(this).stopAdvertising()
        advertising = false
        uiStatus = getString(R.string.tv_status_idle)
    }

    // ===== TV → Phone SEND over Nearby =====
    private fun sendFilesToPhone() {
        val ep = pendingEndpointId ?: run {
            uiStatus = getString(R.string.tv_status_no_connected_phone)
            return
        }
        if (selectedUris.isEmpty()) {
            uiStatus = getString(R.string.tv_status_no_file_selected)
            return
        }

        val metas = selectedUris.map { queryMeta(it) }

        // HEADER
        val arr = JSONArray()
        metas.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("mime", it.mime ?: "application/octet-stream")
            )
        }
        val header = JSONObject()
            .put("type", "HEADER")
            .put("count", metas.size)
            .put("files", arr)
            .toString()
        Nearby.getConnectionsClient(this)
            .sendPayload(ep, Payload.fromBytes(header.encodeToByteArray()))

        // Each file: FILE_META → FILE
        metas.forEach { m ->
            val pfd = contentResolver.openFileDescriptor(m.uri, "r") ?: return@forEach
            val filePayload = Payload.fromFile(pfd)
            val pid = filePayload.id

            outPending[pid] = m.name to m.size

            val metaJson = JSONObject()
                .put("type", "FILE_META")
                .put("payloadId", pid)
                .put("name", m.name)
                .put("size", m.size)
                .put("mime", m.mime ?: "application/octet-stream")
                .toString()
            Nearby.getConnectionsClient(this)
                .sendPayload(ep, Payload.fromBytes(metaJson.encodeToByteArray()))
            Nearby.getConnectionsClient(this).sendPayload(ep, filePayload)

            // Local optimistic log entry
            tvHistory.add(
                TransferLog(
                    dir = Direction.OUT,
                    name = m.name,
                    size = m.size,
                    success = true,
                    time = System.currentTimeMillis()
                )
            )
        }
        uiStatus = getString(R.string.tv_status_send_to_phone_started_count, metas.size)
    }

    // ===== LAN server (Phone → TV) =====
    private fun startLanServer() {
        val tree = targetTreeUri ?: run {
            uiStatus = getString(R.string.tv_status_select_target_folder_first)
            return
        }
        val deviceId = myDeviceId
        val deviceName = "TV-Box"
        lanServer = LanServer(
            ctx = this,
            deviceId = deviceId,
            deviceName = deviceName,
            onPinRequired = { pin ->
                currentToken = pin
                pinValue = pin
                pinSource = PinSource.LAN_IN
                showPinDialog = true
                uiStatus = getString(R.string.tv_status_lan_pin_label, pin)
            },
            isTrusted = { remoteId -> trustedMap.containsKey(remoteId) },
            trustNow = { remoteId, remoteName ->
                trustedMap[remoteId] = remoteName
                saveTrusted()
                uiStatus = getString(R.string.tv_status_lan_paired_named, remoteName)
            },
            onHeader = { count, total ->
                filesExpected = count
                totalBytesExpected = total
                totalBytesCompleted = 0L
                inFlightBytes.clear()
                uiStatus = getString(R.string.tv_status_lan_header_count, count)
            },
            onFileMeta = { pid, name, mime, size ->
                metaMap[pid] = FileMeta(name, mime, size)
            },
            onFileData = { pid, size, input ->
                val meta = metaMap[pid]
                val name = meta?.name ?: "received_${System.currentTimeMillis()}"
                val mime = meta?.mime ?: "application/octet-stream"
                val docUri = createDoc(tree, mime, name)
                val out: OutputStream? = contentResolver.openOutputStream(docUri!!)
                var remaining = size
                val buf = ByteArray(64 * 1024)

                BufferedInputStream(input).use { i ->
                    out.use { o ->
                        while (remaining > 0) {
                            val r = i.read(
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
                filesReceived++
                totalBytesCompleted += (meta?.size ?: size)
                metaMap.remove(pid)
                inFlightBytes.remove(pid)
                name
            },
            onAckSent = { saved ->
                tvHistory.add(
                    TransferLog(
                        dir = Direction.IN,
                        name = saved,
                        size = -1,
                        success = true,
                        time = System.currentTimeMillis()
                    )
                )
            },
            log = { s -> Log.d("LanServer", s) }
        )
        lanServer!!.start(uiScope)
        lanRunning = true
    }

    private fun stopLanServer() {
        lanServer?.stop()
        lanRunning = false
        currentToken = null
    }

    // ===== TV → Phone SEND over LAN =====
    private fun sendTvToPhoneOverLan() {
        val uris = selectedUris
        if (uris.isEmpty()) {
            uiStatus = getString(R.string.tv_status_lan_select_files_first)
            return
        }

        val metas = uris.map { u ->
            var name = "file"
            var size = -1L
            val mime: String? = contentResolver.getType(u)
            contentResolver.query(
                u,
                arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.OpenableColumns.SIZE
                ),
                null, null, null
            )?.use { c ->
                val iName = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (iName >= 0) name = c.getString(iName) ?: name
                    if (iSize >= 0) size = c.getLong(iSize)
                }
            }
            LanClientTv.FileMeta(u, name, size, mime)
        }

        val client = LanClientTv(
            ctx = this,
            myDeviceId = myDeviceId,
            myDeviceName = "TV-Box",
            isTrustedPhone = { remoteId -> trustedMap.containsKey(remoteId) },
            trustPhoneNow = { remoteId ->
                trustedMap[remoteId] = "Phone"
                saveTrusted()
            }
        )

        uiScope.launch {
            uiStatus = getString(R.string.tv_status_lan_searching_phone)
            val ok = client.discoverAndSend(
                files = metas,
                onPinDialog = { pin ->
                    askUserPin(pin, PinSource.LAN_OUT)
                },
                onProgress = { msg ->
                    uiStatus = msg
                }
            )
            uiStatus = if (ok) {
                getString(R.string.tv_status_lan_transfer_complete)
            } else {
                getString(R.string.tv_status_lan_error)
            }
        }
    }

    private suspend fun askUserPin(pin: String, source: PinSource): Boolean =
        suspendCancellableCoroutine { cont ->
            pinValue = pin
            pinSource = source
            showPinDialog = true

            pinDecisionCallback = { agree ->
                showPinDialog = false
                pinValue = null
                pinSource = null
                pinDecisionCallback = null
                cont.resume(agree)
            }
        }

    // ===== UI wiring =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences and trusted map after context is available
        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        trustedMap = loadTrusted()

        ensureTargetDirChosen()
        uiFolder = targetTreeUri?.toString() ?: "—"

        setContent {
            MaterialTheme {
                TvHomeScreen(
                    advertising = advertising,
                    lanRunning = lanRunning,
                    uiStatus = uiStatus,
                    uiFolder = uiFolder,
                    totalBytesExpected = totalBytesExpected,
                    progressText = progressText,
                    progressFloat = progressFloat,
                    filesReceived = filesReceived,
                    filesExpected = filesExpected,
                    logs = tvHistory.logs,
                    showPinDialog = showPinDialog,
                    pinValue = pinValue,
                    pinSource = pinSource,
                    onToggleAdvertising = {
                        if (advertising) stopAdvertising() else startAdvertising()
                    },
                    onSendToPhone = {
                        pickFiles.launch(arrayOf("*/*"))
                        sendTvToPhoneOverLan()
                    },
                    onToggleLanServer = {
                        if (lanRunning) stopLanServer() else startLanServer()
                    },
                    onPinInfoDismiss = {
                        showPinDialog = false
                        pinValue = null
                        pinSource = null
                    },
                    onPinDecision = { agree ->
                        pinDecisionCallback?.invoke(agree)
                    }
                )
            }
        }
    }
}