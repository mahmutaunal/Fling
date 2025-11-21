package com.mahmutalperenunal.fling.phone.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.mahmutalperenunal.fling.core.LanProtocol
import com.mahmutalperenunal.fling.phone.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.Socket

/**
 * LAN client running on the phone side.
 * Discovers the TV over mDNS and sends selected files via a JSON + TCP protocol.
 */
class LanClient(
    private val ctx: Context,
    private val myDeviceId: String,
    private val myDeviceName: String,
    private val isTrustedTv: (remoteId: String) -> Boolean,
    private val trustTvNow: (remoteId: String) -> Unit,
) {
    private val tag = "LanClient"
    private val nsd: NsdManager = ctx.getSystemService(NsdManager::class.java)

    /**
     * Discovers a single TV over LAN and sends all files sequentially.
     * Returns true only if every file is acknowledged by the TV.
     */
    suspend fun discoverAndSend(
        files: List<FileMeta>,
        onPinDialog: suspend (pin: String) -> Boolean, // true if user confirms that the PIN matches
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val (host, port, remoteId) = discoverOne() ?: return@withContext false
        Socket(host, port).use { sock ->
            val ins = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val outs = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            // Send HELLO to the TV with device info
            writeJson(
                outs,
                JSONObject().put("type", LanProtocol.HELLO).put("deviceId", myDeviceId)
                    .put("deviceName", myDeviceName)
            )

            // Wait for HELLO from the TV (may contain a PIN)
            val hello = readJson(ins) ?: return@withContext false
            val pin = hello.optString("pin", "")
            val isTrusted = isTrustedTv(remoteId)
            if (!isTrusted) {
                val ok = onPinDialog(pin) // show PIN to the user and ask for confirmation
                writeJson(
                    outs,
                    JSONObject().put("type", LanProtocol.CONFIRM).put("agree", ok).put("pin", pin)
                )
                if (!ok) return@withContext false
                trustTvNow(remoteId)
            }

            // Send HEADER with summary of all files
            val arr = JSONArray()
            files.forEach {
                arr.put(
                    JSONObject().put("name", it.name).put("size", it.size)
                        .put("mime", it.mime ?: "application/octet-stream")
                )
            }
            writeJson(
                outs,
                JSONObject().put("type", LanProtocol.HEADER).put("count", files.size)
                    .put("files", arr)
            )

            // For each file: FILE_META + 8-byte length prefix + raw bytes
            files.forEach { f ->
                val pid = System.nanoTime() // simple unique id for this file payload
                writeJson(
                    outs,
                    JSONObject().put("type", LanProtocol.FILE_META).put("payloadId", pid)
                        .put("name", f.name).put("size", f.size)
                        .put("mime", f.mime ?: "application/octet-stream")
                )

                val len = f.size.coerceAtLeast(0L)
                outs.writeLong(len)
                ctx.contentResolver.openInputStream(f.uri)!!.use { insFile ->
                    val buf = ByteArray(LanProtocol.BUFFER)
                    var read: Int
                    var sent = 0L
                    while (insFile.read(buf).also { read = it } >= 0) {
                        outs.write(buf, 0, read)
                        sent += read
                        if (len > 0) onProgress(
                            ctx.getString(
                                R.string.lan_sending_progress,
                                f.name,
                                (100 * sent / len).toInt()
                            )
                        )
                    }
                }
                outs.flush()

                // Wait for ACK from the TV after each file
                val ack = readJson(ins)
                if (ack?.optString("type") == LanProtocol.ACK && ack.optBoolean("ok", false)) {
                    onProgress(ctx.getString(R.string.lan_ack_ok, f.name))
                } else {
                    onProgress(ctx.getString(R.string.lan_ack_error, f.name))
                    return@withContext false
                }
            }
            true
        }
    }

    /**
     * Metadata for a file that will be sent from the phone to the TV.
     */
    data class FileMeta(
        val uri: android.net.Uri,
        val name: String,
        val size: Long,
        val mime: String?
    )

    // Uses Android NSD to resolve a single TV advertising the LAN service.
    private fun discoverOne(): Triple<InetAddress, Int, String>? {
        var resolved: NsdServiceInfo? = null
        val lock = Object()

        val discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "NSD discovery start")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(tag, "NSD discovery stop")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) { lock.notify() }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == LanProtocol.SERVICE_TYPE) {
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
        }

        nsd.discoverServices(LanProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
        synchronized(lock) {
            try {
                lock.wait(8000)
            } catch (_: InterruptedException) {
            }
        }
        try {
            nsd.stopServiceDiscovery(discListener)
        } catch (_: Exception) {
        }

        val info = resolved ?: return null
        // Our serviceName format: "TVDrop-xxxx"
        val remoteIdSuffix = info.serviceName.removePrefix("TVDrop-")
        return Triple(
            info.host,
            info.port,
            remoteIdSuffix
        ) // use suffix as remoteId; after first CONFIRM you can store the real deviceId from HELLO if needed
    }

    // Reads a size-prefixed JSON object from the stream, or null on error.
    private fun readJson(ins: DataInputStream): JSONObject? = try {
        val len = ins.readInt()
        val buf = ByteArray(len)
        ins.readFully(buf)
        JSONObject(String(buf, Charsets.UTF_8))
    } catch (_: Throwable) {
        null
    }

    // Writes a JSON object with a size prefix to the stream.
    private fun writeJson(outs: DataOutputStream, jo: JSONObject) {
        val b = jo.toString().toByteArray(Charsets.UTF_8)
        outs.writeInt(b.size)
        outs.write(b)
        outs.flush()
    }
}