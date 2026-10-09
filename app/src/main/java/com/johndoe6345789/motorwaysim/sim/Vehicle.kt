package com.johndoe6345789.motorwaysim.sim

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class VehicleType(
    val length: Double,
    val width: Double,
    val height: Double,
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
    CAR(4.5, 1.8, 1.45, 60.0, 1.6, 2.0, 1.3, 2.0),
    VAN(5.6, 2.0, 2.4, 40.0, 1.3, 2.0, 1.4, 2.0),
    COACH(12.5, 2.55, 3.5, Units.mphToMs(62.0), 1.0, 1.8, 1.6, 2.5),
    LORRY(16.5, 2.55, 3.9, Units.mphToMs(56.0), 0.7, 1.8, 1.8, 3.0),
    POLICE(4.9, 1.85, 1.6, 62.0, 2.6, 2.6, 0.9, 2.0),
    RECOVERY(8.6, 2.5, 3.1, Units.mphToMs(65.0), 1.1, 2.0, 1.5, 2.5);

    val isHeavy get() = this == LORRY || this == COACH || this == RECOVERY
}

enum class Role {
    TRAFFIC,

    /** Broken down or crashed: stationary with hazard lights, waiting for recovery. */
    WRECK,
    POLICE,
    RECOVERY,
}

enum class Beacon { NONE, BLUE, AMBER }

class Vehicle(
    val id: Int,
    val type: VehicleType,
    link: Link,
    /** Position of the front bumper along [link] (m). */
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
    var isPlayer: Boolean = false,
) {
    val length get() = type.length
    val width get() = type.width
    val rear get() = s - length

    var link: Link = link
        private set

    /** Target lane. During a lane change the vehicle also still occupies [fromLane]. */
    var lane: Int = lane
        private set
    var fromLane: Int = lane
        private set

    /** Lane-change progress from 0 (just started) to 1 (finished). */
    var laneProgress = 1.0
        private set
    private var laneChangeDuration = 3.0

    var acc = 0.0
    var role = Role.TRAFFIC
    var beacon = Beacon.NONE
    var hazards = false

    /** -1 indicating left, +1 indicating right, 0 off. */
    var indicator = 0

    /** Lane this driver would like to move into but can't yet (for zip merging). */
    var wantsLane = NO_LANE

    var lastLaneChangeTime = -100.0
    var nextDecisionTime = 0.0

    // Routing.
    /** Junction (index) at which this vehicle will leave the motorway, if any. */
    var exitJunction: Int? = null
    var decidedJunction = Int.MIN_VALUE

    /** The roundabout exit this vehicle will take. */
    var ringExit: RingPort? = null

    /** The road this vehicle used to reach the junction (to choose where to go next). */
    var enteredFrom: PathLink? = null

    /** Distance driven since joining the roundabout (the rest of the body is still on the slip road). */
    var ringTravelled = 0.0

    /** Committed to entering the roundabout from the give-way line. */
    var committed = false

    /** Job for police and recovery vehicles. */
    var task: Task? = null

    // Wrecks and recovery.
    var carrying: Vehicle? = null

    /** The recovery truck winching this wreck aboard, and how far it has got (0–1). */
    var loader: Vehicle? = null
    var loadProgress = 0.0

    /** Extra yaw of a crashed vehicle, for looks. */
    var wreckYaw = 0.0

    /** World pose of the centre of the vehicle's footprint (updated every step). */
    val pose = Pose()
    var pitch = 0.0
    private val front = Pose()
    private val back = Pose()

    val isChangingLane get() = laneProgress < 1.0

    /** Continuous lateral lane coordinate, e.g. 1.5 when halfway between lane indices 1 and 2. */
    val lateralLane: Double
        get() {
            if (!isChangingLane) return lane.toDouble()
            val p = laneProgress
            val eased = p * p * (3 - 2 * p)
            return fromLane + (lane - fromLane) * eased
        }

    /** A vehicle can only move sideways while moving forwards, so slow lane changes take longer. */
    private val laneChangeRate get() = (v / 12.0).coerceIn(0.2, 1.0)

    /** Sideways speed during a lane change (m/s, positive = towards higher lane indices). */
    val lateralVelocity: Double
        get() {
            if (!isChangingLane) return 0.0
            val p = laneProgress
            return (lane - fromLane) * Road.LANE_WIDTH * 6 * p * (1 - p) * laneChangeRate / laneChangeDuration
        }

    val isResponder get() = role == Role.POLICE || role == Role.RECOVERY

    fun occupies(l: Int) = lane == l || (isChangingLane && fromLane == l)

    fun startLaneChange(target: Int, time: Double, duration: Double) {
        if (target == lane) return
        fromLane = lane
        lane = target
        laneProgress = 0.0
        laneChangeDuration = duration
        indicator = if (target > fromLane) 1 else -1
        lastLaneChangeTime = time
        wantsLane = NO_LANE
    }

    fun advanceLaneChange(dt: Double) {
        if (!isChangingLane) return
        laneProgress = min(1.0, laneProgress + dt * laneChangeRate / laneChangeDuration)
        if (laneProgress >= 1.0) {
            fromLane = lane
            indicator = 0
        }
    }

    /** Moves the vehicle onto another link, e.g. from a slip road onto the roundabout. */
    fun moveTo(newLink: Link, newS: Double, newLane: Int) {
        link = newLink
        s = newS
        lane = newLane
        fromLane = newLane
        laneProgress = 1.0
        if (indicator != 0 && link.kind != LinkKind.RING) indicator = 0
        committed = false
        wantsLane = NO_LANE
    }

    fun desiredSpeed(limit: Double): Double = min(type.maxSpeed, limit * desiredFactor)

    fun canUseLane(l: Int): Boolean =
        l in 0 until Road.LANES && !(type == VehicleType.LORRY && l == Road.LORRY_BANNED_LANE)

    /** Recomputes [pose] and [pitch] from the vehicle's position on its link. */
    fun updatePose() {
        val lat = lateralLane
        link.pose(s, lat, front)
        link.pose(s - length, lat, back)
        val onMotorway = link is MotorwayLink
        val heading = if (onMotorway) front.heading else atan2(front.y - back.y, front.x - back.x)
        // Lean into lane changes.
        val steer = if (onMotorway) atan2(lateralVelocity, maxOf(v, 2.0)) else 0.0
        pose.heading = heading - steer + wreckYaw
        pose.x = (front.x + back.x) / 2
        pose.y = (front.y + back.y) / 2
        pose.z = (front.z + back.z) / 2
        pitch = atan2(front.z - back.z, length)
    }

    /** World x of a point [forward] metres ahead of the centre and [right] metres to the right. */
    fun worldX(forward: Double, right: Double) = pose.x + cos(pose.heading) * forward + sin(pose.heading) * right
    fun worldY(forward: Double, right: Double) = pose.y + sin(pose.heading) * forward - cos(pose.heading) * right

    companion object {
        const val NO_LANE = -99
    }
}

/** A police or recovery job at an incident. */
class Task(val incident: Incident) {
    /** Lane the responder should be in when it stops. */
    var lane = 0

    /** Carriageway position at which the responder's front should stop. */
    var stopS = 0.0

    /** Recovery trucks pass the scene in this lane, then pull in ahead of the wreck. */
    var approachLane = Vehicle.NO_LANE

    /** The vehicle to be recovered. */
    var target: Vehicle? = null
    var arrived = false
}
