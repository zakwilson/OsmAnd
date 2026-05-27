package net.osmand.plus.plugins.glassnav.transport

import android.content.Context
import android.os.Build
import android.util.Log
import com.goodanser.osmglass.protocol.transport.TcpTransport
import com.goodanser.osmglass.protocol.transport.Transport

/**
 * Selects between {@link TcpTransport} (for emulator pair-testing) and {@link RfcommTransport}
 * (real Glass + phone).
 *
 * Resolution order (first match wins):
 *   1. SharedPreferences mode (set via in-app settings or `adb shell setprop com.goodanser.osmglass.transport`)
 *   2. BuildConfig.TRANSPORT_KIND (no longer used in-plugin; left as a doc reference for parity with phone-app)
 *   3. Auto: emulator → TCP, real device with paired Glass → RFCOMM, real device without → TCP
 *
 * Returns a {@link CreateResult} describing what was chosen so the UI can surface diagnostics.
 */
object TransportFactory {

    private const val TAG = "TransportFactory"

    sealed class CreateResult {
        data class Ok(val transport: Transport, val description: String) : CreateResult()
        data class Failed(val reason: String) : CreateResult()
    }

    fun create(ctx: Context, explicitMac: String? = null): CreateResult {
        val systemMode = systemProp("com.goodanser.osmglass.transport")
        val prefMode = TransportPrefs.getMode(ctx)
        val resolved: TransportPrefs.Mode = when {
            systemMode == "tcp" -> TransportPrefs.Mode.TCP
            systemMode == "rfcomm" -> TransportPrefs.Mode.RFCOMM
            prefMode != TransportPrefs.Mode.AUTO -> prefMode
            isEmulator() -> TransportPrefs.Mode.TCP
            else -> TransportPrefs.Mode.RFCOMM
        }
        Log.i(TAG, "resolved transport mode: $resolved (system=$systemMode, pref=$prefMode, emulator=${isEmulator()})")

        return when (resolved) {
            TransportPrefs.Mode.TCP -> {
                val host = TransportPrefs.getTcpHost(ctx)
                CreateResult.Ok(
                    TcpTransport(TcpTransport.Role.CLIENT, host, 8765),
                    "TCP $host:8765",
                )
            }
            TransportPrefs.Mode.RFCOMM, TransportPrefs.Mode.AUTO -> {
                if (!explicitMac.isNullOrBlank()) {
                    CreateResult.Ok(
                        RfcommTransport(explicitMac),
                        "RFCOMM $explicitMac (src=plugin-settings)",
                    )
                } else when (val r = GlassDeviceFinder.find(ctx)) {
                    is GlassDeviceFinder.Result.Found ->
                        CreateResult.Ok(
                            RfcommTransport(r.mac),
                            "RFCOMM ${r.mac} (${r.name}, src=${r.source})",
                        )
                    GlassDeviceFinder.Result.BluetoothUnavailable ->
                        CreateResult.Failed("This device has no Bluetooth.")
                    GlassDeviceFinder.Result.BluetoothDisabled ->
                        CreateResult.Failed("Bluetooth is off — turn it on and reopen the app.")
                    GlassDeviceFinder.Result.PermissionMissing ->
                        CreateResult.Failed("Bluetooth permission not granted.")
                    GlassDeviceFinder.Result.NoPairedDevices ->
                        CreateResult.Failed("Pair the Glass with this phone in Bluetooth settings first.")
                    GlassDeviceFinder.Result.NoGlassFound ->
                        CreateResult.Failed(
                            "Multiple paired devices, none look like Glass — set the MAC in app settings.",
                        )
                }
            }
        }
    }

    private fun isEmulator(): Boolean {
        val product = Build.PRODUCT.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.lowercase()
        return product.contains("sdk")
            || product.contains("emulator")
            || brand.startsWith("generic")
            || device.contains("emulator")
            || model.contains("sdk")
            || Build.FINGERPRINT.startsWith("generic")
    }

    private fun systemProp(key: String): String? = try {
        @Suppress("PrivateApi")
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }
}
