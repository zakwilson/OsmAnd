package net.osmand.plus.plugins.glassnav.fragments;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.goodanser.osmglass.protocol.Packet;

import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.glassnav.GlassNavController;
import net.osmand.plus.plugins.glassnav.GlassNavPlugin;
import net.osmand.plus.plugins.glassnav.GlassNavSettings;
import net.osmand.plus.plugins.glassnav.render.MapOrientation;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;

/**
 * Settings screen for {@link GlassNavPlugin}. Reached via OsmAnd Menu → Plugins → Glass Nav →
 * Settings (the routing is set up by {@link GlassNavPlugin#getSettingsScreenType()} returning
 * {@link net.osmand.plus.settings.fragments.SettingsScreenType#GLASS_NAV_SETTINGS}).
 *
 * <p>Modeled on {@link net.osmand.plus.plugins.externalsensors.AntPlusSettingsFragment}: load the
 * base info preference from XML, then programmatically append the prefs that the plugin owns.
 * Prefs are added in code (rather than declared in the XML) so the IDs come from
 * {@link GlassNavSettings} constants instead of being repeated as XML keys.
 */
public class GlassNavSettingsFragment extends BaseSettingsFragment {

	private static final String PAIR_DEVICE_PREF_KEY = "glass_nav_pair_device";

	private GlassNavPlugin plugin;
	private GlassNavSettings glassSettings;

	@Override
	protected void setupPreferences() {
		plugin = PluginsHelper.getPlugin(GlassNavPlugin.class);
		if (plugin == null) return;
		glassSettings = plugin.getGlassNavSettings();

		Preference info = findPreference("glass_nav_info");
		if (info != null) {
			info.setIcon(getContentIcon(R.drawable.ic_action_info_dark));
		}

		PreferenceScreen screen = getPreferenceScreen();
		if (screen == null) return;

		setupPairDevicePref(screen);
		setupSlotPref(screen, GlassNavSettings.PREF_TOP_LEFT_SLOT,
				R.string.glass_nav_top_left_slot_title, glassSettings.topLeftSlot.get());
		setupSlotPref(screen, GlassNavSettings.PREF_TOP_RIGHT_SLOT,
				R.string.glass_nav_top_right_slot_title, glassSettings.topRightSlot.get());
		setupSlotPref(screen, GlassNavSettings.PREF_BOTTOM_LEFT_SLOT,
				R.string.glass_nav_bottom_left_slot_title, glassSettings.bottomLeftSlot.get());
		setupSlotPref(screen, GlassNavSettings.PREF_BOTTOM_RIGHT_SLOT,
				R.string.glass_nav_bottom_right_slot_title, glassSettings.bottomRightSlot.get());
		setupTtsMutePref(screen);
		setupMapOrientationPref(screen);
		setupDebugLoggingPref(screen);
	}

	private void setupPairDevicePref(@NonNull PreferenceScreen screen) {
		Preference pair = new Preference(screen.getContext());
		pair.setKey(PAIR_DEVICE_PREF_KEY);
		pair.setPersistent(false);
		pair.setTitle(R.string.glass_nav_pair_device);
		pair.setSummary(pairSummary());
		pair.setIconSpaceReserved(false);
		// Click is dispatched via onPreferenceClick(Preference) below — BaseSettingsFragment
		// reassigns every Preference's OnPreferenceClickListener to itself after setupPreferences()
		// runs, so a listener attached here would be clobbered.
		screen.addPreference(pair);
	}

	@Override
	public boolean onPreferenceClick(@NonNull Preference preference) {
		if (PAIR_DEVICE_PREF_KEY.equals(preference.getKey())) {
			GlassDevicePairingDialog dialog = new GlassDevicePairingDialog();
			dialog.show(getChildFragmentManager(), GlassDevicePairingDialog.TAG);
			return true;
		}
		return super.onPreferenceClick(preference);
	}

	/**
	 * Applies plugin preference changes. We handle them here rather than via per-preference
	 * {@code setOnPreferenceChangeListener} calls because {@link BaseSettingsFragment#registerPreference}
	 * reassigns every preference's OnPreferenceChangeListener to the fragment itself after
	 * {@link #setupPreferences()} runs (same caveat as the click listener above). A directly-attached
	 * listener would never fire, leaving the on-screen summary stale and the controller un-notified
	 * until the settings screen is reopened.
	 *
	 * <p>This is invoked before the new value is persisted through the preference data store, so we
	 * write the backing {@link GlassNavSettings} preference here too: the controller reads the live
	 * value when notified, and the subsequent data-store write lands the same value.
	 */
	@Override
	public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
		String key = preference.getKey();
		CommonPreference<Packet.DisplayConfig.Field> slotPref = slotPrefForKey(key);
		if (slotPref != null) {
			Packet.DisplayConfig.Field f;
			try {
				f = Packet.DisplayConfig.Field.valueOf((String) newValue);
			} catch (IllegalArgumentException ex) {
				return false;
			}
			slotPref.set(f);
			preference.setSummary(getString(fieldLabelRes(f)));
			notifyControllerSettingsChanged();
			return true;
		} else if (GlassNavSettings.PREF_MAP_ORIENTATION.equals(key)) {
			MapOrientation o;
			try {
				o = MapOrientation.valueOf((String) newValue);
			} catch (IllegalArgumentException ex) {
				return false;
			}
			glassSettings.mapOrientation.set(o);
			preference.setSummary(getString(orientationLabelRes(o)));
			notifyControllerSettingsChanged();
			return true;
		} else if (GlassNavSettings.PREF_TTS_MUTED.equals(key)) {
			glassSettings.ttsMuted.set((Boolean) newValue);
			notifyControllerSettingsChanged();
			return true;
		} else if (GlassNavSettings.PREF_DEBUG_LOGGING.equals(key)) {
			// No notifyControllerSettingsChanged: the controller reads debugLogging live, and toggling
			// it must not trigger a snippet re-render the way the display/orientation prefs do.
			glassSettings.debugLogging.set((Boolean) newValue);
			return true;
		}
		return super.onPreferenceChange(preference, newValue);
	}

	/** Maps a corner-slot preference key to its backing {@link GlassNavSettings} preference, or
	 *  null if the key isn't one of the four corner slots. */
	private CommonPreference<Packet.DisplayConfig.Field> slotPrefForKey(String key) {
		if (GlassNavSettings.PREF_TOP_LEFT_SLOT.equals(key)) return glassSettings.topLeftSlot;
		if (GlassNavSettings.PREF_TOP_RIGHT_SLOT.equals(key)) return glassSettings.topRightSlot;
		if (GlassNavSettings.PREF_BOTTOM_LEFT_SLOT.equals(key)) return glassSettings.bottomLeftSlot;
		if (GlassNavSettings.PREF_BOTTOM_RIGHT_SLOT.equals(key)) return glassSettings.bottomRightSlot;
		return null;
	}

	private CharSequence pairSummary() {
		String mac = glassSettings.getPairedMacOrNull();
		return mac != null ? mac : getString(R.string.glass_nav_pair_device_summary_unpaired);
	}

	private void setupSlotPref(@NonNull PreferenceScreen screen, @NonNull String key,
	                           int titleRes, @NonNull Packet.DisplayConfig.Field current) {
		ListPreference pref = new ListPreference(screen.getContext());
		pref.setKey(key);
		pref.setTitle(titleRes);
		pref.setIconSpaceReserved(false);
		Packet.DisplayConfig.Field[] fields = Packet.DisplayConfig.Field.values();
		CharSequence[] entries = new CharSequence[fields.length];
		CharSequence[] values = new CharSequence[fields.length];
		for (int i = 0; i < fields.length; i++) {
			entries[i] = getString(fieldLabelRes(fields[i]));
			values[i] = fields[i].name();
		}
		pref.setEntries(entries);
		pref.setEntryValues(values);
		pref.setValue(current.name());
		pref.setSummary(getString(fieldLabelRes(current)));
		pref.setDialogTitle(titleRes);
		// Change handling lives in onPreferenceChange (see note there).
		screen.addPreference(pref);
	}

	private void setupTtsMutePref(@NonNull PreferenceScreen screen) {
		SwitchPreferenceCompat pref = new SwitchPreferenceCompat(screen.getContext());
		pref.setKey(GlassNavSettings.PREF_TTS_MUTED);
		pref.setTitle(R.string.glass_nav_tts_muted_title);
		pref.setSummary(R.string.glass_nav_tts_muted_summary);
		pref.setIconSpaceReserved(false);
		pref.setChecked(glassSettings.ttsMuted.get());
		// Change handling lives in onPreferenceChange (see note there).
		screen.addPreference(pref);
	}

	private void setupDebugLoggingPref(@NonNull PreferenceScreen screen) {
		SwitchPreferenceCompat pref = new SwitchPreferenceCompat(screen.getContext());
		pref.setKey(GlassNavSettings.PREF_DEBUG_LOGGING);
		pref.setTitle(R.string.glass_nav_debug_logging_title);
		pref.setSummary(R.string.glass_nav_debug_logging_summary);
		pref.setIconSpaceReserved(false);
		pref.setChecked(glassSettings.debugLogging.get());
		// Change handling lives in onPreferenceChange (see note there).
		screen.addPreference(pref);
	}

	private void setupMapOrientationPref(@NonNull PreferenceScreen screen) {
		ListPreference pref = new ListPreference(screen.getContext());
		pref.setKey(GlassNavSettings.PREF_MAP_ORIENTATION);
		pref.setTitle(R.string.glass_nav_map_orientation_title);
		pref.setIconSpaceReserved(false);
		MapOrientation[] orientations = MapOrientation.values();
		CharSequence[] entries = new CharSequence[orientations.length];
		CharSequence[] values = new CharSequence[orientations.length];
		for (int i = 0; i < orientations.length; i++) {
			entries[i] = getString(orientationLabelRes(orientations[i]));
			values[i] = orientations[i].name();
		}
		pref.setEntries(entries);
		pref.setEntryValues(values);
		MapOrientation current = glassSettings.mapOrientation.get();
		pref.setValue(current.name());
		pref.setSummary(getString(orientationLabelRes(current)));
		pref.setDialogTitle(R.string.glass_nav_map_orientation_title);
		// Change handling lives in onPreferenceChange (see note there).
		screen.addPreference(pref);
	}

	private static int fieldLabelRes(@NonNull Packet.DisplayConfig.Field f) {
		switch (f) {
			case TURN_INSTRUCTION:    return R.string.glass_nav_field_turn_instruction;
			case DISTANCE_TO_TURN:    return R.string.glass_nav_field_distance_to_turn;
			case REMAINING_DISTANCE:  return R.string.glass_nav_field_remaining_distance;
			case ETA:                 return R.string.glass_nav_field_eta;
			case ARRIVAL_TIME:        return R.string.glass_nav_field_arrival_time;
			case SPEED:               return R.string.glass_nav_field_speed;
			case NONE:                return R.string.glass_nav_field_none;
		}
		return R.string.glass_nav_field_turn_instruction;
	}

	private static int orientationLabelRes(@NonNull MapOrientation o) {
		switch (o) {
			case NORTH_UP:   return R.string.glass_nav_orientation_north_up;
			case TRAVEL_UP:  return R.string.glass_nav_orientation_travel_up;
		}
		return R.string.glass_nav_orientation_north_up;
	}

	/** Called by the pairing dialog after persisting a MAC. */
	public void onPairedMacChanged() {
		Preference pair = findPreference(PAIR_DEVICE_PREF_KEY);
		if (pair != null) {
			pair.setSummary(pairSummary());
		}
		notifyControllerSettingsChanged();
	}

	private void notifyControllerSettingsChanged() {
		if (plugin == null) return;
		GlassNavController controller = plugin.getController();
		if (controller != null) controller.onSettingsChanged();
	}
}
