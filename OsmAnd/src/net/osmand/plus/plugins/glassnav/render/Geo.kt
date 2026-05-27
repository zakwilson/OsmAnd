package net.osmand.plus.plugins.glassnav.render

import com.goodanser.osmglass.protocol.TurnKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Step-2 scaffold: minimal geometry primitives + map-orientation enum needed by
 * [SnippetBounds] and [OsmAndSnippetRenderer], copied (minus the unused helpers) from
 * `phone-app`'s `routing/Geo.kt`, `routing/Turn.kt`, and `ui/DisplayPrefs.kt`.
 *
 * Later steps in the migration:
 *  - Step 3 (`GlassNavController`): builds [Turn] instances directly from `RoutingHelper.getRoute()
 *    .getImmutableAllDirections()`, so the data class stays here.
 *  - Step 5 (`GlassNavSettings`): [MapOrientation] folds into a `CommonPreference<MapOrientation>`
 *    on `GlassNavSettings`. The enum itself can stay in this package (referenced by the renderer)
 *    or move next to the settings class — either works as long as both sites import the same type.
 *
 * Keeping these here (rather than at the plugin root) avoids leaking helper types into the rest
 * of OsmAnd's package namespace while the migration is in flight.
 */

/** WGS84 lat/lon point. */
data class LatLng(val lat: Double, val lon: Double)

/**
 * One decision point along a route, suitable for shipping to Glass.
 *
 * Coordinates are WGS84 lat/lon. [distanceFromStartM] is cumulative track distance from the
 * route start to this turn point, in meters.
 */
data class Turn(
    val seq: Int,
    val lat: Double,
    val lon: Double,
    val kind: TurnKind,
    val distanceFromStartM: Int,
    val instruction: String,
)

/**
 * Rotation applied to the Glass map snippets before they're sent. NORTH_UP leaves OsmAnd's
 * raw output untouched; TRAVEL_UP rotates each per-turn snippet so the route's entry
 * direction at the turn points straight up.
 */
enum class MapOrientation { NORTH_UP, TRAVEL_UP }

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle distance between two points in meters (haversine). */
fun haversineMeters(a: LatLng, b: LatLng): Double {
    val phi1 = Math.toRadians(a.lat)
    val phi2 = Math.toRadians(b.lat)
    val dPhi = Math.toRadians(b.lat - a.lat)
    val dLam = Math.toRadians(b.lon - a.lon)
    val s = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLam / 2) * sin(dLam / 2)
    val c = 2 * atan2(sqrt(s), sqrt(1 - s))
    return EARTH_RADIUS_M * c
}

/** Initial bearing from a to b in degrees clockwise from north. */
fun bearingDeg(a: LatLng, b: LatLng): Double {
    val phi1 = Math.toRadians(a.lat)
    val phi2 = Math.toRadians(b.lat)
    val dLam = Math.toRadians(b.lon - a.lon)
    val y = sin(dLam) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam)
    var theta = Math.toDegrees(atan2(y, x))
    if (theta < 0) theta += 360.0
    return theta
}

/** Index of the track point closest to [at], or null if the track is empty. */
fun nearestTrackIndex(track: List<LatLng>, at: LatLng): Int? {
    if (track.isEmpty()) return null
    var bestIdx = 0
    var bestDist = haversineMeters(track[0], at)
    for (i in 1 until track.size) {
        val d = haversineMeters(track[i], at)
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }
    return bestIdx
}
