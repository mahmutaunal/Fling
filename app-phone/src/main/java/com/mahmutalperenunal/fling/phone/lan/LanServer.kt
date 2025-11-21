package com.mahmutalperenunal.fling.phone.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
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
 * LAN server running on the phone side.
 * Listens for TV connections, performs a HELLO/PIN handshake,
 * and receives files over a JSON + TCP stream.
 */
class LanServer(
    ctx: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val isTrusted: (remoteDeviceId: String) -> Boolean,
    private val trustNow: (remoteDeviceId: String) -> Unit,
    private val onPinRequired: (pin: String) -> Unit,
    private val onHeader: (count: Int, totalBytes: Long) -> Unit,
    private val onFileMeta: (payloadId: Long, name: String, mime: String, size: Long) -> Unit,
    private val onFileData: suspend (payloadId: Long, size: Long, input: BufferedInputStream) -> String, // saved name
    private val onAckSent: (name: String) -> Unit,
    private val log: (String) -> Unit
) {
    private val tag = "LanServerPhone"
    private var server: ServerSocket? = null
    private val nsd: NsdManager = ctx.getSystemService(NsdManager::class.java)
    private var regListener: NsdManager.RegistrationListener? = null

    /**
     * Starts the phone LAN server on a random free port and registers it via NSD.
     * Each incoming TV connection is handled on a separate coroutine.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val s = ServerSocket(0) // bind to any free port
            server = s
            registerService(s.localPort)
            log("Phone LAN server started on port ${s.localPort}")

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
            regListener?.let { nsd.unregisterService(it) }
        } catch (_: Throwable) {
        }
    }

    // Advertises this phone as an mDNS service so the TV can discover it.
    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = LanProtocol.SERVICE_NAME_PREFIX_PHONE + deviceId.takeLast(4)
            serviceType = LanProtocol.SERVICE_TYPE_PHONE
            setPort(port)
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("NSD phone registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log("NSD phone reg failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                log("NSD phone unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log("NSD phone unreg failed: $errorCode")
            }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
    }

    /**
     * Handles a single TV client connection:
     * performs HELLO/PIN handshake and then processes incoming file metadata and data.
     */
    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            val ins = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val outs = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            // 1) Receive HELLO from the TV
            val helloReq = readJson(ins)
            if (helloReq?.optString("type") != LanProtocol.HELLO) return
            val remoteId = helloReq.optString("deviceId")
            helloReq.optString("deviceName", "TV-Box")

            // 2) Trust check + optional PIN generation
            val trusted = isTrusted(remoteId)
            val pin = if (trusted) "" else (100000..999999).random().toString()
            if (!trusted) onPinRequired(pin)

            // 3) Phone responds with HELLO + PIN (if not already trusted)
            writeJson(
                outs, JSONObject()
                    .put("type", LanProtocol.HELLO)
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .put("pin", pin)
            )

            if (!trusted) {
                val confirm = readJson(ins)
                if (confirm?.optString("type") != LanProtocol.CONFIRM ||
                    !confirm.optBoolean("agree") ||
                    confirm.optString("pin") != pin
                ) {
                    return
                } else {
                    trustNow(remoteId)
                }
            }

            // 4) HEADER + FILE stream from the TV (metadata + file bytes)
            while (true) {
                val obj = readJson(ins) ?: break
                when (obj.optString("type")) {
                    LanProtocol.HEADER -> {
                        val arr = obj.optJSONArray("files")
                        val count = obj.optInt("count", arr?.length() ?: 0)
                        val total = sumSizes(arr)
                        onHeader(count, total)
                    }

                    LanProtocol.FILE_META -> {
                        val pid = obj.getLong("payloadId")
                        val name = obj.getString("name")
                        val size = obj.optLong("size", -1L)
                        val mime = obj.optString("mime", "application/octet-stream")
                        onFileMeta(pid, name, mime, size)

                        // Next: 8-byte length prefix followed by raw file bytes
                        val length = ins.readLong()
                        val savedName = withContext(Dispatchers.IO) {
                            onFileData(pid, length, BufferedInputStream(sock.getInputStream()))
                        }
                        // Send ACK back to the TV after the file is saved
                        writeJson(
                            outs, JSONObject()
                                .put("type", LanProtocol.ACK)
                                .put("payloadId", pid)
                                .put("ok", true)
                                .put("name", savedName)
                        )
                        onAckSent(savedName)
                    }
                }
            }
        }
    }

    // Calculates the total size of all files from the HEADER metadata.
    private fun sumSizes(arr: JSONArray?): Long {
        if (arr == null) return 0L
        var s = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sz = o.optLong("size", -1L)
            if (sz > 0) s += sz
        }
        return s
    }

    // Reads a size-prefixed JSON object from the input stream, or null on error.
    private fun readJson(ins: DataInputStream): JSONObject? = try {
        val len = ins.readInt()
        val buf = ByteArray(len)
        ins.readFully(buf)
        JSONObject(String(buf, Charsets.UTF_8))
    } catch (t: Throwable) {
        Log.e(tag, "readJson error", t)
        null
    }

    // Writes a JSON object with a size prefix to the output stream.
    private fun writeJson(outs: DataOutputStream, obj: JSONObject) {
        val b = obj.toString().toByteArray(Charsets.UTF_8)
        outs.writeInt(b.size)
        outs.write(b)
        outs.flush()
    }
}