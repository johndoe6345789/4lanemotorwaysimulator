package com.johndoe6345789.motorwaysim.sim

/**
 * Geometry and rules of a UK-style four-lane motorway carriageway.
 *
 * Lanes are indexed 0..3 from left to right: lane index 0 is "lane 1" (the
 * nearside lane next to the hard shoulder) and index 3 is "lane 4" (the
 * offside/overtaking lane next to the central reservation). Traffic drives on
 * the left, so overtaking happens to the right (higher lane index).
 */
object Road {
    const val LANES = 4
    const val LANE_WIDTH = 3.65
    const val HARD_SHOULDER = 3.3
    const val OFFSIDE_STRIP = 1.0

    /** Total paved width from the outer edge of the hard shoulder to the central reservation. */
    const val WIDTH = HARD_SHOULDER + LANES * LANE_WIDTH + OFFSIDE_STRIP

    /** Distance between overhead smart-motorway gantries. */
    const val GANTRY_SPACING = 1000.0

    /** The national speed limit for cars on a motorway. */
    const val NATIONAL_LIMIT_MPH = 70

    /** Lorries over 7.5 t may not use the outside lane of a motorway with three or more lanes. */
    const val LORRY_BANNED_LANE = LANES - 1

    /** Lateral centre of a (possibly fractional) lane, measured from the left edge of the hard shoulder. */
    fun laneCenter(lane: Double): Double = HARD_SHOULDER + LANE_WIDTH * (lane + 0.5)

    fun gantryIndexAt(s: Double): Long = Math.floorDiv(s.toLong(), GANTRY_SPACING.toLong())
}

object Units {
    const val MPH = 0.44704
    fun mphToMs(mph: Double) = mph * MPH
    fun msToMph(ms: Double) = ms / MPH
    const val METRES_PER_MILE = 1609.344
}
