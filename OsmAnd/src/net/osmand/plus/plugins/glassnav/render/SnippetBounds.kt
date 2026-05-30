package net.osmand.plus.plugins.glassnav.render

import net.osmand.data.RotatedTileBox
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geographic-to-pixel projection for a snippet bitmap rendered by OsmAnd's [net.osmand.plus
 * .myplaces.tracks.TrackBitmapDrawer].
 *
 * The position marker must land exactly on the route line that OsmAnd drew. The only way to
 * guarantee that is to project the live position with the *same* [RotatedTileBox] the drawer used
 * (it draws the track via `tileBox.getPixXFromLatLon`, see `TrackBitmapDrawer.drawPoints` /
 * `drawSelectedPoint`). So [fromWindow] reconstructs that tile box bit-for-bit from
 * `TrackBitmapDrawer.createTileBox`: centre on the GPX bounding-box centre, then pick the largest
 * *integer* zoom (7..17) whose box still contains the whole bbox.
 *
 * An earlier version approximated the box with a continuous meters-per-pixel fit times a fixed
 * `PADDING_FACTOR`. That can't match OsmAnd's discrete zoom ladder — the true scale differs by up
 * to a full zoom level (≈2×), which placed the marker well off the route (glass-nav-9zn). Using
 * the real tile box removes that error entirely (and the Web-Mercator vs. equirectangular gap too).
 *
 * [project] returns coordinates in the *destination* ([widthPx]×[heightPx]) frame: it first
 * projects into the [renderWidthPx]×[renderHeightPx] source frame via [tileBox], then applies the
 * same centre-rotation (by [rotationDeg]) + centre-crop that
 * [OsmAndSnippetRenderer.rotateIntoDestination] applies to the bitmap (a no-op for NORTH_UP, where
 * source dims == dest dims and [rotationDeg] is 0).
 *
 * @property tileBox the reconstructed OsmAnd tile box; projects geographic coords into the source
 *           ([renderWidthPx]×[renderHeightPx]) bitmap frame
 * @property renderWidthPx source bitmap width OsmAnd rendered into (the rotation pivot frame)
 * @property renderHeightPx source bitmap height
 * @property widthPx destination bitmap width (= [OsmAndSnippetRenderer.WIDTH]) — governs where the
 *           projected point lands and the in-bounds check
 * @property heightPx destination bitmap height
 * @property startLat lat of the first point of the snippet's polyline window — used as the
 *           fallback marker position when current GPS lies outside the bitmap
 * @property startLon lon of the first point of the snippet's polyline window
 * @property startBearingDeg direction (deg, clockwise from north) of the route at [startLat]/
 *           [startLon] — used as the marker heading when falling back to the start position
 * @property rotationDeg canvas rotation (postRotate degrees, same sign convention as
 *           [android.graphics.Canvas.rotate]) applied to the source bitmap around its center to
 *           produce the destination snippet. 0 means north-up; travel-up passes `-startBearingDeg`
 *           (the route's entry bearing, negated) so the entry direction points up in the destination.
 */
data class SnippetBounds(
    val tileBox: RotatedTileBox,
    val renderWidthPx: Int,
    val renderHeightPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val startLat: Double,
    val startLon: Double,
    val startBearingDeg: Double,
    val rotationDeg: Double = 0.0,
) {
    /** Projection result: pixel coordinates and an in-bounds flag (using [widthPx]/[heightPx]). */
    data class Pixel(val x: Float, val y: Float, val inBounds: Boolean)

    /** Project a geographic point to pixel coordinates in the destination snippet bitmap, with any
     *  configured [rotationDeg] applied around the bitmap center. */
    fun project(lat: Double, lon: Double): Pixel {
        // Stage 1: geographic -> source-bitmap pixel, exactly as OsmAnd drew the route.
        val sx = tileBox.getPixXFromLatLon(lat, lon).toDouble()
        val sy = tileBox.getPixYFromLatLon(lat, lon).toDouble()
        // Stage 2: source -> destination, mirroring OsmAndSnippetRenderer.rotateIntoDestination,
        // which rotates the source about its centre and centres it on the widthPx×heightPx
        // destination. [rotationDeg] is already the canvas rotation angle the renderer applied
        // (travel-up uses -startBearingDeg, the same value passed to canvas.rotate), so we rotate
        // by +rotationDeg here. For NORTH_UP (rotationDeg=0, source dims == dest dims)
        // this collapses to the identity.
        val dx = sx - renderWidthPx / 2.0
        val dy = sy - renderHeightPx / 2.0
        val rad = Math.toRadians(rotationDeg)
        val cos = cos(rad)
        val sin = sin(rad)
        val px = widthPx / 2.0 + (cos * dx - sin * dy)
        val py = heightPx / 2.0 + (sin * dx + cos * dy)
        val inBounds = px >= 0 && px < widthPx && py >= 0 && py < heightPx
        return Pixel(px.toFloat(), py.toFloat(), inBounds)
    }

    /** Convert a north-up heading (deg clockwise from north) into the bearing the marker arrow
     *  must be drawn at in this (possibly rotated) snippet, so the arrow still points in the
     *  rider's actual heading. */
    fun transformBearing(headingDeg: Double): Double {
        var d = (headingDeg + rotationDeg) % 360.0
        if (d < 0) d += 360.0
        return d
    }

    companion object {
        /**
         * Compute snippet bounds from the polyline window that was handed to OsmAnd.
         *
         * [widthPx]/[heightPx] are the dimensions of the final (post-crop) bitmap delivered to
         * Glass — they govern where the projected point lands and the in-bounds check.
         * [renderWidthPx]/[renderHeightPx] are the dimensions OsmAnd actually rendered at, which
         * govern the tile box (OsmAnd fits the GPX bbox to the rendered bitmap). When the renderer
         * produces an oversized frame and crops the centre rect for rotation headroom, these
         * differ — pass the source dims for the tile box, the cropped dest dims for the projection
         * frame. [density] must match the render density (so the reconstructed tile box matches the
         * one the drawer built).
         *
         * [rotationDeg] is the canvas rotation the renderer applies (0 for north-up, `-entryBearing`
         * for travel-up); it is stored on the result *and* drives the zoom choice so the visible
         * (rotated, centre-cropped) window contains the whole route — see [buildTileBox].
         *
         * Returns null if [window] is empty.
         */
        fun fromWindow(
            window: List<LatLng>,
            widthPx: Int,
            heightPx: Int,
            renderWidthPx: Int = widthPx,
            renderHeightPx: Int = heightPx,
            rotationDeg: Double = 0.0,
            density: Float = OsmAndSnippetRenderer.DENSITY,
        ): SnippetBounds? {
            if (window.isEmpty()) return null
            var minLat = window[0].lat
            var maxLat = window[0].lat
            var minLon = window[0].lon
            var maxLon = window[0].lon
            for (p in window) {
                minLat = min(minLat, p.lat)
                maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon)
                maxLon = max(maxLon, p.lon)
            }
            val midLat = (minLat + maxLat) / 2.0
            val midLon = (minLon + maxLon) / 2.0
            val tileBox = buildTileBox(
                midLat, midLon, window,
                renderWidthPx, renderHeightPx, widthPx, heightPx, rotationDeg, density,
            )
            val start = window.first()
            val startBearing = entryBearingDeg(window)
            return SnippetBounds(
                tileBox = tileBox,
                renderWidthPx = renderWidthPx,
                renderHeightPx = renderHeightPx,
                widthPx = widthPx,
                heightPx = heightPx,
                startLat = start.lat,
                startLon = start.lon,
                startBearingDeg = startBearing,
                rotationDeg = rotationDeg,
            )
        }

        /**
         * Reconstruct the [RotatedTileBox] that the snippet bitmap is rendered with: centre on the
         * GPX bbox centre at integer zoom, then pick the largest zoom (7..17) at which the *whole*
         * [window], after the renderer's centre-rotation + centre-crop, still lands inside the
         * destination [destWidthPx]×[destHeightPx] frame.
         *
         * Marker projection must use the identical box or it drifts from the drawn route (the core
         * of the glass-nav-9zn fix), so [OsmAndSnippetRenderer] injects this same box into the
         * drawer instead of letting `TrackBitmapDrawer.createTileBox` auto-fit.
         *
         * The earlier version chose the largest zoom whose *north-up* bbox fit the render bitmap.
         * For travel-up that over-zoomed at intermediate bearings — the route's bbox was fit to the
         * oversized [rotatedSourceSize] frame, but only the inscribed rotated 640×360 rect is
         * visible, so the window's ends (and any near turn there) were cropped off (glass-nav-bp9).
         * Testing the actual rotated+cropped projection here picks a zoom that keeps the whole
         * window in frame; for north-up ([rotationDeg]==0, render dims == dest dims) it collapses
         * to the old bbox-containment test.
         */
        private fun buildTileBox(
            midLat: Double,
            midLon: Double,
            window: List<LatLng>,
            renderWidthPx: Int,
            renderHeightPx: Int,
            destWidthPx: Int,
            destHeightPx: Int,
            rotationDeg: Double,
            density: Float,
        ): RotatedTileBox {
            val tb = RotatedTileBox.RotatedTileBoxBuilder()
                .setLocation(midLat, midLon)
                .setZoom(15)
                .density(density)
                .setMapDensity(density.toDouble())
                .setPixelDimensions(renderWidthPx, renderHeightPx, 0.5f, 0.5f)
                .build()
            fun fits() = windowFitsDestination(
                tb, window, renderWidthPx, renderHeightPx, destWidthPx, destHeightPx, rotationDeg,
            )
            // Find the largest integer zoom in [7,17] at which the whole window stays in frame.
            if (fits()) {
                while (tb.zoom < 17) {
                    tb.setZoom(tb.zoom + 1)
                    if (!fits()) {
                        tb.setZoom(tb.zoom - 1)
                        break
                    }
                }
            } else {
                while (tb.zoom > 7) {
                    tb.setZoom(tb.zoom - 1)
                    if (fits()) break
                }
            }
            return tb
        }

        /**
         * True iff every point of [window], projected through [tb] into the source frame and then
         * through the same centre-rotation + centre-crop that [SnippetBounds.project] applies,
         * lands within the destination [destWidthPx]×[destHeightPx] frame. Kept in lock-step with
         * [project] so the zoom search models exactly what the rider will see.
         */
        private fun windowFitsDestination(
            tb: RotatedTileBox,
            window: List<LatLng>,
            renderWidthPx: Int,
            renderHeightPx: Int,
            destWidthPx: Int,
            destHeightPx: Int,
            rotationDeg: Double,
        ): Boolean {
            val rad = Math.toRadians(rotationDeg)
            val cos = cos(rad)
            val sin = sin(rad)
            for (p in window) {
                val sx = tb.getPixXFromLatLon(p.lat, p.lon).toDouble()
                val sy = tb.getPixYFromLatLon(p.lat, p.lon).toDouble()
                val dx = sx - renderWidthPx / 2.0
                val dy = sy - renderHeightPx / 2.0
                val px = destWidthPx / 2.0 + (cos * dx - sin * dy)
                val py = destHeightPx / 2.0 + (sin * dx + cos * dy)
                if (px < 0 || px > destWidthPx || py < 0 || py > destHeightPx) return false
            }
            return true
        }
    }
}
