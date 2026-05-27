package net.osmand.plus.plugins.glassnav.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.glassnav.GlassNavPlugin

/**
 * Lists the phone's bonded Bluetooth devices so the user can pick the one that is the Glass
 * headset. The selected MAC is written to [com.goodanser.osmglass.phone.protocol]
 * [net.osmand.plus.plugins.glassnav.GlassNavSettings.pairedMac] and the parent
 * [GlassNavSettingsFragment] is notified so it can refresh the summary and ping the controller.
 *
 * The list comes from [BluetoothAdapter.getBondedDevices] — pairing the device itself still
 * happens in the system Bluetooth settings. The existing
 * [net.osmand.plus.plugins.glassnav.transport.GlassDeviceFinder] supplies the same lookup at
 * connect time, but it returns at most one device; here we want to surface all of them so the
 * user can disambiguate when several headsets are paired.
 */
class GlassDevicePairingDialog : DialogFragment() {

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val ctx = requireContext()
		val builder = AlertDialog.Builder(ctx)
			.setTitle(R.string.glass_nav_pair_device_dialog_title)
			.setNegativeButton(R.string.shared_string_cancel, null)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val perm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
			if (perm != PackageManager.PERMISSION_GRANTED) {
				val act = activity
				if (act != null) {
					ActivityCompat.requestPermissions(
						act,
						arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
						REQUEST_BLUETOOTH_CONNECT,
					)
				}
				return builder.setMessage(R.string.glass_nav_bluetooth_permission_required).create()
			}
		}

		val adapter = BluetoothAdapter.getDefaultAdapter()
		val bonded: List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()
		if (bonded.isEmpty()) {
			return builder.setMessage(R.string.glass_nav_no_paired_devices).create()
		}

		val labels = bonded.map { label(it) }.toTypedArray<CharSequence>()
		builder.setItems(labels) { _, which ->
			val device = bonded[which]
			val plugin = PluginsHelper.getPlugin(GlassNavPlugin::class.java) ?: return@setItems
			plugin.glassNavSettings.pairedMac.set(device.address)
			(parentFragment as? GlassNavSettingsFragment)?.onPairedMacChanged()
			dismiss()
		}
		return builder.create()
	}

	@SuppressLint("MissingPermission")
	private fun label(d: BluetoothDevice): CharSequence {
		val name = try { d.name } catch (_: SecurityException) { null }
		return if (name.isNullOrBlank()) d.address else "$name\n${d.address}"
	}

	companion object {
		const val TAG = "GlassDevicePairingDialog"
		private const val REQUEST_BLUETOOTH_CONNECT = 0x9101
	}
}
