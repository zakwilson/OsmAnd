package net.osmand.plus.plugins.glassnav.render

import com.goodanser.osmglass.protocol.TurnKind
import net.osmand.router.TurnType

/**
 * Maps OsmAnd's in-process [TurnType] codes to our wire-protocol [TurnKind].
 *
 * Now that the GlassNav code lives in-process inside the OsmAnd APK, we have the full
 * [TurnType] object available (lanes, exit number, isLeftSide) — not just the int `getValue()`
 * that the AIDL surface used to expose. The mapping below still operates on the int code for
 * API parity with the previous AIDL-based mapper; callers that want the richer fields can read
 * them off the [TurnType] directly and pass alongside the [TurnKind].
 *
 * Fidelity gaps to be aware of when relying on this mapping:
 *
 *  - Our [TurnKind] enum has no STRAIGHT or ROUNDABOUT ordinals. OsmAnd's `C` (continue) gets
 *    mapped to `null` so callers can skip the bundle; `RNDB`/`RNLB` degrade to the closest
 *    directional turn (KL/KR) as a least-bad approximation. Adding STRAIGHT + ROUNDABOUT to
 *    [TurnKind] would require a proto version bump but would restore fidelity. With the
 *    in-process API we now have access to `TurnType.getExitOut()` for roundabouts, which the
 *    AIDL path dropped — once the proto is extended we can carry exit number too.
 *
 *  - `OFFR` (off-route) is not a turn — caller should treat it as a deviation signal and
 *    re-route, not surface a bundle. Mapped to `null`.
 *
 *  - `TRU` (right U-turn) — our wire protocol only has a single [TurnKind.TU], so left/right
 *    distinction is lost.
 */
object TurnTypeMapping {

    /**
     * Translate an OsmAnd turn-type int (from [TurnType.getValue]) to a [TurnKind]. Returns
     * null for codes that should not be surfaced as a turn bundle (continue, off-route,
     * unknown).
     */
    fun fromOsmAndTurnType(value: Int): TurnKind? = when (value) {
        TurnType.TL   -> TurnKind.TL
        TurnType.TR   -> TurnKind.TR
        TurnType.TSLL -> TurnKind.TSLL
        TurnType.TSLR -> TurnKind.TSLR
        TurnType.TSHL -> TurnKind.TSHL
        TurnType.TSHR -> TurnKind.TSHR
        TurnType.KL   -> TurnKind.KL
        TurnType.KR   -> TurnKind.KR
        TurnType.TU, TurnType.TRU -> TurnKind.TU
        // Roundabouts: degrade to KL/KR — see fidelity-gap note in the class doc.
        // TODO(proto): once TurnKind gains a ROUNDABOUT ordinal, pass exit number from
        //   TurnType.getExitOut() through to callers instead of collapsing to KL/KR.
        TurnType.RNDB -> TurnKind.KR
        TurnType.RNLB -> TurnKind.KL
        // C (continue), OFFR (off-route), and unknown codes: caller decides.
        TurnType.C, TurnType.OFFR -> null
        else -> null
    }

    /** Convenience overload — accepts a [TurnType] object and delegates on the int code. */
    fun fromOsmAndTurnType(turnType: TurnType?): TurnKind? =
        turnType?.let { fromOsmAndTurnType(it.value) }

    /** True iff the turn-type code represents a route deviation rather than a turn. */
    fun isOffRoute(value: Int): Boolean = value == TurnType.OFFR
}
