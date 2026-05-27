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
 *    has a paired Glass MAC, kick off [GlassNavController.startStreaming]. Subsequent reroute
 *    callbacks (newRoute=false) are ignored here — the controller's per-tick path keeps shipping
 *    Progress packets against the same routeId, and any deviation triggers a fresh
 *    newRouteIsCalculated(true) once OsmAnd recomputes.
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
        if (!newRoute) {
            Log.d(TAG, "newRouteIsCalculated(false) — ignoring (recalculation)")
            return
        }
        if (!controller.hasPairedDevice()) {
            Log.d(TAG, "newRouteIsCalculated(true) — no paired Glass MAC configured; skipping")
            return
        }
        Log.i(TAG, "newRouteIsCalculated(true) — starting Glass stream")
        controller.startStreaming()
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
