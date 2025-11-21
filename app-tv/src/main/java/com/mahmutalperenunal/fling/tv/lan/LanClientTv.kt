package com.mahmutalperenunal.fling.tv.lan

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.mahmutalperenunal.fling.core.LanProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * LAN client running on the TV side.
 * Discovers the phone over mDNS and sends selected files via a simple JSON/TCP protocol.
 */
class LanClientTv(
    private val ctx: Context,
    private val myDeviceId: String,
    private val myDeviceName: String,
    private val isTrustedPhone: (remoteId: String) -> Boolean,
    private val trustPhoneNow: (remoteId: String) -> Unit
) {
    private val tag = "LanClientTv"
    private val nsd: NsdManager = ctx.getSystemService(NsdManager::class.java)

    /**
     * Metadata for a file that will be sent over LAN.
     */
    data class FileMeta(val uri: Uri, val name: String, val size: Long, val mime: String?)

    /**
     * Discovers a single phone over LAN and sends all files sequentially.
     * Returns true only if every file is acknowledged by the receiver.
     */
    suspend fun discoverAndSend(
        files: List<FileMeta>,
        onPinDialog: suspend (pin: String) -> Boolean, // does the user confirm that the PIN matches?
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val resolved = discoverOne() ?: return@withContext false
        val (host, port, remoteId) = resolved

        Socket(host, port).use { sock ->
            val ins = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val outs = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            // 1) Send HELLO with TV device info
            writeJson(
                outs, JSONObject()
                    .put("type", LanProtocol.HELLO)
                    .put("deviceId", myDeviceId)
                    .put("deviceName", myDeviceName)
            )

            // 2) Receive HELLO + PIN from the phone
            val hello = readJson(ins) ?: return@withContext false
            val pin = hello.optString("pin", "")
            val isTrusted = isTrustedPhone(remoteId)
            if (!isTrusted) {
                val ok = onPinDialog(pin)
                writeJson(
                    outs, JSONObject()
                        .put("type", LanProtocol.CONFIRM)
                        .put("agree", ok)
                        .put("pin", pin)
                )
                if (!ok) return@withContext false
                trustPhoneNow(remoteId)
            }

            // 3) Send HEADER with all file metadata
            val arr = JSONArray()
            files.forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name)
                        .put("size", it.size)
                        .put("mime", it.mime ?: "application/octet-stream")
                )
            }
            writeJson(
                outs, JSONObject()
                    .put("type", LanProtocol.HEADER)
                    .put("count", files.size)
                    .put("files", arr)
            )

            // 4) For each file: FILE_META + length + bytes + ACK
            for (f in files) {
                val pid = System.nanoTime() // basit uniq id

                writeJson(
                    outs, JSONObject()
                        .put("type", LanProtocol.FILE_META)
                        .put("payloadId", pid)
                        .put("name", f.name)
                        .put("size", f.size)
                        .put("mime", f.mime ?: "application/octet-stream")
                )

                val length = f.size.coerceAtLeast(0L)
                outs.writeLong(length)
                ctx.contentResolver.openInputStream(f.uri)?.use { fileIns ->
                    val buf = ByteArray(LanProtocol.BUFFER)
                    var sent = 0L
                    var read: Int
                    while (fileIns.read(buf).also { read = it } >= 0) {
                        outs.write(buf, 0, read)
                        sent += read
                        if (length > 0) {
                            val pct = (100 * sent / length).toInt()
                            onProgress("${f.name}: %$pct")
                        }
                    }
                }
                outs.flush()

                // Wait for ACK after file bytes are sent
                val ack = readJson(ins)
                if (ack?.optString("type") == LanProtocol.ACK && ack.optBoolean("ok", false)) {
                    onProgress("LAN ACK OK: ${ack.optString("name", f.name)}")
                } else {
                    onProgress("LAN ACK Error: ${f.name}")
                    return@withContext false
                }
            }
            true
        }
    }

    // Uses Android NSD to resolve a single phone advertising the LAN service.
    private fun discoverOne(): Triple<InetAddress, Int, String>? {
        var resolved: NsdServiceInfo? = null
        val lock = Object()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(tag, "NSD discovery start: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == LanProtocol.SERVICE_TYPE_PHONE) {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            resolved = resolvedInfo
                            synchronized(lock) { lock.notify() }
                        }

                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) { lock.notify() }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsd.discoverServices(LanProtocol.SERVICE_TYPE_PHONE, NsdManager.PROTOCOL_DNS_SD, listener)
        synchronized(lock) {
            try {
                lock.wait(8000)
            } catch (_: InterruptedException) {
            }
        }
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }

        val info = resolved ?: return null
        val suffix = info.serviceName.removePrefix(LanProtocol.SERVICE_NAME_PREFIX_PHONE)
        return Triple(info.host, info.port, suffix)
    }

    // Writes a JSON object with a size-prefixed frame to the socket.
    private fun writeJson(outs: DataOutputStream, obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        outs.writeInt(bytes.size)
        outs.write(bytes)
        outs.flush()
    }

    // Reads a size-prefixed JSON frame from the socket, or null on error.
    private fun readJson(ins: DataInputStream): JSONObject? = try {
        val len = ins.readInt()
        val buf = ByteArray(len)
        ins.readFully(buf)
        JSONObject(String(buf, Charsets.UTF_8))
    } catch (t: Throwable) {
        Log.e(tag, "readJson error", t)
        null
    }
}