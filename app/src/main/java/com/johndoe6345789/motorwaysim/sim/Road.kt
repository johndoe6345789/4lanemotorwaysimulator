package com.johndoe6345789.motorwaysim.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round

/**
 * Geometry and rules of a UK-style dual four-lane motorway.
 *
 * On each carriageway lanes are indexed from the nearside: lane index 0 is
 * "lane 1" next to the hard shoulder and index 3 is "lane 4" next to the
 * central reservation. Lane index -1 is the extra lane that exists only at
 * junctions: the diverge lane leading to an exit and the acceleration lane
 * from an entry slip road. Traffic drives on the left.
 */
object Road {
    const val LANES = 4
    const val LANE_WIDTH = 3.65
    const val HARD_SHOULDER = 3.3
    const val OFFSIDE_STRIP = 1.0

    /** Paved width of one carriageway from the outer edge of the hard shoulder to the central reservation. */
    const val WIDTH = HARD_SHOULDER + LANES * LANE_WIDTH + OFFSIDE_STRIP

    /** Half the width of the central reservation. */
    const val CR_HALF = 1.5

    const val GANTRY_SPACING = 1000.0
    const val NATIONAL_LIMIT_MPH = 70

    /** The right-hand lane, barred to goods vehicles over 3.5 t and coaches (Highway Code Rule 265). */
    const val RIGHT_HAND_LANE = LANES - 1

    // Junctions: one every JUNCTION_SPACING metres. Offsets are relative to the junction
    // centre, measured along each carriageway in its direction of travel.
    const val JUNCTION_SPACING = 3000.0
    const val DIVERGE_START = -950.0
    const val DIVERGE_END = -700.0
    const val MERGE_START = 700.0
    const val MERGE_END = 920.0
    const val FIRST_JUNCTION_NUMBER = 20

    // Ground-level roundabout; the motorway passes beneath it in an underpass.
    const val RING_RADIUS = 52.0
    const val RING_WIDTH = 7.5
    const val RING_SPEED_MPH = 30
    const val SLIP_WIDTH = 4.5
    const val LOCAL_LENGTH = 650.0
    const val LOCAL_OFFSET = 2.0
    const val LOCAL_LIMIT_MPH = 40

    // Underpass: at each junction the motorway drops into a cutting between retaining walls and
    // runs through a covered section under the roundabout. The slip roads, the roundabout and
    // the local roads stay at ground level, so the diverge and merge lanes are level too.
    const val UNDERPASS_DEPTH = 7.0

    /** Within this distance of the junction centre the cutting is at full depth. */
    const val CUTTING_FLAT = 90.0

    /**
     * Beyond this distance of the junction centre the motorway is back at ground level. The
     * slip roads stay outside the cutting's walls until then (a 3.2% grade at most).
     */
    const val CUTTING_TOP = 430.0

    /** Half the length of the covered section under the roundabout. */
    const val TUNNEL_HALF = 62.0

    /** The retaining walls stand this far either side of the centre line. */
    const val CUTTING_HALF_WIDTH = CR_HALF + WIDTH + 1.0

    /** Height of the motorway at world [y]: 0 at ground level, negative in an underpass cutting. */
    fun motorwayZ(y: Double): Double {
        val d = abs(y - junctionY(junctionNearest(y)))
        if (d >= CUTTING_TOP) return 0.0
        if (d <= CUTTING_FLAT) return -UNDERPASS_DEPTH
        val t = (d - CUTTING_FLAT) / (CUTTING_TOP - CUTTING_FLAT)
        return -UNDERPASS_DEPTH * (1 + cos(PI * t)) / 2
    }

    /** Whether world [y] is under the roundabout's covered section. */
    fun inTunnel(y: Double): Boolean = abs(y - junctionY(junctionNearest(y))) < TUNNEL_HALF

    /** Lateral centre of a (possibly fractional) lane, measured from the outer edge of the hard shoulder. */
    fun laneCenter(lane: Double): Double = HARD_SHOULDER + LANE_WIDTH * (lane + 0.5)

    fun gantryIndexAt(s: Double): Long = floor(s / GANTRY_SPACING).toLong()

    /** No gantry stands under the roundabout at a junction centre. */
    fun hasGantry(index: Long): Boolean = Math.floorMod(index, (JUNCTION_SPACING / GANTRY_SPACING).toLong()) != 0L

    /** Index of the junction whose centre is nearest to world y. */
    fun junctionNearest(y: Double): Int = round(y / JUNCTION_SPACING).toInt()

    fun junctionY(k: Int): Double = k * JUNCTION_SPACING

    /** Offset of carriageway position [s] from the nearest junction centre on that carriageway. */
    fun junctionOffset(s: Double): Double = s - round(s / JUNCTION_SPACING) * JUNCTION_SPACING

    /** Carriageway position of the centre of the next junction whose exit is still ahead of [s]. */
    fun nextJunctionCentre(s: Double): Double =
        Math.ceil((s - DIVERGE_END) / JUNCTION_SPACING) * JUNCTION_SPACING

    fun isDivergeZone(s: Double) = junctionOffset(s).let { it in DIVERGE_START..DIVERGE_END }
    fun isMergeZone(s: Double) = junctionOffset(s).let { it in MERGE_START..MERGE_END }

    /** Whether the extra lane (index -1) exists at carriageway position [s]. */
    fun hasExtraLane(s: Double) = isDivergeZone(s) || isMergeZone(s)

    fun deg(d: Double) = d * PI / 180
}

/** The two carriageways. Northbound is on the west side, as traffic keeps left. */
enum class Carriageway(val dir: Int, val label: String) {
    NORTH(1, "M17 North"),
    SOUTH(-1, "M17 South");

    /** World x of lateral position [m] (metres from the outer edge of this carriageway's hard shoulder). */
    fun worldX(m: Double): Double =
        if (this == NORTH) -(Road.CR_HALF + Road.WIDTH) + m else (Road.CR_HALF + Road.WIDTH) - m

    fun worldY(s: Double): Double = dir * s
    fun sOf(worldY: Double): Double = dir * worldY

    /** Direction of travel (radians from east). */
    val heading: Double get() = if (this == NORTH) PI / 2 else -PI / 2

    val opposite: Carriageway get() = if (this == NORTH) SOUTH else NORTH

    /** Carriageway position of junction [k]'s centre. */
    fun junctionS(k: Int): Double = sOf(Road.junctionY(k))

    /** Junction index for a junction centred at carriageway position [s]. */
    fun junctionAt(s: Double): Int = Road.junctionNearest(worldY(s))
}

object Units {
    const val MPH = 0.44704
    fun mphToMs(mph: Double) = mph * MPH
    fun msToMph(ms: Double) = ms / MPH
    const val METRES_PER_MILE = 1609.344
}
