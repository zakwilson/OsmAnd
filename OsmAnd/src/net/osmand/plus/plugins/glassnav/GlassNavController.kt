package net.osmand.plus.plugins.glassnav

import android.util.Log
import com.goodanser.osmglass.protocol.Packet
import com.goodanser.osmglass.protocol.TurnKind
import com.goodanser.osmglass.protocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import net.osmand.Location
import net.osmand.StateChangedListener
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.enums.DayNightMode
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.plugins.glassnav.render.LatLng
import net.osmand.plus.plugins.glassnav.render.MapOrientation
import net.osmand.plus.plugins.glassnav.render.OsmAndSnippetRenderer
import net.osmand.plus.plugins.glassnav.render.SnippetBounds
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
 * Thread safety / ordering: all transport writes are funnelled through a single [writerJob]
 * coroutine that drains two channels (see [startWriter]):
 *   - [controlChannel] (UNLIMITED, reliable): RouteStart / TurnBundle / TurnAlert / RouteEnd /
 *     DisplayConfig — none may be dropped.
 *   - [progressChannel] (CONFLATED, latest-wins): per-tick [Packet.Progress]. Position is
 *     latest-wins data, so when the link is busy (e.g. mid-snippet PNG burst at route start) we
 *     shed stale positions instead of queuing them, which is what kept the Glass marker lagging
 *     behind the phone (glass-nav-lx5). The single writer also guarantees in-order delivery —
 *     the previous code launched a coroutine per tick on multi-threaded [Dispatchers.IO], so
 *     Progress packets raced for the FrameWriter lock and could be written out of order.
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
    /** Maps OsmAnd's raw direction index (the space [NextDirectionInfo.directionInfoInd] lives in,
     *  i.e. [RouteCalculationResult.getImmutableAllDirections]) to the compacted [portedTurns]
     *  index used to key TurnBundles. [buildTurns] skips non-maneuver directions, so the two
     *  index spaces diverge; see [compactTurnIndex]. */
    @Volatile private var rawToCompacted: IntArray = IntArray(0)
    @Volatile private var destinationLabel: String = ""
    /** Departure instruction ("Head … on …") for the route's first leg, captured from the skipped
     *  departure direction and shipped on [Packet.RouteStart.startLabel] for Glass's initial cue. */
    @Volatile private var startLabel: String = ""
    /** Last turn index we emitted on a Progress packet — used to fire [Packet.TurnAlert] on
     *  segment transitions, including the first turn (transition from the -1 reset state). */
    @Volatile private var lastTurnIndex: Int = -1
    /** Snippet bounds keyed by turn index, captured at [prewarmSnippets]. Used per Progress packet
     *  to project the live position arrow into the cached TurnBundle's bitmap. */
    @Volatile private var snippetBoundsByTurn: List<SnippetBounds?> = emptyList()
    private var pushTurnsJob: Job? = null

    /**
     * Pre-warm cache: one [CompletableDeferred] per turn, filled by [prewarmJob] as each snippet
     * finishes rendering. Started at route-calculation (planning) time in [prepareRouteState] so the
     * rider's route-review window doubles as render head-start and OsmAnd's map tiles for the route
     * are warm before navigation begins. [pushSnippets] awaits these in order: a slot already filled
     * during planning returns instantly, an in-flight one is awaited — so the transport never blocks
     * on a cold render (glass-nav-kt7). Keyed by [prewarmRouteId] + [prewarmOrientation]; a mismatch
     * (new route, or orientation changed via [onSettingsChanged]) forces a re-render.
     */
    @Volatile private var prewarmSlots: List<CompletableDeferred<OsmAndSnippetRenderer.Snippet>> =
        emptyList()
    @Volatile private var prewarmRouteId: Long = 0L
    @Volatile private var prewarmOrientation: MapOrientation? = null
    /** Effective map night-mode ([ThemeUsageContext.MAP]) the cached snippets were rendered for.
     *  Part of the prewarm cache key so a phone day/night change invalidates the bitmaps and forces
     *  a re-render in the new theme (glass-nav-lsl). Null until the first render. */
    @Volatile private var prewarmNightMode: Boolean? = null
    private var prewarmJob: Job? = null

    /** Debounces map-theme-change re-renders so a quick double-toggle (or AUTO flicker) coalesces
     *  into a single tile burst over the slow Glass link. */
    private var themeChangeJob: Job? = null

    /** Re-render + re-push snippets when the phone's map day/night theme changes mid-ride, so the
     *  Glass tiles keep matching the phone (glass-nav-lsl). Held as a strong-ref field because
     *  [net.osmand.plus.settings.backend.preferences.PreferenceWithListener] stores listeners
     *  weakly — a local lambda would be GC'd and silently stop firing. */
    private val dayNightListener = StateChangedListener<DayNightMode> { onMapThemeChanged() }

    init {
        app.settings.DAYNIGHT_MODE.addListener(dayNightListener)
    }

    /** Single ordered outbound path; see the class doc. Both channels and [writerJob] live for the
     *  duration of one transport connection — created in [startWriter] (on connect), torn down in
     *  [stopWriter]. Reliable control packets and conflated Progress packets are funnelled here so
     *  the actual socket writes happen on one coroutine, in submission order. */
    @Volatile private var controlChannel: Channel<Packet>? = null
    @Volatile private var progressChannel: Channel<Packet.Progress>? = null
    private var writerJob: Job? = null

    /** True iff a paired MAC is configured. Called by the listener to short-circuit
     *  newRouteIsCalculated when the user hasn't set up Glass yet. */
    fun hasPairedDevice(): Boolean = settings.pairedMacOrNull != null

    /** Verbose per-route/per-tick diagnostics (turn list, progress mapping, departure) are gated
     *  on the plugin's "Debug logging" setting — off by default since they're noisy. */
    private fun debugLogging(): Boolean = settings.debugLogging.get()

    /**
     * Called when OsmAnd (re)calculates a route. OsmAnd raises `newRouteIsCalculated(true)` during
     * route preview — before the rider taps "Go" — so this does NOT immediately publish to Glass.
     * It refreshes the route state and kicks off snippet pre-rendering (so tiles warm during the
     * review window), then defers the actual transport open + RouteStart/TurnBundle push to
     * [maybeStartPublishing], which fires only once [RoutingHelper.isFollowingMode] is true.
     * Publishing during preview is what made Glass speak the initial direction and show a card
     * before navigation had started (glass-nav-kt7).
     *
     * If a transport is already up streaming an earlier route — e.g. the rider set a new
     * destination mid-ride, so OsmAnd recalculates while the Glass is already navigating — this
     * re-publishes the new route over the live transport instead (see [republishRoute]).
     */
    fun startStreaming() {
        val rh = app.routingHelper
        if (!rh.isRouteCalculated) {
            Log.w(TAG, "startStreaming: no calculated route — bailing")
            return
        }
        if (settings.pairedMacOrNull.isNullOrBlank()) {
            Log.w(TAG, "startStreaming: no paired MAC (settings not configured); skipping")
            return
        }

        // Refresh route state and pre-render snippets now (runs during preview too).
        prepareRouteState(rh)
        Log.i(TAG, "routeCalculated: routeId=$routeId, turns=${portedTurns.size}, dest=$destinationLabel, following=${rh.isFollowingMode}")

        if (streaming.get()) {
            // Already publishing a prior route — push the freshly-prepared route over the live link.
            republishRoute()
            return
        }
        // Otherwise hold off until the rider actually starts navigating.
        maybeStartPublishing(rh)
    }

    /**
     * Called when OsmAnd recomputes a route over an already-calculated one — i.e. a reroute after
     * the rider deviated (OsmAnd reports these as `newRouteIsCalculated(false)`; see
     * [net.osmand.plus.routing.RouteRecalculationHelper.setNewRoute]). Only meaningful while we're
     * actively streaming to Glass: refresh the route state (new routeId, ported turns, snippet
     * bounds, index map) off the new [RouteCalculationResult] and re-publish over the live transport
     * so Glass clears the stale route's TurnBundle cache (keyed on routeId) and shows the new turns
     * and tiles. Without this the controller kept the pre-deviation route and the Glass tiles never
     * tracked the rider's progress on the new route (glass-nav-0s1).
     *
     * No-op when not streaming: a recompute during route preview (e.g. an avoid-roads toggle) is
     * handled by the eventual [startStreaming]/[maybeStartPublishing] path when the rider taps "Go".
     */
    fun onRouteRecalculated() {
        if (!streaming.get()) return
        val rh = app.routingHelper
        if (!rh.isRouteCalculated) {
            Log.w(TAG, "onRouteRecalculated: no calculated route — bailing")
            return
        }
        prepareRouteState(rh)
        Log.i(TAG, "reroute: routeId=$routeId, turns=${portedTurns.size}, dest=$destinationLabel")
        republishRoute()
    }

    /**
     * Open the transport and begin publishing the prepared route, but only once the rider has
     * actually started navigating ([RoutingHelper.isFollowingMode]). No-op during route preview,
     * when already publishing, or before the route is prepared. Invoked from [startStreaming] (in
     * case navigation was already underway when the route was calculated, e.g. a mid-ride reroute)
     * and from [onRoutingDataUpdate] (the first location tick after the rider taps "Go").
     */
    private fun maybeStartPublishing(rh: RoutingHelper) {
        if (!rh.isFollowingMode) return
        val mac = settings.pairedMacOrNull
        if (mac.isNullOrBlank()) return
        if (routeId == 0L) {
            // Navigation started without a prepared route (newRouteIsCalculated not seen yet) —
            // prepare + prewarm now so we have something to publish.
            if (!rh.isRouteCalculated) return
            prepareRouteState(rh)
        }
        if (!streaming.compareAndSet(false, true)) return
        Log.i(TAG, "startPublishing: routeId=$routeId, turns=${portedTurns.size}")
        openTransport(mac)
    }

    /** Start the FGS and open the transport; on connect, publish DisplayConfig + the prepared
     *  route. Resets the [streaming] gate on failure so a later tick can retry. */
    private fun openTransport(mac: String) {
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
                        startWriter(t)
                        pushDisplayConfig()
                        pushTurnsJob?.cancel()
                        pushTurnsJob = scope.launch { pushRoute() }
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
     * Recompute the controller's route state (routeId, destination, ported turns, index map) from
     * the current [RoutingHelper]. Caller must hold the conceptual "streaming" gate. Does not touch
     * the transport — [startStreaming]/[republishRoute] handle publishing.
     */
    private fun prepareRouteState(rh: RoutingHelper) {
        val route = rh.route
        routeId = freshRouteId()
        lastTurnIndex = -1
        snippetBoundsByTurn = emptyList()
        destinationLabel = computeDestinationLabel(rh)
        // The first direction is the departure (the street the rider starts on); buildTurns skips
        // it (TurnType.C maps to no maneuver). Capture its street NAME for Glass's initial cue —
        // getDescriptionRoute returns just "Head <dist>" with no name, so use getStreetName()/ref.
        val departure = route?.immutableAllDirections?.firstOrNull()
        startLabel = departure?.streetName?.takeIf { it.isNotBlank() }
            ?: departure?.ref?.takeIf { it.isNotBlank() }
            ?: ""
        if (debugLogging()) {
            Log.i(TAG, "departure: street=\"${departure?.streetName}\" ref=\"${departure?.ref}\""
                + " desc=\"${departure?.getDescriptionRoute(app)}\" -> startLabel=\"$startLabel\"")
        }
        val built = buildTurns(route)
        portedTurns = built.turns
        rawToCompacted = built.rawToCompacted
        // Kick off snippet rendering now, at route-calculation time, rather than waiting for the
        // transport to connect. startStreaming fires on newRouteIsCalculated — which OsmAnd raises
        // during route planning/preview — so this warms the map tiles and pre-renders the bitmaps
        // while the rider is still reviewing the route, before navigation starts.
        prewarmSnippets()
    }

    /**
     * Begin rendering every turn's snippet for the freshly-prepared route on [scope], publishing
     * each result into [prewarmSlots] as it completes. Captures [routeId]/orientation so
     * [pushSnippets] can tell whether the cache still matches. Cheap to call eagerly: the bounds are
     * pure polyline math (microseconds) and the bitmap renders run off-thread; [pushSnippets] is
     * what actually ships them once the transport is up.
     */
    private fun prewarmSnippets() {
        val turns = portedTurns
        val track = currentTrack()
        val orientation = settings.mapOrientation.get()
        val renderer = OsmAndSnippetRenderer(app)
        // Bounds are pure polyline math (microseconds), so set them synchronously — the very first
        // Progress packet can then project a marker even before any bitmap has finished rendering.
        snippetBoundsByTurn = renderer.computeBounds(turns, track, orientation)
        val slots = List(turns.size) { CompletableDeferred<OsmAndSnippetRenderer.Snippet>() }
        prewarmSlots = slots
        prewarmRouteId = routeId
        prewarmOrientation = orientation
        // Record the theme the renderer will draw in (FixedTileBoxTrackDrawer reads the same
        // MAP context) so pushSnippets can detect a later day/night change and re-render.
        prewarmNightMode = app.daynightHelper.isNightMode(ThemeUsageContext.MAP)
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            try {
                renderer.renderEach(turns, track, orientation) { idx, snippet ->
                    slots[idx].complete(snippet)
                }
            } catch (e: Exception) {
                Log.w(TAG, "prewarmSnippets render failed", e)
            } finally {
                // Defensively settle any slot the renderer didn't reach (early throw) so a waiting
                // pushSnippets can't hang; an empty PNG just ships a bundle with no bitmap.
                slots.forEach { if (!it.isCompleted) it.complete(EMPTY_SNIPPET) }
            }
        }
    }

    /**
     * Re-publish the freshly-prepared route over an already-open transport: cancel any in-flight
     * push, then (if connected) re-push DisplayConfig + RouteStart + TurnBundles for the new
     * routeId. If the transport hasn't connected yet, the pending `onConnected` handler will
     * pushRoute with the new state, so we leave it alone.
     */
    private fun republishRoute() {
        transport ?: return
        pushTurnsJob?.cancel()
        if (!connected) return
        pushDisplayConfig()
        pushTurnsJob = scope.launch { pushRoute() }
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
        pushTurnsJob?.cancel()
        pushTurnsJob = null
        prewarmJob?.cancel()
        prewarmJob = null
        themeChangeJob?.cancel()
        themeChangeJob = null
        // Tear the writer down first so it can't race the final RouteEnd, then send RouteEnd
        // directly (synchronously) before closing the transport — it must not be dropped.
        stopWriter()
        if (t != null && connected && id != 0L) {
            try {
                t.send(Packet.RouteEnd(id, Packet.RouteEnd.Reason.CANCELLED))
            } catch (e: Exception) {
                Log.w(TAG, "stopStreaming: send RouteEnd failed", e)
            }
        }
        try { t?.stop() } catch (_: Throwable) {}
        transport = null
        connected = false
        portedTurns = emptyList()
        rawToCompacted = IntArray(0)
        snippetBoundsByTurn = emptyList()
        prewarmSlots = emptyList()
        prewarmRouteId = 0L
        prewarmOrientation = null
        prewarmNightMode = null
        routeId = 0L
        lastTurnIndex = -1
        // Drop the FGS after the transport is fully closed so the OS doesn't reap us mid-shutdown.
        GlassStreamingService.stop(app)
    }

    /**
     * Called from [GlassNavRoutingListener.onRoutingDataUpdate]. Builds a [Packet.Progress] from
     * the current [RoutingHelper] state and writes it to the transport. On turn-index change,
     * also emits a [Packet.TurnAlert].
     *
     * Also the navigation-start trip-wire: there's no OsmAnd callback for "following mode began",
     * so when we aren't publishing yet we poll [RoutingHelper.isFollowingMode] here (location ticks
     * only flow once the rider is moving/navigating) and open the transport on the first tick after
     * "Go". This is what keeps RouteStart / voice / display off Glass during route preview.
     */
    fun onRoutingDataUpdate(rh: RoutingHelper) {
        if (!streaming.get()) {
            maybeStartPublishing(rh)
            return
        }
        if (!connected) return
        val id = routeId
        if (id == 0L) return
        if (rh.isDeviatedFromRoute) {
            // Signal the deviation to Glass now; we keep streaming/transport up. OsmAnd recomputes
            // and fires newRouteIsCalculated(false) (a recompute over an existing route), which the
            // listener routes to onRouteRecalculated → prepareRouteState + republishRoute, so the
            // new route's RouteStart/TurnBundles/Progress resume once the reroute lands.
            Log.i(TAG, "deviated — sending RouteEnd(OFFROUTE)")
            enqueueControl(Packet.RouteEnd(id, Packet.RouteEnd.Reason.OFFROUTE))
            return
        }

        val progress = buildProgress(rh, id) ?: return
        val turnIdx = progress.turnIndex
        // Fire on every index change, including the first tick (lastTurnIndex == -1). The first
        // turn has no preceding segment to "transition" from, but it still needs a TurnAlert so
        // Glass surfaces the card and shows the first turn's snippet from the start of the leg —
        // without it the first turn's map tile only appeared once the rider crossed the distance
        // approach threshold, so on most trips it never showed (glass-nav-kt7).
        val turnChanged = turnIdx != lastTurnIndex
        lastTurnIndex = turnIdx

        // Progress is conflated (latest-wins): if the link is busy the stale tick is dropped so the
        // marker never falls behind. TurnAlert / RouteEnd are reliable control packets.
        enqueueProgress(progress)
        if (turnChanged) {
            enqueueControl(Packet.TurnAlert(id, turnIdx))
        }
        if (turnIdx == portedTurns.lastIndex.coerceAtLeast(0) && progress.distanceToTurnM == 0) {
            Log.i(TAG, "arrived — sending RouteEnd(ARRIVED)")
            enqueueControl(Packet.RouteEnd(id, Packet.RouteEnd.Reason.ARRIVED))
        }
    }

    /** Enqueue RouteStart + one TurnBundle per ported turn onto the control channel. Runs on
     *  [scope] because [pushSnippets] renders bitmaps; the actual writes happen on [writerJob]. */
    private suspend fun pushRoute() {
        try {
            val turns = portedTurns
            enqueueControl(Packet.RouteStart(routeId, turns.size, destinationLabel, startLabel))
            Log.i(TAG, "queued ROUTE_START id=$routeId turns=${turns.size} from=\"$startLabel\"")
            pushSnippets()
        } catch (e: Exception) {
            Log.w(TAG, "pushRoute failed", e)
        }
    }

    /** Send a TurnBundle for each turn, drawing each snippet from the pre-warm cache started in
     *  [prepareRouteState]. A slot rendered during planning resolves instantly; an in-flight one is
     *  awaited, so a turn ships as soon as its bitmap is ready. Bundles are sent starting from the
     *  rider's current turn and wrapping around (see [sendOrder]) so the turn they're actually
     *  approaching reaches Glass first instead of queuing behind already-passed turns on the slow
     *  link — the snippet bitmaps are heavy, so a passed turn sent first left the upcoming turn blank
     *  through its whole approach (glass-nav-kt7). Glass's PacketDispatcher keys its TurnBundle cache
     *  by (routeId, turnIndex), so order of arrival doesn't matter to correctness. */
    private suspend fun pushSnippets() {
        val turns = portedTurns
        val id = routeId
        val orientation = settings.mapOrientation.get()
        val nightMode = app.daynightHelper.isNightMode(ThemeUsageContext.MAP)
        // Reuse the prewarm render when it still matches this route + orientation + map theme;
        // otherwise (new route that skipped prepareRouteState, an orientation change from
        // onSettingsChanged, or a day/night change from onMapThemeChanged) re-render now.
        // prewarmSnippets resets the slots and the snippetBoundsByTurn projection.
        if (prewarmRouteId != id || prewarmOrientation != orientation
            || prewarmSlots.size != turns.size || prewarmNightMode != nightMode) {
            prewarmSnippets()
        }
        val slots = prewarmSlots
        for (idx in sendOrder(turns.size, currentTurnIndex())) {
            val turn = turns[idx]
            val png = slots.getOrNull(idx)?.await()?.pngBytes ?: EMPTY_BYTES
            enqueueControl(
                Packet.TurnBundle(
                    id,
                    idx,
                    turn.kind,
                    turn.distanceFromStartM,
                    turn.instruction,
                    png,
                ),
            )
            Log.d(TAG, "queued TURN_BUNDLE #$idx (${turn.kind}, ${png.size}B)")
        }
    }

    /** The rider's current/upcoming compacted turn index from [RoutingHelper], or 0 if unknown
     *  (e.g. publishing started before the first location fix). Mirrors [buildProgress]. */
    private fun currentTurnIndex(): Int {
        val nextDir: NextDirectionInfo? = try {
            app.routingHelper.getNextRouteDirectionInfo(NextDirectionInfo(), false)
        } catch (_: Throwable) {
            null
        }
        return compactTurnIndex(nextDir?.directionInfoInd ?: -1)
    }

    /** Turn-send order for [count] turns starting at [start] and wrapping: e.g. count=5, start=2 →
     *  [2,3,4,0,1]. Empty when [count] is 0. Visible for testing. */
    private fun sendOrder(count: Int, start: Int): List<Int> {
        if (count <= 0) return emptyList()
        val from = start.coerceIn(0, count - 1)
        return (from until count) + (0 until from)
    }

    /** Push the current Glass-side DisplayConfig (four corner slots, TTS mute, screen-wake timeout)
     *  from settings. Safe to call any time the transport is up. */
    private fun pushDisplayConfig() {
        enqueueControl(
            Packet.DisplayConfig(
                settings.topLeftSlot.get(),
                settings.topRightSlot.get(),
                settings.bottomLeftSlot.get(),
                settings.bottomRightSlot.get(),
                settings.ttsMuted.get(),
                settings.screenWakeSec.get(),
            ),
        )
    }

    /**
     * Called by the settings fragment after the user changes any of the prefs. If a transport
     * is live, re-pushes [Packet.DisplayConfig] immediately so the change is visible mid-ride;
     * if an active route exists, also re-renders + re-pushes snippets to pick up an orientation
     * change. No-op without a live transport.
     */
    fun onSettingsChanged() {
        transport ?: return
        if (!connected) return
        pushDisplayConfig()
        if (streaming.get() && routeId != 0L) {
            scope.launch {
                try {
                    pushSnippets()
                } catch (e: Exception) {
                    Log.w(TAG, "onSettingsChanged: pushSnippets failed", e)
                }
            }
        }
    }

    /**
     * Fired by [dayNightListener] when the phone's [net.osmand.plus.settings.backend.OsmandSettings.DAYNIGHT_MODE]
     * changes. If an active route is streaming, re-render the snippets in the new map theme and
     * re-push them so the Glass tiles match the phone (glass-nav-lsl).
     *
     * Safeguards against pummelling the slow Glass link (and the fragile Glass GPU — see
     * glass-nav-yuf):
     *  - Debounced by [THEME_DEBOUNCE_MS] so a quick double-toggle / AUTO flicker collapses to one
     *    render+push.
     *  - No-ops when the *effective* [ThemeUsageContext.MAP] night mode hasn't actually moved since
     *    the cached render (e.g. picking the same mode again, or an app-theme flip that doesn't
     *    change the map theme), so we never re-send identical tiles.
     *  - [pushSnippets] still sends current-turn-first (see [sendOrder]), so the turn the rider is
     *    approaching refreshes first even though the whole set re-pushes.
     */
    private fun onMapThemeChanged() {
        if (!streaming.get() || !connected || routeId == 0L) return
        themeChangeJob?.cancel()
        themeChangeJob = scope.launch {
            delay(THEME_DEBOUNCE_MS)
            val night = app.daynightHelper.isNightMode(ThemeUsageContext.MAP)
            if (night == prewarmNightMode) return@launch
            Log.i(TAG, "map theme changed (night=$night) — re-rendering + re-pushing snippets")
            try {
                pushSnippets()
            } catch (e: Exception) {
                Log.w(TAG, "onMapThemeChanged: pushSnippets failed", e)
            }
        }
    }

    /** Snapshot of the route's full polyline. Empty if the route isn't usable. */
    private fun currentTrack(): List<LatLng> {
        val route = app.routingHelper.route ?: return emptyList()
        val locs = route.immutableAllLocations ?: return emptyList()
        return locs.map { LatLng(it.latitude, it.longitude) }
    }

    /** Result of [buildTurns]: the compacted maneuver list plus the raw→compacted index map. */
    private class BuiltRoute(val turns: List<Turn>, val rawToCompacted: IntArray)

    /**
     * Translate OsmAnd's [RouteDirectionInfo] list into the controller's local [Turn] model.
     * Skips directions whose [net.osmand.router.TurnType] doesn't map to a [TurnKind] (continue,
     * off-route, unknown) — those aren't meaningful as standalone turn bundles.
     *
     * Also builds [BuiltRoute.rawToCompacted], one entry per raw direction, recording the
     * compacted index of the maneuver at-or-after that raw direction. This is what lets
     * [compactTurnIndex] convert a live [NextDirectionInfo.directionInfoInd] (which indexes the
     * full, un-skipped direction list) back into the [portedTurns]/TurnBundle index space.
     */
    private fun buildTurns(route: RouteCalculationResult): BuiltRoute {
        val directions = route.immutableAllDirections ?: return BuiltRoute(emptyList(), IntArray(0))
        val out = ArrayList<Turn>(directions.size)
        val map = IntArray(directions.size)
        var cumulative = 0
        var seq = 0
        for ((rawIdx, dir) in directions.withIndex()) {
            // Record before the keep/skip decision: a kept direction maps to its own compacted
            // index (seq), a skipped one maps forward to the next maneuver the rider is heading
            // toward (also the current seq, which the next kept turn will claim).
            map[rawIdx] = seq
            val kind: TurnKind? = TurnTypeMapping.fromOsmAndTurnType(dir.turnType)
            val loc: Location? = route.getLocationFromRouteDirection(dir)
            if (kind != null && loc != null) {
                val instruction = dir.getDescriptionRoute(app) ?: ""
                out += Turn(
                    seq = seq++,
                    lat = loc.latitude,
                    lon = loc.longitude,
                    kind = kind,
                    // `cumulative` is the summed distance of all PRECEDING directions, i.e. the
                    // distance from the route start to this turn. RouteDirectionInfo.distance is the
                    // leg AFTER the turn ("after turn to next turn"), so it's added below — adding it
                    // before overstated every turn's distance-from-start by its own outgoing leg.
                    distanceFromStartM = cumulative.coerceIn(0, 0xffff),
                    instruction = instruction,
                )
            }
            cumulative += dir.distance.coerceAtLeast(0)
        }
        if (debugLogging()) {
            Log.i(TAG, "buildTurns: ${out.size} turns from ${directions.size} directions")
            for (t in out) {
                Log.i(TAG, "  turn #${t.seq} ${t.kind} @${t.distanceFromStartM}m \"${t.instruction}\"")
            }
        }
        return BuiltRoute(out, map)
    }

    /**
     * Convert a raw OsmAnd direction index — the space [NextDirectionInfo.directionInfoInd] lives
     * in ([RouteCalculationResult.getImmutableAllDirections]) — into the compacted [portedTurns]
     * index that keys TurnBundles on Glass.
     *
     * Necessary because [buildTurns] drops non-maneuver directions (the route's leading "continue"
     * start, mid-route straights, off-route markers), so the raw index runs ahead of the compacted
     * one. Without this translation Glass resolves the wrong TurnBundle — or none, once the raw
     * index outruns the bundle list near the end — and its display freezes behind the phone.
     *
     * A negative input (OsmAnd reports `directionInfoInd == -1` once past the final direction) maps
     * to the last maneuver, i.e. arrival.
     */
    private fun compactTurnIndex(rawDirectionInfoInd: Int): Int {
        val map = rawToCompacted
        val lastTurn = (portedTurns.size - 1).coerceAtLeast(0)
        if (map.isEmpty()) return 0
        if (rawDirectionInfoInd < 0) return lastTurn
        val raw = rawDirectionInfoInd.coerceIn(0, map.size - 1)
        return map[raw].coerceIn(0, lastTurn)
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
        val rawDirInd = nextDir?.directionInfoInd ?: -1
        val turnIdx = compactTurnIndex(rawDirInd)
        if (debugLogging()) {
            Log.d(TAG, "progress: rawDirInd=$rawDirInd -> turnIdx=$turnIdx distToTurn=${distToTurnM}m")
        }

        val (markerX, markerY, markerBearing) = if (lastLoc != null) {
            val bearingDeg = if (lastLoc.hasBearing()) lastLoc.bearing else null
            computeMarker(turnIdx, lastLoc.latitude, lastLoc.longitude, bearingDeg)
        } else {
            Triple(Packet.Progress.MARKER_NONE, Packet.Progress.MARKER_NONE, Packet.Progress.MARKER_NONE)
        }

        return Packet.Progress(
            id,
            turnIdx,
            distToTurnM,
            /* bearingDelta100 = */ 0,
            speedKmh,
            remainingM,
            etaSec,
            markerX,
            markerY,
            markerBearing,
        )
    }

    /**
     * Project the rider's current geographic position onto the snippet bitmap for [turnIndex].
     * Returns MARKER_NONE fields if no bounds are known for this turn (e.g. the snippet window
     * was empty). When the rider falls outside the bitmap, falls back to the polyline's entry
     * point (the start of the route line within the snippet).
     */
    private fun computeMarker(
        turnIndex: Int,
        lat: Double,
        lon: Double,
        bearingDeg: Float?,
    ): Triple<Int, Int, Int> {
        val bounds = snippetBoundsByTurn.getOrNull(turnIndex)
            ?: return Triple(Packet.Progress.MARKER_NONE, Packet.Progress.MARKER_NONE, Packet.Progress.MARKER_NONE)
        val live = bounds.project(lat, lon)
        val (px, py, rawBearing) = if (live.inBounds) {
            val b = bearingDeg?.let { wrap360(it.toDouble()) } ?: bounds.startBearingDeg
            Triple(live.x, live.y, b)
        } else {
            val fallback = bounds.project(bounds.startLat, bounds.startLon)
            Triple(fallback.x, fallback.y, bounds.startBearingDeg)
        }
        // In TRAVEL_UP, the snippet bitmap was rotated so the entry direction points up. The
        // marker arrow is drawn by Glass with a plain canvas.rotate, so we have to shift the
        // bearing into the rotated frame before sending.
        val finalBearing = bounds.transformBearing(rawBearing)
        return Triple(
            px.toInt().coerceIn(0, bounds.widthPx - 1),
            py.toInt().coerceIn(0, bounds.heightPx - 1),
            (wrap360(finalBearing) * 100.0).toInt().coerceIn(0, 35_999),
        )
    }

    private fun wrap360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
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

    // ---- Outbound writer ---------------------------------------------------------------------

    /**
     * Start the single ordered writer for [t]. Creates a fresh reliable control channel and a
     * conflated progress channel, then launches one coroutine that drains both — control with
     * priority (it's registered first in the [select]) so RouteStart/TurnBundles land before the
     * Progress packets that depend on them. Idempotent within a connection: tears down any prior
     * writer first.
     */
    private fun startWriter(t: Transport) {
        stopWriter()
        val control = Channel<Packet>(Channel.UNLIMITED)
        val progress = Channel<Packet.Progress>(Channel.CONFLATED)
        controlChannel = control
        progressChannel = progress
        writerJob = scope.launch {
            try {
                while (isActive) {
                    val packet = select<Packet> {
                        control.onReceive { it }
                        progress.onReceive { it }
                    }
                    try {
                        t.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "outbound send failed (${packet.javaClass.simpleName})", e)
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Channels closed by stopWriter — normal teardown, exit quietly.
            }
        }
    }

    /** Close the outbound channels and cancel the writer. Safe to call when no writer is running. */
    private fun stopWriter() {
        controlChannel?.close()
        progressChannel?.close()
        controlChannel = null
        progressChannel = null
        writerJob?.cancel()
        writerJob = null
    }

    /** Queue a reliable control packet (never dropped). No-op if the writer isn't up. */
    private fun enqueueControl(p: Packet) {
        controlChannel?.trySend(p)
    }

    /** Offer the latest Progress; conflation drops any unsent prior tick. No-op if no writer. */
    private fun enqueueProgress(p: Packet.Progress) {
        progressChannel?.trySend(p)
    }

    /** Cancel the coroutine scope. Call from plugin's disable() — after this point the controller
     *  is dead and a new instance is needed to resume. */
    fun shutdown() {
        stopStreaming()
        scope.cancel()
    }

    companion object {
        private const val TAG = "GlassNavController"
        /** Debounce for [onMapThemeChanged]: coalesce rapid day/night toggles into one re-render. */
        private const val THEME_DEBOUNCE_MS = 400L
        private val EMPTY_BYTES = ByteArray(0)
        /** Placeholder used to settle a prewarm slot the renderer never reached. */
        private val EMPTY_SNIPPET = OsmAndSnippetRenderer.Snippet(EMPTY_BYTES, null)
    }
}
