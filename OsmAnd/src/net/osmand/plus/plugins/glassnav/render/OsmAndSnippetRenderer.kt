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
 * To avoid black corners after travel-up rotation, the source bitmap is square ([SRC_SIZE] ×
 * [SRC_SIZE]), sized so a 640×360 destination rectangle fits inside it at any rotation. For
 * [MapOrientation.TRAVEL_UP] the source is rotated around its center so the route entry
 * direction points up; for both orientations we then crop the central [WIDTH]×[HEIGHT]
 * rectangle and ship that to Glass. The matching [SnippetBounds] is built with render dims =
 * SRC_SIZE so the meters-per-pixel reflects what was actually drawn, while the destination
 * dims = [WIDTH]/[HEIGHT] govern where the live position marker lands and the in-bounds check.
 *
 * **Migration status (Step 2 of GlassNav plugin port):** in `phone-app` this class drove
 * OsmAnd's `getBitmapForGpx` AIDL via `OsmAndAidlClient`. Now that the code is in-process
 * inside the OsmAnd APK, the bitmap source should come from an in-process API instead of AIDL.
 * That rewire is Step 3 work (`GlassNavController`); for now [renderBitmap] returns null and
 * [render] therefore emits empty PNGs with valid [SnippetBounds]. See the TODO in
 * [renderBitmap].
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
                val baseBounds = SnippetBounds.fromWindow(
                    window, WIDTH, HEIGHT, SRC_SIZE, SRC_SIZE,
                )
                val outBounds = baseBounds?.applyOrientation(orientation)
                val png = try {
                    if (window.size < 2) {
                        Log.w(TAG, "no usable track window for turn $idx; skipping snippet")
                        EMPTY
                    } else {
                        renderOne(dir, idx, window, baseBounds, orientation)
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

    private fun SnippetBounds.applyOrientation(orientation: MapOrientation): SnippetBounds =
        when (orientation) {
            MapOrientation.NORTH_UP -> this
            MapOrientation.TRAVEL_UP -> rotatedTravelUp()
        }

    private suspend fun renderOne(
        dir: File,
        idx: Int,
        window: List<LatLng>,
        baseBounds: SnippetBounds?,
        orientation: MapOrientation,
    ): ByteArray {
        val gpx = File(dir, "turn-$idx.gpx")
        writeTrackGpx(gpx, window)
        try {
            val srcBmp = renderBitmap(gpx, SRC_SIZE, SRC_SIZE) ?: return EMPTY
            val rotated = if (orientation == MapOrientation.TRAVEL_UP && baseBounds != null) {
                rotateAroundCenter(srcBmp, -baseBounds.startBearingDeg)
            } else srcBmp
            val finalBmp = Bitmap.createBitmap(
                rotated, CROP_X, CROP_Y, WIDTH, HEIGHT,
            )
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

    /** Rotate [src] around its center by [degrees] (Canvas convention; same sign as
     *  [android.graphics.Canvas.rotate]) onto a fresh bitmap of the same dimensions. The four
     *  corners of [src] rotate off-canvas and are discarded; the caller then crops the central
     *  [WIDTH]×[HEIGHT] rectangle, which fits inside the rotated square at any angle as long as
     *  [SRC_SIZE] ≥ √([WIDTH]² + [HEIGHT]²). */
    private fun rotateAroundCenter(src: Bitmap, degrees: Double): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.rotate(degrees.toFloat(), w / 2f, h / 2f)
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

        /** Side of the square source bitmap OsmAnd renders into. Must be ≥ √(WIDTH² + HEIGHT²)
         *  (≈ 734.16) so a [WIDTH]×[HEIGHT] destination fits inside it at any rotation; 736 is
         *  the next multiple of 8 above that bound. */
        const val SRC_SIZE = 736

        /** Top-left of the centred [WIDTH]×[HEIGHT] crop inside the [SRC_SIZE]×[SRC_SIZE] source. */
        const val CROP_X = (SRC_SIZE - WIDTH) / 2
        const val CROP_Y = (SRC_SIZE - HEIGHT) / 2

        /** Polyline distance kept on each side of the turn point in the snippet GPX. Wider than
         *  the displayed area so OsmAnd zooms out enough that the rotated+cropped destination is
         *  filled with map content rather than the route running off the edges. */
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
