package net.osmand.plus.plugins.glassnav

import android.util.Log
import com.goodanser.osmglass.protocol.Packet
import com.goodanser.osmglass.protocol.TurnKind
import com.goodanser.osmglass.protocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.osmand.Location
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.glassnav.render.LatLng
import net.osmand.plus.plugins.glassnav.render.OsmAndSnippetRenderer
import net.osmand.plus.plugins.glassnav.render.Turn
import net.osmand.plus.plugins.glassnav.render.TurnTypeMapping
import net.osmand.plus.plugins.glassnav.transport.TransportFactory
import net.osmand.plus.routing.NextDirectionInfo
import net.osmand.plus.routing.RouteCalculationResult
import net.osmand.plus.routing.RouteDirectionInfo
import net.osmand.plus.routing.RoutingHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the BLE-side streaming pipeline. Owned by [GlassNavPlugin]; invoked from
 * [GlassNavRoutingListener] when OsmAnd's [RoutingHelper] reports route start / progress / end.
 *
 * Pipeline (cf. phone-app's [com.goodanser.osmglass.phone.ride.RideService]):
 *   - [startStreaming]: build [TransportFactory] transport, on connect send [Packet.RouteStart]
 *     and one [Packet.TurnBundle] per [RouteDirectionInfo].
 *   - [onRoutingDataUpdate]: per OsmAnd routing tick, emit one [Packet.Progress]; on turn-index
 *     change, emit a [Packet.TurnAlert] too.
 *   - [stopStreaming]: stop transport, cancel coroutine scope, reset state.
 *
 * In-process port differences from RideService:
 *   - Foreground service is [GlassStreamingService] (FGS type `connectedDevice`); the controller
 *     starts it in [startStreaming] and stops it in [stopStreaming]. The service holds no state —
 *     transport ownership stays here, in the plugin scope.
 *   - No AIDL fork-path / phone GPS pipeline — RoutingHelper accessors are the single source.
 *   - No phone-side off-route detection — RoutingHelper.isDeviatedFromRoute() drives [Packet.RouteEnd].
 *   - Snippet bitmaps are currently empty PNGs because [OsmAndSnippetRenderer.renderBitmap] is
 *     stubbed; TurnBundles still ship with valid TurnKind / instruction / distance.
 *
 * Thread safety: all transport writes are funnelled through [scope] (single supervisor job,
 * Dispatchers.IO). FrameWriter inside RfcommTransport is internally synchronized, so concurrent
 * writes are technically safe but we prefer a single dispatcher to keep ordering predictable.
 */
class GlassNavController(
    private val app: OsmandApplication,
    /** Backs paired-MAC lookup and the per-connect DisplayConfig push. */
    private val settings: GlassNavSettings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: Transport? = null
    private val streaming = AtomicBoolean(false)

    @Volatile private var routeId: Long = 0L
    @Volatile private var connected: Boolean = false
    @Volatile private var portedTurns: List<Turn> = emptyList()
    @Volatile private var destinationLabel: String = ""
    /** Last turn index we emitted on a Progress packet — used to fire [Packet.TurnAlert] on
     *  segment transitions. */
    @Volatile private var lastTurnIndex: Int = -1
    private var pushTurnsJob: Job? = null

    /** True iff a paired MAC is configured. Called by the listener to short-circuit
     *  newRouteIsCalculated when the user hasn't set up Glass yet. */
    fun hasPairedDevice(): Boolean = settings.pairedMacOrNull != null

    /**
     * Open the transport (if not already open), publish the current route as RouteStart +
     * N×TurnBundle, and arm the per-tick Progress path. Idempotent — calling twice while
     * streaming is a no-op.
     */
    fun startStreaming() {
        if (!streaming.compareAndSet(false, true)) {
            Log.d(TAG, "startStreaming: already streaming")
            return
        }
        val rh = app.routingHelper
        if (!rh.isRouteCalculated) {
            Log.w(TAG, "startStreaming: no calculated route — bailing")
            streaming.set(false)
            return
        }
        val route = rh.route
        val mac = settings.pairedMacOrNull
        if (mac.isNullOrBlank()) {
            Log.w(TAG, "startStreaming: no paired MAC (settings not configured); skipping")
            streaming.set(false)
            return
        }
        routeId = freshRouteId()
        lastTurnIndex = -1
        destinationLabel = computeDestinationLabel(rh)
        portedTurns = buildTurns(route)
        Log.i(TAG, "startStreaming: routeId=$routeId, turns=${portedTurns.size}, dest=$destinationLabel")

        // Start the FGS before opening transport so the process is pinned for the duration of the
        // ride even if OsmAnd's MapActivity goes to background.
        GlassStreamingService.start(app)
        when (val r = TransportFactory.create(app, mac)) {
            is TransportFactory.CreateResult.Failed -> {
                Log.w(TAG, "transport unavailable: ${r.reason}")
                streaming.set(false)
                GlassStreamingService.stop(app)
            }
            is TransportFactory.CreateResult.Ok -> {
                Log.i(TAG, "transport: ${r.description}")
                val t = r.transport
                transport = t
                t.setListener(object : Transport.Listener {
                    override fun onConnected() {
                        Log.i(TAG, "transport connected")
                        connected = true
                        pushDisplayConfig(t)
                        pushTurnsJob?.cancel()
                        pushTurnsJob = scope.launch { pushRoute(t) }
                    }
                    override fun onPacket(p: Packet) {
                        // Glass currently sends nothing the controller cares about; keepalive is
                        // handled inside RfcommTransport.
                    }
                    override fun onDisconnected(cause: Throwable?) {
                        Log.w(TAG, "transport disconnected: ${cause?.message ?: "EOF"}")
                        connected = false
                    }
                })
                try {
                    t.start()
                } catch (e: Exception) {
                    Log.w(TAG, "transport.start failed", e)
                    streaming.set(false)
                    GlassStreamingService.stop(app)
                }
            }
        }
    }

    /**
     * Stop the active pipeline. Sends [Packet.RouteEnd] with [Packet.RouteEnd.Reason.CANCELLED]
     * if the transport is still up. Idempotent.
     */
    fun stopStreaming() {
        if (!streaming.compareAndSet(true, false)) return
        Log.i(TAG, "stopStreaming")
        val t = transport
        val id = routeId
        if (t != null && connected && id != 0L) {
            try {
                t.send(Packet.RouteEnd(id, Packet.RouteEnd.Reason.CANCELLED))
            } catch (e: Exception) {
                Log.w(TAG, "stopStreaming: send RouteEnd failed", e)
            }
        }
        pushTurnsJob?.cancel()
        pushTurnsJob = null
        try { t?.stop() } catch (_: Throwable) {}
        transport = null
        connected = false
        portedTurns = emptyList()
        routeId = 0L
        lastTurnIndex = -1
        // Drop the FGS after the transport is fully closed so the OS doesn't reap us mid-shutdown.
        GlassStreamingService.stop(app)
    }

    /**
     * Called from [GlassNavRoutingListener.onRoutingDataUpdate]. Builds a [Packet.Progress] from
     * the current [RoutingHelper] state and writes it to the transport. On turn-index change,
     * also emits a [Packet.TurnAlert]. No-op if we aren't streaming yet or the transport is down.
     */
    fun onRoutingDataUpdate(rh: RoutingHelper) {
        if (!streaming.get()) return
        val t = transport ?: return
        if (!connected) return
        val id = routeId
        if (id == 0L) return
        if (rh.isDeviatedFromRoute) {
            // For now we end the route on deviation; rerouting + re-streaming is a later step.
            // OsmAnd itself will recalculate and fire newRouteIsCalculated(true) again, which
            // re-arms startStreaming via the listener.
            Log.i(TAG, "deviated — sending RouteEnd(OFFROUTE)")
            scope.launch {
                try {
                    t.send(Packet.RouteEnd(id, Packet.RouteEnd.Reason.OFFROUTE))
                } catch (e: Exception) {
                    Log.w(TAG, "send RouteEnd(OFFROUTE) failed", e)
                }
            }
            return
        }

        val progress = buildProgress(rh, id) ?: return
        val turnIdx = progress.turnIndex
        val turnChanged = lastTurnIndex != -1 && turnIdx != lastTurnIndex
        lastTurnIndex = turnIdx

        scope.launch {
            try {
                t.send(progress)
                if (turnChanged) {
                    t.send(Packet.TurnAlert(id, turnIdx))
                }
                if (turnIdx == portedTurns.lastIndex.coerceAtLeast(0)
                    && progress.distanceToTurnM == 0
                ) {
                    Log.i(TAG, "arrived — sending RouteEnd(ARRIVED)")
                    t.send(Packet.RouteEnd(id, Packet.RouteEnd.Reason.ARRIVED))
                }
            } catch (e: Exception) {
                Log.w(TAG, "send Progress failed", e)
            }
        }
    }

    /** Send RouteStart + one TurnBundle per ported turn over the transport. Runs on [scope]. */
    private suspend fun pushRoute(t: Transport) {
        try {
            val turns = portedTurns
            t.send(Packet.RouteStart(routeId, turns.size, destinationLabel))
            Log.i(TAG, "sent ROUTE_START id=$routeId turns=${turns.size}")
            pushSnippets(t)
        } catch (e: Exception) {
            Log.w(TAG, "pushRoute failed", e)
        }
    }

    /** Render snippets for the current [portedTurns] using the current orientation pref and
     *  send a TurnBundle for each turn. Glass's PacketDispatcher keys its TurnBundle cache by
     *  (routeId, turnIndex), so re-sending overwrites the cached bitmap and the next Progress
     *  packet picks it up. */
    private suspend fun pushSnippets(t: Transport) {
        val turns = portedTurns
        val track = currentTrack()
        val snippets = OsmAndSnippetRenderer(app)
            .render(turns, track, settings.mapOrientation.get())
        for ((idx, turn) in turns.withIndex()) {
            val png = snippets.getOrNull(idx)?.pngBytes ?: EMPTY_BYTES
            t.send(
                Packet.TurnBundle(
                    routeId,
                    idx,
                    turn.kind,
                    turn.distanceFromStartM,
                    turn.instruction,
                    png,
                ),
            )
            Log.d(TAG, "sent TURN_BUNDLE #$idx (${turn.kind}, ${png.size}B)")
        }
    }

    /** Push the current Glass-side DisplayConfig (top/bottom slot, TTS mute) from settings.
     *  Safe to call any time the transport is up. */
    private fun pushDisplayConfig(t: Transport) {
        try {
            t.send(
                Packet.DisplayConfig(
                    settings.topSlot.get(),
                    settings.bottomSlot.get(),
                    settings.ttsMuted.get(),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "send DisplayConfig failed", e)
        }
    }

    /**
     * Called by the settings fragment after the user changes any of the prefs. If a transport
     * is live, re-pushes [Packet.DisplayConfig] immediately so the change is visible mid-ride;
     * if an active route exists, also re-renders + re-pushes snippets to pick up an orientation
     * change. No-op without a live transport.
     */
    fun onSettingsChanged() {
        val t = transport ?: return
        if (!connected) return
        pushDisplayConfig(t)
        if (streaming.get() && routeId != 0L) {
            scope.launch {
                try {
                    pushSnippets(t)
                } catch (e: Exception) {
                    Log.w(TAG, "onSettingsChanged: pushSnippets failed", e)
                }
            }
        }
    }

    /** Snapshot of the route's full polyline. Empty if the route isn't usable. */
    private fun currentTrack(): List<LatLng> {
        val route = app.routingHelper.route ?: return emptyList()
        val locs = route.immutableAllLocations ?: return emptyList()
        return locs.map { LatLng(it.latitude, it.longitude) }
    }

    /**
     * Translate OsmAnd's [RouteDirectionInfo] list into the controller's local [Turn] model.
     * Skips directions whose [net.osmand.router.TurnType] doesn't map to a [TurnKind] (continue,
     * off-route, unknown) — those aren't meaningful as standalone turn bundles.
     */
    private fun buildTurns(route: RouteCalculationResult): List<Turn> {
        val directions = route.immutableAllDirections ?: return emptyList()
        val out = ArrayList<Turn>(directions.size)
        var cumulative = 0
        var seq = 0
        for (dir in directions) {
            val kind: TurnKind? = TurnTypeMapping.fromOsmAndTurnType(dir.turnType)
            val loc: Location? = route.getLocationFromRouteDirection(dir)
            cumulative += dir.distance.coerceAtLeast(0)
            if (kind == null || loc == null) continue
            val instruction = dir.getDescriptionRoute(app) ?: ""
            out += Turn(
                seq = seq++,
                lat = loc.latitude,
                lon = loc.longitude,
                kind = kind,
                distanceFromStartM = cumulative.coerceIn(0, 0xffff),
                instruction = instruction,
            )
        }
        return out
    }

    /**
     * Build a [Packet.Progress] from the [RoutingHelper] accessors. Returns null if there isn't
     * enough state yet (no fix, no route).
     */
    private fun buildProgress(rh: RoutingHelper, id: Long): Packet.Progress? {
        val remainingM = rh.leftDistance.coerceIn(0, 0xffff)
        val etaSec = rh.leftTime.coerceIn(0, 0xffff)
        val lastLoc: Location? = rh.lastFixedLocation
        val speedKmh = if (lastLoc != null && lastLoc.hasSpeed()) {
            (lastLoc.speed * 3.6f).toInt().coerceIn(0, 0xffff)
        } else 0

        val nextDir: NextDirectionInfo? = try {
            rh.getNextRouteDirectionInfo(NextDirectionInfo(), false)
        } catch (_: Throwable) {
            null
        }
        val distToTurnM = (nextDir?.distanceTo ?: 0).coerceIn(0, 0xffff)
        val turnIdx = (nextDir?.directionInfoInd ?: 0).coerceAtLeast(0)

        return Packet.Progress(
            id,
            turnIdx,
            distToTurnM,
            /* bearingDelta100 = */ 0,
            speedKmh,
            remainingM,
            etaSec,
            Packet.Progress.MARKER_NONE,
            Packet.Progress.MARKER_NONE,
            Packet.Progress.MARKER_NONE,
        )
    }

    private fun computeDestinationLabel(rh: RoutingHelper): String {
        val tps = app.targetPointsHelper
        val end = tps?.pointToNavigate
        val name = end?.getOnlyName()
        if (!name.isNullOrBlank()) return name
        val finalLatLon = rh.finalLocation
        return if (finalLatLon != null) {
            "${"%.4f".format(finalLatLon.latitude)}, ${"%.4f".format(finalLatLon.longitude)}"
        } else ""
    }

    private fun freshRouteId(): Long = System.currentTimeMillis() and 0xffffffffL

    /** Cancel the coroutine scope. Call from plugin's disable() — after this point the controller
     *  is dead and a new instance is needed to resume. */
    fun shutdown() {
        stopStreaming()
        scope.cancel()
    }

    companion object {
        private const val TAG = "GlassNavController"
        private val EMPTY_BYTES = ByteArray(0)
    }
}
