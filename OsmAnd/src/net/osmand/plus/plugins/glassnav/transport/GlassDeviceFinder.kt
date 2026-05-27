package net.osmand.plus.plugins.glassnav.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Picks the most-likely Glass device from the phone's paired Bluetooth devices.
 *
 * Strategy:
 *   1. If user has saved an explicit MAC in {@link TransportPrefs}, return that.
 *   2. Otherwise, look for a paired (bonded) device whose name contains "Glass".
 *   3. As a last resort with no name match, return any single bonded device (helps when the
 *      Glass advertises a generic name like "GLASS-XX:XX").
 */
object GlassDeviceFinder {

    private const val TAG = "GlassDeviceFinder"

    sealed class Result {
        data class Found(val mac: String, val name: String, val source: String) : Result()
        object BluetoothUnavailable : Result()
        object BluetoothDisabled : Result()
        object PermissionMissing : Result()
        object NoPairedDevices : Result()
        object NoGlassFound : Result()
    }

    @SuppressLint("MissingPermission")
    fun find(context: Context): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            if (perm != PackageManager.PERMISSION_GRANTED) return Result.PermissionMissing
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return Result.BluetoothUnavailable
        if (!adapter.isEnabled) return Result.BluetoothDisabled

        val saved = TransportPrefs.getGlassMac(context)
        if (saved != null) {
            val matched = adapter.bondedDevices?.firstOrNull { it.address.equals(saved, ignoreCase = true) }
            if (matched != null) {
                return Result.Found(matched.address, safeName(matched) ?: "(unnamed)", "saved")
            }
            // Saved MAC not bonded right now — return it anyway; caller can attempt to connect.
            Log.w(TAG, "saved MAC $saved not in bonded set; will try anyway")
            return Result.Found(saved, "(unbonded)", "saved")
        }

        val bonded = adapter.bondedDevices ?: emptySet()
        if (bonded.isEmpty()) return Result.NoPairedDevices

        val byName = bonded.firstOrNull { (safeName(it) ?: "").contains("glass", ignoreCase = true) }
        if (byName != null) return Result.Found(byName.address, safeName(byName) ?: "", "name=glass")

        if (bonded.size == 1) {
            val only = bonded.first()
            return Result.Found(only.address, safeName(only) ?: "", "only-paired")
        }
        return Result.NoGlassFound
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }
}
