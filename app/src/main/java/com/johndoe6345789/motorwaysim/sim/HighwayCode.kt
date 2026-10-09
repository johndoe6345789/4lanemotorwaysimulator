package com.johndoe6345789.motorwaysim.sim

import kotlin.math.max

/**
 * Things the player can get wrong, with the Highway Code rules they break (rule numbers as in
 * the current edition published on GOV.UK) and the penalty points they carry here. Offences with
 * no points are advice: a warning on screen.
 */
enum class Offence(val rules: String, val title: String, val points: Int, val advice: String) {
    SPEEDING("124, 261", "Speeding", 3, "You must not exceed the limit for the road or for your vehicle."),
    SPEED_CAMERA("124, 261", "Caught by a speed camera", 3, "You must not exceed the limit for the road or for your vehicle."),
    RED_X("258", "Driving in a closed lane", 3, "A red X closes the lane: move to an open lane."),
    TAILGATING("126", "Tailgating", 3, "Leave at least a two-second gap on high-speed roads."),
    UNDERTAKING("268", "Overtaking on the left", 3, "Do not overtake on the left or move to a lane on your left to overtake."),
    LANE_HOGGING("264", "Hogging the middle lane", 3, "Keep in the left lane unless overtaking."),
    RIGHT_LANE("265", "Heavy vehicle in the right-hand lane", 3, "Goods vehicles over 3.5 t and coaches must not use the right-hand lane."),
    HARD_SHOULDER("269", "Driving on the hard shoulder", 3, "Only use the hard shoulder in an emergency."),
    CUT_IN("267", "Cutting in", 3, "Do not cut in on the vehicle you have overtaken."),
    JOINING("259", "Forcing your way on", 3, "Give priority to traffic already on the motorway."),
    GIVE_WAY("185", "Failing to give way", 3, "Give priority to traffic approaching from your right."),
    COLLISION("126, 260", "Collision", 3, "Drive at a speed and distance that let you stop safely."),
    NO_SIGNAL("133, 161", "Changing lanes without signalling", 0, "Mirrors – Signal – Manoeuvre."),
    NO_EXIT_SIGNAL("273", "Leaving without signalling", 0, "Signal left in good time before your exit."),
    STOPPING("271", "Stopping on the motorway", 0, "Don't stop on the carriageway except in an emergency."),
    BLUE_LIGHTS("219", "Not letting an emergency vehicle past", 0, "Take appropriate action to let it pass."),
}

class Breach(val time: Double, val offence: Offence)

/**
 * Watches the player's driving against the Highway Code. Points add up on the licence; at
 * [BAN_POINTS] the player is disqualified.
 */
class HighwayCode(private val sim: Simulation) {
    var points = 0
        private set
    val breaches = ArrayList<Breach>()
    val banned get() = points >= BAN_POINTS

    private val lastBooked = HashMap<Offence, Double>()
    private val lastSignal = doubleArrayOf(-100.0, -100.0)
    private var speedingTime = 0.0
    private var underLimitTime = 0.0
    private var speedingBooked = false
    private var tailgateTime = 0.0
    private var hogTime = 0.0
    private var hogWarned = false
    private var rightLaneTime = 0.0
    private var shoulderTime = 0.0
    private var stoppedTime = 0.0
    private var blockedTime = 0.0
    private var lastLane = Int.MIN_VALUE
    private var lastLink: Link? = null
    private var laneChangeAt = -100.0
    private var laneChangeFollower: Vehicle? = null
    private var laneChangeWasMerge = false
    private var giveWayUntil = -1.0
    private val wasAhead = HashMap<Int, Boolean>()

    fun reset() {
        points = 0
        breaches.clear()
        lastBooked.clear()
        lastSignal.fill(-100.0)
        speedingTime = 0.0; underLimitTime = 0.0; speedingBooked = false
        tailgateTime = 0.0; hogTime = 0.0; hogWarned = false
        rightLaneTime = 0.0; shoulderTime = 0.0; stoppedTime = 0.0; blockedTime = 0.0
        lastLane = Int.MIN_VALUE
        lastLink = null
        laneChangeAt = -100.0
        laneChangeFollower = null
        giveWayUntil = -1.0
        wasAhead.clear()
    }

    /** Records an offence (at most once every [cooldown] seconds for the same offence). */
    fun book(o: Offence, cooldown: Double = 15.0) {
        val t = sim.time
        val last = lastBooked[o]
        if (last != null && t - last < cooldown) return
        lastBooked[o] = t
        breaches += Breach(t, o)
        if (breaches.size > 30) breaches.removeAt(0)
        points += o.points
        val penalty = if (o.points > 0) " (+${o.points} points)" else ""
        sim.post("Rule ${o.rules}: ${o.title}$penalty. ${o.advice}", warning = true)
        if (banned) sim.post("Disqualified: $points penalty points on your licence", warning = true)
    }

    internal fun signalled(dir: Int) {
        lastSignal[if (dir < 0) 0 else 1] = sim.time
    }

    /** Rule 273: signal left in good time when leaving the motorway. */
    internal fun leftMotorway() {
        if (exempt()) return
        if (sim.time - lastSignal[0] > 8 && sim.player.indicator >= 0) book(Offence.NO_EXIT_SIGNAL)
    }

    /** Rule 185: start watching whether joining the roundabout made circulating traffic brake hard. */
    internal fun joinedRoundabout() {
        giveWayUntil = sim.time + 3.0
    }

    private fun exempt(): Boolean = sim.autopilot || sim.player.beacon == Beacon.BLUE

    /** Recovery trucks at work may use closed lanes and the hard shoulder. */
    private fun atWork(): Boolean = sim.player.beacon != Beacon.NONE

    fun update(dt: Double, beforeLink: Link, beforeS: Double) {
        val p = sim.player
        if (p.indicator != 0) signalled(p.indicator)
        if (sim.autopilot) {
            lastLane = Int.MIN_VALUE
            return
        }
        val link = p.link
        val t = sim.time

        // Speed (Rules 124 and 261).
        val limit = sim.playerLimitMph()
        val mph = Units.msToMph(p.v)
        if (!exempt() && mph > limit * 1.1 + 2) {
            speedingTime += dt
            underLimitTime = 0.0
            if (speedingTime > 6 && !speedingBooked) {
                speedingBooked = true
                book(Offence.SPEEDING, 0.0)
            } else if (speedingTime in 2.0..(2.0 + dt)) {
                sim.post("Slow down: the limit for a ${p.type.label.lowercase()} here is $limit mph (Rule 124)", warning = true)
            }
        } else {
            underLimitTime += dt
            if (underLimitTime > 8) { speedingTime = 0.0; speedingBooked = false }
        }

        // Giving way at roundabouts (Rule 185).
        if (link is RingLink && t < giveWayUntil) {
            val behind = sim.followerIn(link, 0, p)
            if (behind != null && behind.acc < -4.0 && link.forward(behind.s, p.s) < 40) {
                book(Offence.GIVE_WAY)
                giveWayUntil = -1.0
            }
        }

        if (link !is MotorwayLink) {
            lastLane = Int.MIN_VALUE
            lastLink = link
            resetMotorwayTimers()
            return
        }
        val lane = p.lane

        // Lane changes: signals (Rules 133, 161), cutting in (267) and joining (259).
        if (lastLink === link && lastLane != Int.MIN_VALUE && lane != lastLane) {
            val dir = if (lane > lastLane) 1 else -1
            if (!exempt() && t - lastSignal[if (dir < 0) 0 else 1] > 5) book(Offence.NO_SIGNAL, 10.0)
            laneChangeAt = t
            laneChangeFollower = sim.followerIn(link, lane, p)
            laneChangeWasMerge = lastLane == -1 && Road.isMergeZone(p.s)
        }
        lastLane = lane
        lastLink = link
        val follower = laneChangeFollower
        if (follower != null && t - laneChangeAt < 2.5) {
            if (follower.acc < -3.5 && follower.role == Role.TRAFFIC) {
                book(if (laneChangeWasMerge) Offence.JOINING else Offence.CUT_IN)
                laneChangeFollower = null
            }
        } else laneChangeFollower = null

        // Red X (Rule 258): passing a gantry that closes your lane.
        val g = sim.gantryFor(p.s)
        if (beforeLink === link && sim.gantryFor(beforeS) != g && !atWork() && lane >= 0 &&
            sim.signalAt(link.cw, g).isClosed(lane)
        ) book(Offence.RED_X, 5.0)

        // Two-second gap (Rule 126).
        if (p.v > 13 && !exempt()) {
            val lead = sim.findLead(p, lane, Simulation.Lead())
            if (lead.vehicle != null && lead.gap / p.v < 1.0) tailgateTime += dt else tailgateTime = max(0.0, tailgateTime - 2 * dt)
            if (tailgateTime > 5) {
                book(Offence.TAILGATING, 30.0)
                tailgateTime = 0.0
            } else if (tailgateTime in 2.0..(2.0 + dt)) {
                sim.post("Too close: leave at least a two-second gap (Rule 126)", warning = true)
            }
        } else tailgateTime = 0.0

        // Overtaking on the left (Rule 268), unless in queues moving at similar speeds.
        if (p.v > Simulation.UNDERTAKE_SPEED && lane in 0 until Road.LANES - 1 && !exempt()) {
            for (o in link.list(lane + 1)) {
                if (o === p || o.s < p.s - 40 || o.s > p.s + 40) continue
                val ahead = o.rear > p.s - 1
                val was = wasAhead.put(o.id, ahead)
                if (was == true && !ahead && o.s < p.rear && o.v < p.v - 1.5 && o.v > Simulation.UNDERTAKE_SPEED && !o.occupies(lane)) {
                    book(Offence.UNDERTAKING, 20.0)
                }
            }
            if (wasAhead.size > 200) wasAhead.clear()
        }

        // Lane discipline (Rule 264): keep left unless overtaking.
        val zone = Road.hasExtraLane(p.s) || Road.junctionOffset(p.s) in Road.DIVERGE_START - 400..Road.MERGE_END
        if (lane >= 1 && p.v > 20 && !zone && !exempt() &&
            sim.closedAhead(link.cw, lane - 1, p.s, Simulation.CLOSURE_REACT) == null &&
            sim.nearestAhead(link, lane - 1, p.s - 15, p, 220.0) == null
        ) {
            hogTime += dt
            if (hogTime > 12 && !hogWarned) {
                hogWarned = true
                sim.post("Keep in the left lane unless overtaking (Rule 264)", warning = true)
            }
            if (hogTime > 30) {
                book(Offence.LANE_HOGGING, 60.0)
                hogTime = 0.0
            }
        } else {
            hogTime = max(0.0, hogTime - 3 * dt)
            if (hogTime == 0.0) hogWarned = false
        }

        // Right-hand lane restrictions (Rule 265).
        if (p.type.rightLaneBanned && p.occupies(Road.RIGHT_HAND_LANE) && !atWork()) {
            rightLaneTime += dt
            if (rightLaneTime > 3) { book(Offence.RIGHT_LANE, 30.0); rightLaneTime = 0.0 }
        } else rightLaneTime = 0.0

        // Hard shoulder (Rule 269).
        if (p.lat < -0.55 && !Road.hasExtraLane(p.s) && p.v > 2 && !atWork()) {
            shoulderTime += dt
            if (shoulderTime > 2) { book(Offence.HARD_SHOULDER, 30.0); shoulderTime = 0.0 }
        } else shoulderTime = 0.0

        // Stopping (Rule 271), unless queuing.
        val queueing = sim.findLead(p, lane, Simulation.Lead()).gap < 30
        if (p.v < 0.5 && !queueing && !atWork()) {
            stoppedTime += dt
            if (stoppedTime > 6) { book(Offence.STOPPING, 30.0); stoppedTime = 0.0 }
        } else stoppedTime = 0.0

        // Emergency vehicles (Rule 219).
        val behind = sim.followerIn(link, lane, p)
        if (behind != null && behind.beacon == Beacon.BLUE && p.rear - behind.s < 60 && lane > 0) {
            blockedTime += dt
            if (blockedTime > 6) { book(Offence.BLUE_LIGHTS, 30.0); blockedTime = 0.0 }
        } else blockedTime = 0.0
    }

    private fun resetMotorwayTimers() {
        tailgateTime = 0.0
        hogTime = 0.0
        rightLaneTime = 0.0
        shoulderTime = 0.0
        stoppedTime = 0.0
        blockedTime = 0.0
    }

    companion object {
        /** Twelve or more points within three years means disqualification. */
        const val BAN_POINTS = 12
    }
}
