package com.johndoe6345789.motorwaysim.sim

import kotlin.math.min

enum class VehicleType(
    val length: Double,
    val width: Double,
    /** Hard top speed (e.g. speed limiter) in m/s. */
    val maxSpeed: Double,
    /** IDM maximum acceleration (m/s²). */
    val maxAccel: Double,
    /** IDM comfortable deceleration (m/s²). */
    val comfortDecel: Double,
    /** IDM desired time headway (s). */
    val timeHeadway: Double,
    /** IDM minimum standstill gap (m). */
    val minGap: Double,
) {
    CAR(4.5, 1.8, 60.0, 1.6, 2.0, 1.3, 2.0),
    VAN(5.6, 2.0, 40.0, 1.3, 2.0, 1.4, 2.0),
    COACH(12.5, 2.55, Units.mphToMs(62.0), 1.0, 1.8, 1.6, 2.5),
    LORRY(16.5, 2.55, Units.mphToMs(56.0), 0.7, 1.8, 1.8, 3.0),

    /** A stationary broken-down vehicle that closes a lane. */
    BREAKDOWN(4.8, 1.9, 0.0, 0.0, 0.0, 0.0, 0.0);

    val isHeavy get() = this == LORRY || this == COACH
}

class Vehicle(
    val id: Int,
    val type: VehicleType,
    /** Longitudinal position of the front bumper along the road (m). */
    var s: Double,
    lane: Int,
    /** Speed (m/s). */
    var v: Double,
    /** Personal attitude to the speed limit: desired speed = limit × factor. */
    val desiredFactor: Double,
    /** Willingness to give up own advantage for others (MOBIL politeness). */
    val politeness: Double,
    /** Whether this driver opens a gap for someone waiting to merge in. */
    val yieldsToMergers: Boolean,
    val color: Int,
    val isPlayer: Boolean = false,
) {
    val length get() = type.length
    val width get() = type.width
    val rear get() = s - length

    /** Target lane. During a lane change the vehicle also still occupies [fromLane]. */
    var lane: Int = lane
        private set
    var fromLane: Int = lane
        private set

    /** Lane-change progress from 0 (just started) to 1 (finished). */
    var laneProgress = 1.0
        private set
    private var laneChangeDuration = 3.0

    /** Acceleration applied during the last step (m/s²). */
    var acc = 0.0

    /** -1 indicating left, +1 indicating right, 0 off. */
    var indicator = 0

    /** Lane this driver would like to move into but can't yet (for zip merging), or -1. */
    var wantsLane = -1

    var lastLaneChangeTime = -100.0
    var nextDecisionTime = 0.0

    val isChangingLane get() = laneProgress < 1.0

    /** Continuous lateral lane coordinate, e.g. 1.5 when halfway between lane 1 and lane 2 (indices). */
    val lateralLane: Double
        get() {
            if (!isChangingLane) return lane.toDouble()
            val p = laneProgress
            val eased = p * p * (3 - 2 * p)
            return fromLane + (lane - fromLane) * eased
        }

    /** Sideways speed during a lane change (m/s, positive = moving right). */
    val lateralVelocity: Double
        get() {
            if (!isChangingLane) return 0.0
            val p = laneProgress
            return (lane - fromLane) * Road.LANE_WIDTH * 6 * p * (1 - p) * laneChangeRate / laneChangeDuration
        }

    /**
     * A vehicle can only move sideways while it moves forwards: in slow traffic a lane
     * change takes proportionally longer (but never stalls completely).
     */
    private val laneChangeRate get() = (v / 12.0).coerceIn(0.2, 1.0)

    /** Lateral centre position in metres from the hard-shoulder edge. */
    val x get() = Road.laneCenter(lateralLane)

    fun occupies(l: Int) = lane == l || (isChangingLane && fromLane == l)

    fun startLaneChange(target: Int, time: Double, duration: Double) {
        if (target == lane || target !in 0 until Road.LANES) return
        fromLane = lane
        lane = target
        laneProgress = 0.0
        laneChangeDuration = duration
        indicator = if (target > fromLane) 1 else -1
        lastLaneChangeTime = time
        wantsLane = -1
    }

    fun advanceLaneChange(dt: Double) {
        if (!isChangingLane) return
        laneProgress = min(1.0, laneProgress + dt * laneChangeRate / laneChangeDuration)
        if (laneProgress >= 1.0) {
            fromLane = lane
            indicator = 0
        }
    }

    fun desiredSpeed(limit: Double): Double = min(type.maxSpeed, limit * desiredFactor)

    fun canUseLane(l: Int): Boolean =
        l in 0 until Road.LANES && !(type == VehicleType.LORRY && l == Road.LORRY_BANNED_LANE)
}
