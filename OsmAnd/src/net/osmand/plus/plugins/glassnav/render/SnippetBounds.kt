package net.osmand.plus.plugins.glassnav.render

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geographic-to-pixel projection for a snippet bitmap rendered by OsmAnd's `getBitmapForGpx`.
 *
 * OsmAnd does not return the bounds it actually drew. We approximate them by taking the GPX
 * track's bounding box, applying a padding factor that matches OsmAnd's typical visual margin,
 * and computing the meters-per-pixel zoom that fits the padded box in the bitmap (whichever
 * axis is the tighter constraint dominates — same rule OsmAnd uses).
 *
 * The result is a north-up, equirectangular projection at the bbox latitude. Over the ~300 m
 * windows we use for per-turn snippets, the Web-Mercator vs. equirectangular discrepancy is
 * sub-pixel and is dwarfed by the padding-factor approximation, so we use the simpler form.
 *
 * @property midLat center latitude of the bounding box used to fit the snippet
 * @property midLon center longitude
 * @property metersPerPixel constant scale (north-up); same value applies to both axes
 * @property widthPx bitmap width in pixels (= [OsmAndSnippetRenderer.WIDTH])
 * @property heightPx bitmap height in pixels
 * @property startLat lat of the first point of the snippet's polyline window — used as the
 *           fallback marker position when current GPS lies outside the bitmap
 * @property startLon lon of the first point of the snippet's polyline window
 * @property startBearingDeg direction (deg, clockwise from north) of the route at [startLat]/
 *           [startLon] — used as the marker heading when falling back to the start position
 * @property rotationDeg canvas rotation (postRotate degrees, same sign convention as
 *           [android.graphics.Canvas.rotate]) applied to the bitmap around its center to produce
 *           the destination snippet. 0 means north-up (bitmap untouched). [rotatedTravelUp] sets
 *           this to `-startBearingDeg` so the route's entry direction points up in the destination.
 */
data class SnippetBounds(
    val midLat: Double,
    val midLon: Double,
    val metersPerPixel: Double,
    val widthPx: Int,
    val heightPx: Int,
    val startLat: Double,
    val startLon: Double,
    val startBearingDeg: Double,
    val rotationDeg: Double = 0.0,
) {
    /** Projection result: pixel coordinates and an in-bounds flag (using [widthPx]/[heightPx]). */
    data class Pixel(val x: Float, val y: Float, val inBounds: Boolean)

    /** Project a geographic point to pixel coordinates in the snippet bitmap, with any
     *  configured [rotationDeg] applied around the bitmap center. */
    fun project(lat: Double, lon: Double): Pixel {
        val dLatMeters = (lat - midLat) * METERS_PER_DEG_LAT
        val dLonMeters = (lon - midLon) * METERS_PER_DEG_LAT * cos(Math.toRadians(midLat))
        val xn = widthPx / 2.0 + dLonMeters / metersPerPixel
        val yn = heightPx / 2.0 - dLatMeters / metersPerPixel
        val (px, py) = if (rotationDeg == 0.0) {
            xn to yn
        } else {
            val cx = widthPx / 2.0
            val cy = heightPx / 2.0
            val dx = xn - cx
            val dy = yn - cy
            val rad = Math.toRadians(rotationDeg)
            val cos = cos(rad)
            val sin = sin(rad)
            // Same matrix postRotate(rotationDeg) applies in Canvas, so the projected point
            // tracks whatever pixel of the source bitmap lands at this destination pixel.
            (cx + cos * dx - sin * dy) to (cy + sin * dx + cos * dy)
        }
        val inBounds = px >= 0 && px < widthPx && py >= 0 && py < heightPx
        return Pixel(px.toFloat(), py.toFloat(), inBounds)
    }

    /** Bounds for a snippet whose bitmap has been rotated so the route's entry direction points
     *  up. Canvas rotation needed for that is `-startBearingDeg`. */
    fun rotatedTravelUp(): SnippetBounds = copy(rotationDeg = -startBearingDeg)

    /** Convert a north-up heading (deg clockwise from north) into the bearing the marker arrow
     *  must be drawn at in this (possibly rotated) snippet, so the arrow still points in the
     *  rider's actual heading. */
    fun transformBearing(headingDeg: Double): Double {
        var d = (headingDeg + rotationDeg) % 360.0
        if (d < 0) d += 360.0
        return d
    }

    companion object {
        /** Mean meters per degree of latitude on WGS-84. (Variation with lat is < 0.6 % and is
         *  absorbed by the padding-factor approximation.) */
        const val METERS_PER_DEG_LAT = 111_320.0

        /**
         * Estimated fraction of bitmap extent OsmAnd leaves as padding around the GPX bounding
         * box. Calibrated empirically against the renderer output at WIDTH=640 HEIGHT=360
         * DENSITY=2.5: OsmAnd usually leaves ~20 % padding per side (so the track occupies the
         * inner 60 % of the relevant axis), which corresponds to a 1.4× expansion of the bbox.
         */
        const val PADDING_FACTOR = 1.4

        /**
         * Compute snippet bounds from the polyline window that was handed to OsmAnd.
         *
         * [widthPx]/[heightPx] are the dimensions of the final (post-crop) bitmap delivered to
         * Glass — they govern where the projected point lands and the in-bounds check.
         * [renderWidthPx]/[renderHeightPx] are the dimensions OsmAnd actually rendered at, which
         * govern the meters-per-pixel scale (OsmAnd auto-fits the GPX bbox to the rendered
         * bitmap). When the renderer produces an oversized square and crops the centre rect for
         * rotation headroom, these differ — pass the source dims for the mppx calculation, and
         * the cropped dest dims for the projection frame.
         *
         * Returns null if [window] is empty.
         */
        fun fromWindow(
            window: List<LatLng>,
            widthPx: Int,
            heightPx: Int,
            renderWidthPx: Int = widthPx,
            renderHeightPx: Int = heightPx,
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
            val heightMeters = (maxLat - minLat) * METERS_PER_DEG_LAT
            val widthMeters = (maxLon - minLon) * METERS_PER_DEG_LAT * cos(Math.toRadians(midLat))
            // OsmAnd fits the (padded) bounding box inside the rendered image. The dimension with
            // the tighter ratio (more meters per available pixel) determines the zoom.
            val rawMppx = max(widthMeters / renderWidthPx, heightMeters / renderHeightPx)
            // A near-degenerate (single point or co-linear two-point) window produces zero meters
            // on one or both axes. Clamp to a sane minimum so we don't divide by zero in project().
            val mppx = max(rawMppx, 0.1) * PADDING_FACTOR
            val start = window.first()
            val startBearing = computeStartBearing(window)
            return SnippetBounds(
                midLat = midLat,
                midLon = midLon,
                metersPerPixel = mppx,
                widthPx = widthPx,
                heightPx = heightPx,
                startLat = start.lat,
                startLon = start.lon,
                startBearingDeg = startBearing,
            )
        }

        /** Bearing of the route at the snippet's entry point, averaged over the first ~30 m. */
        private fun computeStartBearing(window: List<LatLng>): Double {
            if (window.size < 2) return 0.0
            val start = window[0]
            var idx = 1
            // Use a few points to smooth out GPS jitter, but bail out fast if track is short.
            val limit = minOf(window.size - 1, 5)
            while (idx < limit) {
                val seg = haversineMeters(start, window[idx])
                if (seg >= 30.0) break
                idx++
            }
            return bearingDeg(start, window[idx])
        }
    }
}
