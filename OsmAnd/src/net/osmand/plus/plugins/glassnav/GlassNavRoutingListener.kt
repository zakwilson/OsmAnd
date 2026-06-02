package net.osmand.plus.plugins.glassnav

import android.util.Log
import net.osmand.data.ValueHolder
import net.osmand.plus.OsmandApplication
import net.osmand.plus.routing.IRouteInformationListener
import net.osmand.plus.routing.IRoutingDataUpdateListener

/**
 * Bridges OsmAnd's routing lifecycle into [GlassNavController].
 *
 *  - [newRouteIsCalculated]: when a brand-new route is calculated (newRoute=true) and the user
 *    has a paired Glass MAC, kick off [GlassNavController.startStreaming]. A reroute after a
 *    deviation does NOT arrive as newRoute=true — OsmAnd's [net.osmand.plus.routing.RouteRecalculationHelper.setNewRoute]
 *    computes `newRoute = !prevRoute.isCalculated()`, so any recompute over an existing route
 *    fires newRoute=**false**. We forward those to [GlassNavController.onRouteRecalculated], which
 *    refreshes the route state + republishes if we're mid-stream — otherwise the Glass keeps the
 *    pre-reroute turns and tiles (glass-nav-0s1).
 *  - [routeWasCancelled] / [routeWasFinished]: stop streaming + close transport.
 *  - [onRoutingDataUpdate]: invoked on every routing tick; forwards [RoutingHelper] state to
 *    the controller which builds the [com.goodanser.osmglass.protocol.Packet.Progress] to send.
 *
 * Held by [GlassNavPlugin] as a strong field; [net.osmand.plus.routing.RoutingHelper] uses weak
 * references for its listener lists, so anything held only by the helper would be GC'd between
 * ticks.
 */
class GlassNavRoutingListener(
    private val app: OsmandApplication,
    private val controller: GlassNavController,
) : IRouteInformationListener, IRoutingDataUpdateListener {

    override fun newRouteIsCalculated(newRoute: Boolean, showToast: ValueHolder<Boolean>?) {
        if (!controller.hasPairedDevice()) {
            Log.d(TAG, "newRouteIsCalculated($newRoute) — no paired Glass MAC configured; skipping")
            return
        }
        if (newRoute) {
            Log.i(TAG, "newRouteIsCalculated(true) — starting Glass stream")
            controller.startStreaming()
        } else {
            // Reroute after a deviation (OsmAnd reports recomputes over an existing route as
            // newRoute=false). Refresh + republish if we're mid-stream so Glass drops the stale
            // route's tiles (glass-nav-0s1).
            Log.i(TAG, "newRouteIsCalculated(false) — reroute; refreshing Glass stream")
            controller.onRouteRecalculated()
        }
    }

    override fun routeWasCancelled() {
        Log.i(TAG, "routeWasCancelled")
        controller.stopStreaming()
    }

    override fun routeWasFinished() {
        Log.i(TAG, "routeWasFinished")
        controller.stopStreaming()
    }

    override fun onRoutingDataUpdate() {
        controller.onRoutingDataUpdate(app.routingHelper)
    }

    companion object {
        private const val TAG = "GlassNavRoutingListener"
    }
}
