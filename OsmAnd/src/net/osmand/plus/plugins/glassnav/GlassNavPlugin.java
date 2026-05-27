package net.osmand.plus.plugins.glassnav;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.fragments.SettingsScreenType;

public class GlassNavPlugin extends OsmandPlugin {

	public static final String PLUGIN_GLASS_NAV = "osmand.glassnav";

	@NonNull private final GlassNavSettings settings;

	/**
	 * Strong references — RoutingHelper stores listeners in WeakReference lists, so anything held
	 * only by it gets collected between ticks. Keeping the listener (and the controller it owns)
	 * pinned on the plugin guarantees the streaming pipeline survives across navigation events.
	 */
	@Nullable private GlassNavController controller;
	@Nullable private GlassNavRoutingListener routingListener;

	public GlassNavPlugin(@NonNull OsmandApplication app) {
		super(app);
		settings = new GlassNavSettings(app.getSettings());
	}

	@Override
	public String getId() {
		return PLUGIN_GLASS_NAV;
	}

	@Override
	public String getName() {
		return app.getString(R.string.glass_nav_plugin_name);
	}

	@Override
	public CharSequence getDescription(boolean linksEnabled) {
		return app.getString(R.string.glass_nav_plugin_description);
	}

	@Override
	public boolean isEnableByDefault() {
		return true;
	}

	@Nullable
	@Override
	public SettingsScreenType getSettingsScreenType() {
		return SettingsScreenType.GLASS_NAV_SETTINGS;
	}

	@NonNull
	public GlassNavSettings getGlassNavSettings() {
		return settings;
	}

	@Nullable
	public GlassNavController getController() {
		return controller;
	}

	@Override
	public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
		controller = new GlassNavController(app, settings);
		routingListener = new GlassNavRoutingListener(app, controller);

		RoutingHelper rh = app.getRoutingHelper();
		rh.addListener(routingListener);
		rh.addRouteDataListener(routingListener);
		return super.init(app, activity);
	}

	@Override
	public void disable(@NonNull OsmandApplication app) {
		if (routingListener != null) {
			RoutingHelper rh = app.getRoutingHelper();
			rh.removeListener(routingListener);
			rh.removeRouteDataListener(routingListener);
			routingListener = null;
		}
		if (controller != null) {
			controller.shutdown();
			controller = null;
		}
		super.disable(app);
	}
}
