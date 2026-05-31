package net.osmand.plus.plugins.glassnav;

import androidx.annotation.NonNull;

import com.goodanser.osmglass.protocol.Packet;

import net.osmand.plus.plugins.glassnav.render.MapOrientation;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.preferences.CommonPreference;

/**
 * Facade over OsmAnd's {@link OsmandSettings} for plugin-owned preferences. Created and held by
 * {@link GlassNavPlugin}, consumed by {@link GlassNavController} and
 * {@code GlassNavSettingsFragment}.
 *
 * <p>Mirrors the spec laid out in phone-app's {@code DisplayPrefs} and
 * {@code DisplaySettingsDialogFragment}, collapsed to the Glass-side fields the in-process plugin
 * actually ships (the four {@link Packet.DisplayConfig} corner slots, {@code muteTts}, paired MAC,
 * map orientation). The phone-side slot prefs from the old app are dropped — the OsmAnd map
 * already renders any phone-side surface the rider needs.
 */
public class GlassNavSettings {

	public static final String PREF_PAIRED_MAC = "glass_nav_paired_mac";
	public static final String PREF_TOP_LEFT_SLOT = "glass_nav_top_left_slot";
	public static final String PREF_TOP_RIGHT_SLOT = "glass_nav_top_right_slot";
	public static final String PREF_BOTTOM_LEFT_SLOT = "glass_nav_bottom_left_slot";
	public static final String PREF_BOTTOM_RIGHT_SLOT = "glass_nav_bottom_right_slot";
	public static final String PREF_TTS_MUTED = "glass_nav_tts_muted";
	public static final String PREF_MAP_ORIENTATION = "glass_nav_map_orientation";
	public static final String PREF_DEBUG_LOGGING = "glass_nav_debug_logging";

	public final CommonPreference<String> pairedMac;
	public final CommonPreference<Packet.DisplayConfig.Field> topLeftSlot;
	public final CommonPreference<Packet.DisplayConfig.Field> topRightSlot;
	public final CommonPreference<Packet.DisplayConfig.Field> bottomLeftSlot;
	public final CommonPreference<Packet.DisplayConfig.Field> bottomRightSlot;
	public final CommonPreference<Boolean> ttsMuted;
	public final CommonPreference<MapOrientation> mapOrientation;
	/** When on, {@link GlassNavController} emits verbose diagnostics (turn list, per-tick progress,
	 *  departure label) to logcat. Off by default — these are per-route/per-tick and noisy. */
	public final CommonPreference<Boolean> debugLogging;

	@SuppressWarnings("unchecked")
	public GlassNavSettings(@NonNull OsmandSettings settings) {
		// Plugin settings are global (one paired Glass headset per phone install), so we don't
		// scope them to ApplicationMode the way profile prefs do.
		pairedMac = settings.registerStringPreference(PREF_PAIRED_MAC, "").makeGlobal().makeShared();
		topLeftSlot = settings.registerEnumStringPreference(
				PREF_TOP_LEFT_SLOT,
				Packet.DisplayConfig.Field.TURN_INSTRUCTION,
				Packet.DisplayConfig.Field.values(),
				Packet.DisplayConfig.Field.class).makeGlobal().makeShared();
		topRightSlot = settings.registerEnumStringPreference(
				PREF_TOP_RIGHT_SLOT,
				Packet.DisplayConfig.Field.NONE,
				Packet.DisplayConfig.Field.values(),
				Packet.DisplayConfig.Field.class).makeGlobal().makeShared();
		bottomLeftSlot = settings.registerEnumStringPreference(
				PREF_BOTTOM_LEFT_SLOT,
				Packet.DisplayConfig.Field.DISTANCE_TO_TURN,
				Packet.DisplayConfig.Field.values(),
				Packet.DisplayConfig.Field.class).makeGlobal().makeShared();
		bottomRightSlot = settings.registerEnumStringPreference(
				PREF_BOTTOM_RIGHT_SLOT,
				Packet.DisplayConfig.Field.NONE,
				Packet.DisplayConfig.Field.values(),
				Packet.DisplayConfig.Field.class).makeGlobal().makeShared();
		ttsMuted = settings.registerBooleanPreference(PREF_TTS_MUTED, false).makeGlobal().makeShared();
		mapOrientation = settings.registerEnumStringPreference(
				PREF_MAP_ORIENTATION,
				MapOrientation.NORTH_UP,
				MapOrientation.values(),
				MapOrientation.class).makeGlobal().makeShared();
		debugLogging = settings.registerBooleanPreference(PREF_DEBUG_LOGGING, false).makeGlobal().makeShared();
	}

	/** Returns the configured Glass MAC, or null if the user hasn't paired yet. */
	public String getPairedMacOrNull() {
		String mac = pairedMac.get();
		if (mac == null || mac.isEmpty()) return null;
		return mac;
	}
}
