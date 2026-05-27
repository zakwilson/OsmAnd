package net.osmand.plus.plugins.glassnav.transport

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent transport preferences. Set via the settings dialog or `adb shell` overrides.
 */
object TransportPrefs {
    private const val FILE = "transport"
    private const val KEY_MODE = "mode"           // "auto", "rfcomm", "tcp"
    private const val KEY_GLASS_MAC = "glass_mac"
    private const val KEY_TCP_HOST = "tcp_host"

    enum class Mode { AUTO, RFCOMM, TCP }

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Mode = when (prefs(ctx).getString(KEY_MODE, "auto")?.lowercase()) {
        "rfcomm" -> Mode.RFCOMM
        "tcp" -> Mode.TCP
        else -> Mode.AUTO
    }

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name.lowercase()).apply()
    }

    fun getGlassMac(ctx: Context): String? = prefs(ctx).getString(KEY_GLASS_MAC, null)
    fun setGlassMac(ctx: Context, mac: String?) {
        prefs(ctx).edit().apply {
            if (mac == null) remove(KEY_GLASS_MAC) else putString(KEY_GLASS_MAC, mac)
        }.apply()
    }

    fun getTcpHost(ctx: Context, default: String = "10.0.2.2"): String =
        prefs(ctx).getString(KEY_TCP_HOST, default) ?: default
}
