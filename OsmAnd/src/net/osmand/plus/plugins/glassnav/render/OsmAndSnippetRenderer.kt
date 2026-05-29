package net.osmand.plus.plugins.glassnav.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import net.osmand.plus.OsmandApplication
import net.osmand.plus.myplaces.tracks.MapBitmapDrawerListener
import net.osmand.plus.myplaces.tracks.MapDrawParams
import net.osmand.plus.myplaces.tracks.TrackBitmapDrawer
import net.osmand.plus.shared.SharedUtil
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders per-turn map snippets for the Glass live card. For each turn, slices the real route
 * polyline to a [WINDOW_M] window on each side of the turn point, asks OsmAnd to draw it as a
 * bitmap, and returns both the PNG bytes and the [SnippetBounds] used to project the live
 * position marker into the bitmap.
 *
 * If the OsmAnd bitmap source is unavailable, [render] returns empty PNGs for every turn —
 * callers will still ship TURN_BUNDLEs without an image attached. Bounds are still produced
 * from the polyline window so the position-marker pipeline keeps working even with placeholder
 * snippets.
 *
 * Source bitmap is sized per-turn to the bounding box of a [WIDTH]×[HEIGHT] destination
 * rectangle rotated by the snippet's rotation angle (0 for [MapOrientation.NORTH_UP],
 * `startBearingDeg` for [MapOrientation.TRAVEL_UP]). This makes OsmAnd's auto-fit zoom out
 * just enough that, after we rotate the source by `-rotationDeg` onto a [WIDTH]×[HEIGHT]
 * destination, the visible window contains the route rather than slicing off its ends. Sizing
 * the source to a fixed square (e.g. 736×736 for any-angle headroom) instead caused OsmAnd to
 * fit the route to the larger square and lose ~50 % of vertical content to the center crop —
 * the cause of `glass-nav-8m0` "upcoming turns cut off".
 *
 * The matching [SnippetBounds] is built with the same per-turn render dims so the meters-per-
 * pixel reflects what was actually drawn, while the destination dims = [WIDTH]/[HEIGHT]
 * govern where the live position marker lands and the in-bounds check.
 */
class OsmAndSnippetRenderer(private val app: OsmandApplication) {

    /**
     * Output of [render] for a single turn. [pngBytes] may be empty (bitmap source unavailable
     * or this turn's window was too short to render). [bounds] is null only when the polyline
     * window for this turn was empty, which means the marker pipeline cannot do anything with
     * this snippet.
     */
    data class Snippet(val pngBytes: ByteArray, val bounds: SnippetBounds?) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snippet) return false
            return pngBytes.contentEquals(other.pngBytes) && bounds == other.bounds
        }
        override fun hashCode(): Int = pngBytes.contentHashCode() * 31 + (bounds?.hashCode() ?: 0)
    }

    suspend fun render(
        turns: List<Turn>,
        track: List<LatLng>,
        orientation: MapOrientation = MapOrientation.NORTH_UP,
    ): List<Snippet> {
        if (turns.isEmpty()) return emptyList()
        val dir = File(app.cacheDir, "route-snippets").apply { mkdirs() }
        val results = ArrayList<Snippet>(turns.size)
        try {
            for ((idx, turn) in turns.withIndex()) {
                val window = sliceAroundTurn(track, turn, WINDOW_M)
                val rotationDeg = when (orientation) {
                    MapOrientation.NORTH_UP -> 0.0
                    MapOrientation.TRAVEL_UP -> entryBearingDeg(window)
                }
                val (renderW, renderH) = rotatedSourceSize(rotationDeg)
                val baseBounds = SnippetBounds.fromWindow(
                    window, WIDTH, HEIGHT, renderW, renderH,
                )
                val outBounds = baseBounds?.applyOrientation(orientation)
                val png = try {
                    if (window.size < 2) {
                        Log.w(TAG, "no usable track window for turn $idx; skipping snippet")
                        EMPTY
                    } else {
                        renderOne(dir, idx, window, rotationDeg, renderW, renderH)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "snippet render failed for turn $idx", e)
                    EMPTY
                }
                results += Snippet(png, outBounds)
            }
        } finally {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
        return results
    }

    /**
     * Compute per-turn [SnippetBounds] without rendering any bitmaps. Pure math over the polyline
     * window, so it returns in microseconds — callers can wire up the position-marker projection
     * before the (slow, sequential) bitmap renderer finishes. Index alignment matches [render].
     */
    fun computeBounds(
        turns: List<Turn>,
        track: List<LatLng>,
        orientation: MapOrientation = MapOrientation.NORTH_UP,
    ): List<SnippetBounds?> = turns.map { turn ->
        val window = sliceAroundTurn(track, turn, WINDOW_M)
        val rotationDeg = when (orientation) {
            MapOrientation.NORTH_UP -> 0.0
            MapOrientation.TRAVEL_UP -> entryBearingDeg(window)
        }
        val (renderW, renderH) = rotatedSourceSize(rotationDeg)
        SnippetBounds.fromWindow(window, WIDTH, HEIGHT, renderW, renderH)
            ?.applyOrientation(orientation)
    }

    private fun SnippetBounds.applyOrientation(orientation: MapOrientation): SnippetBounds =
        when (orientation) {
            MapOrientation.NORTH_UP -> this
            MapOrientation.TRAVEL_UP -> rotatedTravelUp()
        }

    private suspend fun renderOne(
        dir: File,
        idx: Int,
        window: List<LatLng>,
        rotationDeg: Double,
        renderW: Int,
        renderH: Int,
    ): ByteArray {
        val gpx = File(dir, "turn-$idx.gpx")
        writeTrackGpx(gpx, window)
        try {
            val srcBmp = renderBitmap(gpx, renderW, renderH) ?: return EMPTY
            val finalBmp = if (rotationDeg == 0.0 && renderW == WIDTH && renderH == HEIGHT) {
                srcBmp
            } else {
                rotateIntoDestination(srcBmp, -rotationDeg, WIDTH, HEIGHT)
            }
            return ByteArrayOutputStream().use {
                finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            runCatching { gpx.delete() }
        }
    }

    /**
     * Render the polyline GPX at [gpxFile] to a [width]×[height] bitmap via OsmAnd's in-process
     * [TrackBitmapDrawer]. Mirrors what `OsmandAidlApi.getBitmapForGpx` did over IPC for the
     * old phone-app, minus the AIDL hop. The drawer dispatches its final callback on the UI
     * thread, so we await with a generous timeout to keep the snippet pipeline from hanging if
     * map tiles haven't loaded yet.
     */
    private suspend fun renderBitmap(gpxFile: File, width: Int, height: Int, density: Float = DENSITY): Bitmap? {
        val parsed = try {
            SharedUtil.loadGpxFile(gpxFile)
        } catch (e: Exception) {
            Log.w(TAG, "loadGpxFile failed: ${e.message}")
            return null
        }
        if (parsed.error != null) {
            Log.w(TAG, "GPX parse error: ${parsed.error}")
            return null
        }
        val deferred = CompletableDeferred<Bitmap?>()
        val drawer = TrackBitmapDrawer(app, MapDrawParams(density, width, height), parsed, null)
        drawer.defaultTrackColor = Color.argb(0xff, 0xff, 0x33, 0x33)
        val listener = object : MapBitmapDrawerListener {
            override fun onBitmapDrawn(bitmap: Bitmap) {
                if (deferred.isActive) deferred.complete(bitmap)
            }
            override fun onBitmapDrawn(success: Boolean) {
                if (!success && deferred.isActive) deferred.complete(null)
            }
        }
        drawer.addListener(listener)
        try {
            app.runInUIThread { drawer.initAndDraw() }
            return try {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "renderBitmap timed out after ${RENDER_TIMEOUT_MS}ms")
                null
            }
        } finally {
            drawer.removeListener(listener)
        }
    }

    /** Rotate [src] by [degrees] around its center and draw it centered onto a fresh
     *  [outW]×[outH] bitmap. Combines the old "rotate then center-crop" steps into one — the
     *  source dims are chosen by [rotatedSourceSize] so the destination rectangle, inverse-
     *  rotated, is exactly inscribed in the source, leaving no transparent corners after the
     *  paint. Sign convention matches [android.graphics.Canvas.rotate]. */
    private fun rotateIntoDestination(src: Bitmap, degrees: Double, outW: Int, outH: Int): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(outW / 2f, outH / 2f)
        canvas.rotate(degrees.toFloat())
        canvas.translate(-src.width / 2f, -src.height / 2f)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun writeTrackGpx(file: File, points: List<LatLng>) {
        val pts = points.joinToString("\n") { "    <trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"/>" }
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<gpx version="1.1" creator="glass-nav" xmlns="http://www.topografix.com/GPX/1/1">
            |  <trk><trkseg>
            |$pts
            |  </trkseg></trk>
            |</gpx>
            |""".trimMargin()
        )
    }

    companion object {
        private const val TAG = "OsmAndSnippetRenderer"
        private val EMPTY = ByteArray(0)
        /** Glass screen dimensions — the final cropped bitmap delivered to the LiveCard. */
        const val WIDTH = 640
        const val HEIGHT = 360
        const val DENSITY = 2.5f
        const val RENDER_TIMEOUT_MS = 8_000L

        /**
         * Dimensions of the source bitmap OsmAnd renders into for a snippet that will be rotated
         * by [rotationDeg] before being shown in the [WIDTH]×[HEIGHT] destination. The bounding
         * box of a [WIDTH]×[HEIGHT] rectangle rotated by [rotationDeg] around its centre — i.e.
         * the smallest rectangle that, when populated by OsmAnd, lets the post-rotation
         * destination be filled without any transparent corners *and* without OsmAnd zooming
         * out to fill an oversized square frame (which was the [glass-nav-8m0] root cause).
         *
         * Result is ceil-rounded to whole pixels so the destination can't extend a fractional
         * pixel past the source edge.
         */
        fun rotatedSourceSize(rotationDeg: Double): Pair<Int, Int> {
            val rad = Math.toRadians(rotationDeg)
            val cosA = abs(cos(rad))
            val sinA = abs(sin(rad))
            val w = ceil(WIDTH * cosA + HEIGHT * sinA).toInt()
            val h = ceil(WIDTH * sinA + HEIGHT * cosA).toInt()
            return w to h
        }

        /** Polyline distance kept on each side of the turn point in the snippet GPX. Bigger than
         *  the displayed area at typical urban-route mppx so the route extends to the edges of
         *  the destination instead of looking like a stub at the centre; OsmAnd's auto-fit then
         *  picks a zoom that lands the whole window inside the per-turn render frame returned by
         *  [rotatedSourceSize]. */
        const val WINDOW_M = 300.0

        /**
         * Slice the real route polyline to a window centered on [turn], extending out to
         * [windowM] meters of polyline distance in each direction. Returns an empty list if
         * [track] is empty.
         */
        fun sliceAroundTurn(track: List<LatLng>, turn: Turn, windowM: Double): List<LatLng> {
            val turnLatLng = LatLng(turn.lat, turn.lon)
            val anchor = nearestTrackIndex(track, turnLatLng) ?: return emptyList()
            var start = anchor
            var back = 0.0
            while (start > 0 && back < windowM) {
                back += haversineMeters(track[start - 1], track[start])
                start--
            }
            var end = anchor
            var fwd = 0.0
            while (end < track.lastIndex && fwd < windowM) {
                fwd += haversineMeters(track[end], track[end + 1])
                end++
            }
            return track.subList(start, end + 1).toList()
        }
    }
}
