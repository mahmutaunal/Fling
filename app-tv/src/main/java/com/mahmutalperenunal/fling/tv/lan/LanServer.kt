package com.mahmutalperenunal.fling.tv.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mahmutalperenunal.fling.core.LanProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * LAN server running on the TV side.
 * Listens for phone connections, handles a simple HELLO/PIN handshake,
 * and receives files over a JSON + TCP stream.
 */
class LanServer(
    ctx: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val onPinRequired: (pin: String) -> Unit,
    private val isTrusted: (remoteDeviceId: String) -> Boolean,
    private val trustNow: (remoteDeviceId: String, remoteName: String) -> Unit,
    private val onHeader: (count: Int, totalBytes: Long) -> Unit,
    private val onFileMeta: (payloadId: Long, name: String, mime: String, size: Long) -> Unit,
    private val onFileData: suspend (payloadId: Long, size: Long, input: BufferedInputStream) -> String, // returns savedName
    private val onAckSent: (name: String) -> Unit,
    private val log: (String) -> Unit
) {

    private var server: ServerSocket? = null
    private var nsdManager: NsdManager = ctx.getSystemService(NsdManager::class.java)
    private var regListener: NsdManager.RegistrationListener? = null

    /**
     * Starts the LAN server on a random free port and registers it via NSD.
     * Each incoming client is handled on a separate coroutine.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val s = ServerSocket(0) // bind to any free port
            server = s
            registerService(s.localPort)
            log("LAN server started on port ${s.localPort}")

            while (!s.isClosed) {
                val client = s.accept()
                scope.launch(Dispatchers.IO) { handleClient(client) }
            }
        }
    }

    /**
     * Stops the server and unregisters the NSD service if active.
     */
    fun stop() {
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        try {
            regListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Throwable) {
        }
    }

    // Advertises this TV as an mDNS service so the phone can discover it.
    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = LanProtocol.SERVICE_NAME_PREFIX + deviceId.takeLast(4)
            serviceType = LanProtocol.SERVICE_TYPE
            setPort(port)
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("NSD registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log("NSD reg failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                log("NSD unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log("NSD unreg failed: $errorCode")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
    }

    /**
     * Handles a single client connection:
     * performs HELLO/PIN/CONFIRM handshake and then processes incoming files.
     */
    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            val ins = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val outs = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            // 1) Wait for HELLO from the client
            val helloReq = readJson(ins)
            val rType = helloReq?.optString("type")
            if (rType != "HELLO") return
            val remoteId = helloReq.optString("deviceId")
            val remoteName = helloReq.optString("deviceName", "Phone")

            // 2) Trust check + optional PIN generation
            val trusted = isTrusted(remoteId)
            val pin = if (trusted) "" else (100000..999999).random().toString()
            if (!trusted) onPinRequired(pin)

            // TV responds with HELLO (including PIN if needed)
            writeJson(
                outs, JSONObject()
                    .put("type", "HELLO")
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .put("pin", pin)
            )

            // If not already trusted, wait for CONFIRM from the phone
            if (!trusted) {
                val confirm = readJson(ins)
                if (confirm?.optString("type") != "CONFIRM" ||
                    !confirm.optBoolean("agree") ||
                    confirm.optString("pin") != pin
                ) {
                    // Reject: either user did not confirm or PIN mismatch
                    return
                } else {
                    trustNow(remoteId, remoteName)
                }
            }

            // Handshake complete, start receiving file metadata + data
            while (true) {
                val obj = readJson(ins) ?: break
                when (obj.optString("type")) {
                    LanProtocol.HEADER -> {
                        val arr = obj.optJSONArray("files")
                        val count = obj.optInt("count", arr?.length() ?: 0)
                        onHeader(count, sumSizes(arr))
                    }

                    LanProtocol.FILE_META -> {
                        val pid = obj.optLong("payloadId")
                        val name = obj.optString("name")
                        val size = obj.optLong("size", -1L)
                        val mime = obj.optString("mime", "application/octet-stream")
                        onFileMeta(pid, name, mime, size)

                        // Raw file bytes follow: 8-byte length prefix + bytes
                        val dataLen = ins.readLong()
                        val savedName = withContext(Dispatchers.IO) {
                            onFileData(pid, dataLen, BufferedInputStream(sock.getInputStream()))
                        }
                        // Send ACK back to the sender
                        writeJson(
                            outs, JSONObject()
                                .put("type", LanProtocol.ACK)
                                .put("payloadId", pid)
                                .put("ok", true)
                                .put("name", savedName)
                        )
                        onAckSent(savedName)
                    }

                    else -> { /* ignore unknown message types */ }
                }
            }
        }
    }

    // Calculates the total size of all files from the HEADER metadata.
    private fun sumSizes(arr: JSONArray?): Long {
        if (arr == null) return 0L
        var sum = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optLong("size", -1L)
            if (s > 0) sum += s
        }
        return sum
    }

    // Reads a size-prefixed JSON object from the input stream, or null on error.
    private fun readJson(ins: DataInputStream): JSONObject? {
        return try {
            val len = ins.readInt()
            val buf = ByteArray(len)
            ins.readFully(buf)
            JSONObject(String(buf, Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    // Writes a JSON object to the output stream with a size prefix.
    private fun writeJson(outs: DataOutputStream, jo: JSONObject) {
        val b = jo.toString().toByteArray(Charsets.UTF_8)
        outs.writeInt(b.size)
        outs.write(b)
        outs.flush()
    }
}