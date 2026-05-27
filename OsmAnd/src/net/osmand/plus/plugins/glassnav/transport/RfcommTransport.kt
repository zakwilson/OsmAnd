package net.osmand.plus.plugins.glassnav.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.goodanser.osmglass.protocol.FrameReader
import com.goodanser.osmglass.protocol.FrameWriter
import com.goodanser.osmglass.protocol.Packet
import com.goodanser.osmglass.protocol.transport.Keepalive
import com.goodanser.osmglass.protocol.transport.Transport
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side Bluetooth Classic RFCOMM client. Connects out to the Glass server using a fixed UUID.
 *
 * Reconnect is handled here: on disconnect we wait an exponentially-increasing back-off (capped at
 * {@link #MAX_BACKOFF_MS}) and retry. Hardware-only — emulator BT does not support cross-device
 * RFCOMM, so this code path is not exercised by container tests; it compiles and is link-checked
 * against API 24 only.
 */
class RfcommTransport(
    private val deviceMac: String,
    private val uuid: UUID = GlassUuid,
) : Transport {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: BluetoothSocket? = null
    private var writer: FrameWriter? = null
    private var listener: Transport.Listener? = null

    override fun setListener(listener: Transport.Listener) {
        this.listener = listener
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ run() }, "RfcommTransport").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { socket?.close() } catch (_: IOException) {}
        thread?.interrupt()
    }

    override fun send(p: Packet) {
        val w = writer ?: throw IOException("RfcommTransport not connected")
        w.write(p)
    }

    private fun run() {
        var backoff = INITIAL_BACKOFF_MS
        while (running.get()) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IOException("Bluetooth not supported on this device")
                if (!adapter.isEnabled) throw IOException("Bluetooth disabled")
                val device: BluetoothDevice = adapter.getRemoteDevice(deviceMac)
                socket = openSocket(device)
                socket!!.connect()
                Log.i(TAG, "RFCOMM connected to ${device.address} on channel $RFCOMM_CHANNEL")
                writer = FrameWriter(socket!!.outputStream)
                val reader = FrameReader(socket!!.inputStream)
                listener?.onConnected()
                backoff = INITIAL_BACKOFF_MS
                val keepalive = Keepalive(
                    /* sendPings = */ true,
                    { p -> try { writer?.write(p) } catch (_: Throwable) {} },
                    { try { socket?.close() } catch (_: Throwable) {} },
                )
                keepalive.start()
                try {
                    while (running.get()) {
                        val p = reader.readNext() ?: break
                        if (keepalive.handleInbound(p)) continue
                        listener?.onPacket(p)
                    }
                } finally {
                    keepalive.stop()
                }
                listener?.onDisconnected(null)
            } catch (t: Throwable) {
                Log.w(TAG, "RFCOMM session failed: ${t.message}")
                listener?.onDisconnected(t)
            } finally {
                writer = null
                try { socket?.close() } catch (_: IOException) {}
                socket = null
            }
            if (running.get()) {
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * Open the RFCOMM socket on a fixed channel via the hidden {@code createInsecureRfcommSocket}
     * BluetoothDevice API. Bypasses SDP, which on Glass XE-C sometimes returns a stale or wrong
     * channel — symptom: phone reports "connected" but the Glass server's accept() never returns
     * because the connection landed on a different RFCOMM service. Falls back to UUID-based SDP
     * lookup if reflection isn't available.
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) t.cause ?: t else t
            Log.w(TAG, "createInsecureRfcommSocket($RFCOMM_CHANNEL) reflection failed: ${cause.message} — falling back to SDP UUID")
            device.createInsecureRfcommSocketToServiceRecord(uuid)
        }
    }

    companion object {
        const val TAG = "RfcommTransport"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        /** Must match Glass-side RfcommServerTransport.RFCOMM_CHANNEL. */
        const val RFCOMM_CHANNEL = 22
        val GlassUuid: UUID = UUID.fromString("e8b4c8a0-1f3a-4f7b-9b0d-ee9f2c1a7b00")
    }
}
