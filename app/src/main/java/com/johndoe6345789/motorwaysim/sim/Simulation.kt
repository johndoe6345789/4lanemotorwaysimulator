package com.johndoe6345789.motorwaysim.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class TrafficLevel(val label: String, val vehiclesPerKmPerLane: Double, val localRoadInterval: Double) {
    LIGHT("Light", 7.0, 30.0),
    MODERATE("Moderate", 15.0, 14.0),
    HEAVY("Heavy", 24.0, 8.0),
    RUSH_HOUR("Rush hour", 31.0, 5.5);

    fun next(): TrafficLevel = entries[(ordinal + 1) % entries.size]
}

/** What an overhead gantry is displaying. */
data class GantrySignal(
    /** Mandatory variable speed limit, or null when the national limit applies (blank signals). */
    val limitMph: Int?,
    /** Bit i set: lane index i shows a red X. */
    val closedLanes: Int,
    /** Simulation time at which the speed limit was last changed. */
    val since: Double = -100.0,
    /** Text on the variable message sign, if any. */
    val message: String? = null,
) {
    fun isClosed(lane: Int) = lane >= 0 && (closedLanes shr lane) and 1 == 1

    companion object {
        val BLANK = GantrySignal(null, 0)
    }
}

/** A message for the HUD. */
class SimEvent(val time: Double, val text: String, val warning: Boolean)

/**
 * Microscopic traffic simulation of a dual four-lane motorway with junctions.
 *
 * Longitudinal behaviour uses the Intelligent Driver Model; lane changes use MOBIL
 * with a keep-left bias and a no-undertaking rule as described in the UK Highway
 * Code. Vehicles can leave at junctions, give way to the right at the roundabouts
 * and join either carriageway. Only a window of road around the player is
 * simulated: traffic is spawned at its edges and removed when it leaves.
 */
class Simulation(seed: Long = System.nanoTime()) {
    internal val rng = Random(seed)
    private var nextId = 1

    val network = Network()
    val vehicles = ArrayList<Vehicle>()
    lateinit var player: Vehicle
        private set

    var time = 0.0
        private set

    var trafficLevel = TrafficLevel.MODERATE
    var incidentsEnabled = true

    /** When true the player's car drives itself using the same model as the AI traffic. */
    var autopilot = false

    // Player controls (manual driving).
    var throttle = false
    var brake = false

    /** True from a crash until the player continues in a new car. */
    var crashed = false
        private set
    var crashTime = -100.0
        private set
    var crashes = 0
        private set
    var distanceTravelled = 0.0
        private set
    var cameraFlashes = 0
        private set
    var lastFlashTime = -100.0
        private set
    var lastFlashSpeedMph = 0
        private set
    var lastFlashLimitMph = 0
        private set

    /** Number of times the anti-overlap safety net had to separate two AI vehicles. */
    var aiInterventions = 0
        private set

    val incidents = ArrayList<Incident>()
    internal val responders = Responders(this)
    val events = ArrayList<SimEvent>()
    private var nextIncidentTime = 0.0

    private val signals = HashMap<Long, GantrySignal>()
    private var spawnTimer = 0.0
    private var signalTimer = 0.0
    private val localSpawnTimers = LinkedHashMap<PathLink, Double>()
    private val toRemove = ArrayList<Vehicle>()

    fun reset() {
        vehicles.clear()
        incidents.clear()
        signals.clear()
        events.clear()
        network.junctions.clear()
        localSpawnTimers.clear()
        time = 0.0
        crashed = false
        crashes = 0
        distanceTravelled = 0.0
        cameraFlashes = 0
        lastFlashTime = -100.0
        aiInterventions = 0
        throttle = false
        brake = false
        nextIncidentTime = 70.0 + rng.nextDouble() * 90.0

        val mw = network.motorway.getValue(Carriageway.NORTH)
        player = newPlayer(mw, START_S, 1, Units.mphToMs(60.0))
        player.updatePose()
        vehicles.add(player)
        network.maintain(focusY, JUNCTION_RANGE)
        populate()
        rebuildLists()
        for (c in vehicles) c.updatePose()
        updateSignals()
    }

    private fun newPlayer(link: Link, s: Double, lane: Int, v: Double) = Vehicle(
        id = nextId++, type = VehicleType.CAR, link = link, s = s, lane = lane, v = v,
        desiredFactor = 1.0, politeness = 0.3, yieldsToMergers = true, color = PLAYER_COLOR, isPlayer = true,
    )

    // ---------------------------------------------------------------- queries

    /** World y of the area being simulated: the player, or their wreck after a crash. */
    val focusY: Double get() = player.pose.y

    fun signalAt(cw: Carriageway, gantry: Long): GantrySignal = signals[key(cw, gantry)] ?: GantrySignal.BLANK

    private fun key(cw: Carriageway, gantry: Long) = gantry * 2 + cw.ordinal

    /** The gantry whose signals apply at carriageway position [s]. */
    fun gantryFor(s: Double): Long {
        var g = Road.gantryIndexAt(s)
        if (!Road.hasGantry(g)) g--
        return g
    }

    /** Speed limit (mph) in force at [s] on a carriageway. */
    fun limitMphAt(cw: Carriageway, s: Double): Int =
        signalAt(cw, gantryFor(s)).limitMph ?: Road.NATIONAL_LIMIT_MPH

    /** Speed limit (mph) in force where [c] is. */
    fun limitMphFor(c: Vehicle): Int {
        val link = c.link
        return if (link is MotorwayLink) limitMphAt(link.cw, c.s) else link.limitMph
    }

    fun isVariableLimitFor(c: Vehicle): Boolean {
        val link = c.link
        return link is MotorwayLink && signalAt(link.cw, gantryFor(c.s)).limitMph != null
    }

    fun averageSpeed(): Double {
        var sum = 0.0
        var n = 0
        for (c in vehicles) if (c.role == Role.TRAFFIC && c.link is MotorwayLink) { sum += c.v; n++ }
        return if (n == 0) 0.0 else sum / n
    }

    fun junctionOf(c: Vehicle): Junction? = c.link.junction

    // ---------------------------------------------------------------- controls

    /**
     * Steering input from the player. On the motorway it changes lane (◀ in lane 1 at a junction
     * takes the exit); on a roundabout ◀ takes the next exit and ▶ stays on the roundabout.
     */
    fun steer(direction: Int) {
        if (crashed) return
        autopilot = false
        val p = player
        when (val link = p.link) {
            is MotorwayLink -> {
                if (p.isChangingLane) return
                val target = p.lane + direction
                if (target == -1) {
                    if (!Road.hasExtraLane(p.s)) {
                        post("The hard shoulder is for emergencies only", warning = true)
                        return
                    }
                    if (Road.isMergeZone(p.s)) return
                }
                if (target < -1 || target >= Road.LANES) return
                p.startLaneChange(target, time, PLAYER_LANE_CHANGE_TIME)
            }
            is RingLink -> {
                p.ringExit = if (direction < 0) link.junction!!.nextExit(p.s) else null
            }
            else -> Unit
        }
    }

    /** Places a broken-down vehicle in a random lane some way ahead on the player's carriageway. */
    fun triggerIncident() {
        if (crashed) return
        val link = (player.link as? MotorwayLink) ?: network.motorway.getValue(Carriageway.NORTH)
        val cw = link.cw
        val lane = rng.nextInt(Road.LANES)
        val ahead = if (player.link === link) player.s else cw.sOf(focusY)
        var s = ahead + 820.0
        if (Road.hasExtraLane(s)) s += 300.0
        vehicles.removeAll { !it.isPlayer && it.link === link && it.occupies(lane) && it.s > s - 160 && it.rear < s + 20 }
        val type = if (rng.nextDouble() < 0.25) VehicleType.VAN else VehicleType.CAR
        val bd = Vehicle(nextId++, type, link, s, lane, 0.0, 1.0, 0.0, false, CAR_COLORS[rng.nextInt(CAR_COLORS.size)])
        bd.updatePose()
        vehicles.add(bd)
        responders.report(listOf(bd), crash = false)
        rebuildLists()
        updateSignals()
    }

    /** After a crash: carry on in a new car a little way back up the road. */
    fun continueAfterCrash() {
        if (!crashed) return
        val wreck = player
        val link = (wreck.link as? MotorwayLink) ?: network.motorway.getValue(Carriageway.NORTH)
        var anchor = if (wreck.link === link) wreck.s - 350 else link.cw.junctionS(Road.junctionNearest(focusY)) - 1300
        rebuildLists()
        for (attempt in 0 until 40) {
            for (lane in intArrayOf(0, 1, 2, 3)) {
                val probe = newPlayer(link, anchor, lane, 0.0)
                val lead = findLead(probe, lane, Lead())
                probe.v = if (lead.vehicle != null) min(lead.vehicle!!.v, 31.0) else 27.0
                if (fitsAt(probe, link, lane)) {
                    wreck.isPlayer = false
                    player = probe
                    vehicles.add(probe)
                    probe.updatePose()
                    crashed = false
                    throttle = false
                    brake = false
                    rebuildLists()
                    return
                }
            }
            anchor -= 40
        }
    }

    internal fun post(text: String, warning: Boolean = false) {
        events.add(SimEvent(time, text, warning))
        if (events.size > 20) events.removeAt(0)
    }

    // ---------------------------------------------------------------- stepping

    /** Advances the simulation by [dt] seconds (internally sub-stepped). */
    fun update(dt: Double) {
        var remaining = min(dt, 0.5)
        while (remaining > 1e-9) {
            val h = min(remaining, MAX_STEP)
            step(h)
            remaining -= h
        }
    }

    private fun step(dt: Double) {
        time += dt
        rebuildLists()

        for (c in vehicles) c.acc = computeAccel(c)

        for (c in vehicles) {
            if (c.role == Role.WRECK) continue
            if (c.isPlayer && !autopilot) continue
            if (c.link.kind == LinkKind.OFF_SLIP || c.link.kind == LinkKind.LOCAL_IN) decideEntry(c)
            if (time >= c.nextDecisionTime) {
                c.nextDecisionTime = time + 0.35 + rng.nextDouble() * 0.35
                if (c.link is MotorwayLink) decideLaneChange(c)
                if (c.link is RingLink && c.ringExit == null) c.ringExit = chooseExit(c, c.link.junction!!, c.enteredFrom)
            }
        }
        playerAssists()

        val before = player.s
        val beforeLink = player.link
        for (c in vehicles) {
            if (c.role == Role.WRECK) continue
            if (responders.controlsPosition(c)) continue
            c.v = max(0.0, c.v + c.acc * dt)
            if (c.isPlayer) c.v = min(c.v, PLAYER_TOP_SPEED)
            val ds = c.v * dt
            c.s += ds
            c.advanceLaneChange(dt)
            transfer(c, ds)
        }
        if (toRemove.isNotEmpty()) {
            vehicles.removeAll(toRemove.toSet())
            toRemove.clear()
        }
        if (player.link === beforeLink) distanceTravelled += max(0.0, player.link.forward(before, player.s))
        else distanceTravelled += player.v * dt

        for (c in vehicles) c.updatePose()
        rebuildLists()
        separateOverlappingAi()
        if (!crashed) {
            checkPlayerCollision()
            checkSpeedCamera(beforeLink, before)
        }

        responders.update(dt)

        spawnTimer -= dt
        if (spawnTimer <= 0) {
            spawnTimer = 0.25
            for (j in network.maintain(focusY, JUNCTION_RANGE)) {
                vehicles.removeAll { it.link.junction === j && !it.isPlayer }
                localSpawnTimers.keys.removeAll { it.junction === j }
            }
            despawn()
            spawn(0.25)
        }
        signalTimer -= dt
        if (signalTimer <= 0) {
            signalTimer = 1.5
            updateSignals()
        }
        if (incidentsEnabled && time >= nextIncidentTime) {
            if (incidents.none { !it.crash }) triggerIncident()
            nextIncidentTime = time + 150.0 + rng.nextDouble() * 150.0
        }
    }

    /** Moves vehicles from one link to the next when they reach the end of a road. */
    private fun transfer(c: Vehicle, ds: Double) {
        repeat(3) {
            when (val link = c.link) {
                is MotorwayLink -> {
                    if (c.lane != -1) return
                    val off = Road.junctionOffset(c.s)
                    if (off >= Road.DIVERGE_END && off < Road.DIVERGE_END + 60) {
                        val k = link.cw.junctionAt(c.s - off)
                        val slip = network.junction(k).offSlip.getValue(link.cw)
                        c.moveTo(slip, off - Road.DIVERGE_END, 0)
                        c.exitJunction = null
                        c.enteredFrom = slip
                        c.ringExit = if (!c.isPlayer || autopilot) chooseExit(c, slip.junction!!, slip) else null
                    } else return
                }
                is RingLink -> {
                    c.s = link.wrap(c.s)
                    c.ringTravelled += ds
                    val exit = c.ringExit ?: return
                    val d = link.forward(c.s - ds, exit.ringS)
                    if (d > ds) return
                    c.moveTo(exit.link, ds - d, 0)
                    c.ringExit = null
                }
                is PathLink -> {
                    if (c.s < link.length) return
                    val over = c.s - link.length
                    val next = link.next
                    if (next == null || (link.kind == LinkKind.LOCAL_OUT && !c.isPlayer)) {
                        toRemove.add(c)
                        return
                    }
                    when (link.kind) {
                        LinkKind.OFF_SLIP, LinkKind.LOCAL_IN -> {
                            val ring = next as RingLink
                            c.moveTo(ring, ring.wrap(link.nextS + over), 0)
                            c.ringTravelled = over - ds // the ring branch below adds this step's travel
                            c.enteredFrom = link
                            if (c.ringExit == null && (!c.isPlayer || autopilot)) c.ringExit = chooseExit(c, link.junction!!, link)
                        }
                        else -> c.moveTo(next, link.nextS + over, link.nextLane)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- driver model

    private fun desiredSpeedOf(c: Vehicle): Double {
        responders.desiredSpeed(c)?.let { return it }
        val link = c.link
        if (link !is MotorwayLink) {
            val limit = Units.mphToMs(link.limitMph.toDouble())
            return max(1.0, min(c.desiredSpeed(limit), link.advisorySpeed(c.s)))
        }
        var limit = Units.mphToMs(limitMphAt(link.cw, c.s).toDouble())
        // Drivers adapt to a lower limit shown on the next gantry before reaching it.
        val next = gantryFor(c.s) + 1
        if (next * Road.GANTRY_SPACING - c.s < 250) {
            signalAt(link.cw, next).limitMph?.let { limit = min(limit, Units.mphToMs(it.toDouble())) }
        }
        return max(1.0, c.desiredSpeed(limit))
    }

    private fun idmTo(c: Vehicle, v0: Double, leader: Vehicle?): Double {
        if (leader == null) return Idm.freeAccel(c.type, c.v, v0)
        return Idm.accel(c.type, c.v, v0, leader.rear - c.s, leader.v)
    }

    /** IDM acceleration in lane [l], including the no-undertaking rule relative to lane l+1. */
    private fun accelInLane(c: Vehicle, l: Int, v0: Double): Double {
        val lead = findLead(c, l, scratchLead)
        var a = Idm.accel(c.type, c.v, v0, lead.gap, lead.speed)
        val link = c.link
        if (link is MotorwayLink && l >= 0 && c.v > UNDERTAKE_SPEED && l + 1 < Road.LANES && !c.isResponder) {
            val right = nearestAhead(link, l + 1, c.s, c, max(60.0, 2.5 * c.v))
            if (right != null && right.role != Role.WRECK && right.v < c.v) {
                a = min(a, max(idmTo(c, v0, right), -3.0))
            }
        }
        return a
    }

    private fun computeAccel(c: Vehicle): Double {
        if (c.role == Role.WRECK) return 0.0
        if (c.isPlayer && !autopilot) {
            val drag = 0.2 + 0.00035 * c.v * c.v
            return when {
                brake -> -8.0
                throttle -> 3.4 * (1 - (c.v / PLAYER_TOP_SPEED).let { it * it }) - drag + 0.2
                else -> 0.0 // cruise control holds the current speed
            }
        }
        val v0 = desiredSpeedOf(c)
        var a = accelInLane(c, c.lane, v0)
        val link = c.link
        if (link !is MotorwayLink) return a
        if (c.isChangingLane) {
            a = min(a, findLead(c, c.fromLane, scratchLead).let { Idm.accel(c.type, c.v, v0, it.gap, it.speed) })
        } else if (c.yieldsToMergers) {
            // Let a driver waiting to merge into our lane in ahead of us ("zip merging").
            for (d in intArrayOf(-1, 1)) {
                val l = c.lane + d
                if (l < link.minLane || l > link.maxLane) continue
                for (m in link.list(l)) {
                    if (m.wantsLane == c.lane && m.rear > c.s && m.rear - c.s < 50.0) {
                        a = min(a, max(idmTo(c, v0, m), -2.5))
                    }
                }
            }
        }
        return a
    }

    /** Lane closures from incidents on carriageway [cw] ahead of [s] in lane [l]. */
    internal fun closedAhead(cw: Carriageway, l: Int, s: Double, within: Double): Incident? =
        incidents.firstOrNull { it.cw == cw && it.isClosed(l) && it.sceneFrontS > s - 5 && it.rearS - s < within }

    private fun decideLaneChange(c: Vehicle) {
        val link = c.link as MotorwayLink
        if (c.isChangingLane || c.task?.arrived == true) return
        val cw = link.cw
        val v0 = desiredSpeedOf(c)
        val off = Road.junctionOffset(c.s)

        // Route: plan to leave at the next junction?
        val centre = Road.nextJunctionCentre(c.s)
        val nextK = cw.junctionAt(centre)
        if (c.role == Role.TRAFFIC && !c.isPlayer && c.decidedJunction != nextK && centre - c.s < 2200) {
            c.decidedJunction = nextK
            val p = if (c.type.isHeavy) 0.07 else 0.16
            c.exitJunction = if (rng.nextDouble() < p && centre - c.s > 1100) nextK else null
        }
        val exiting = c.exitJunction == nextK && !c.isPlayer
        val inDiverge = Road.isDivergeZone(c.s)
        val inMerge = Road.isMergeZone(c.s)
        if (c.lane == -1 && inDiverge) return // committed to the exit
        if (c.exitJunction != null && c.exitJunction != nextK) c.exitJunction = null // missed it

        var urgentAny = 0.0
        val blocked = closedAhead(cw, c.lane, c.s, 700.0)
        if (blocked != null && !(c.task != null && c.task!!.lane == c.lane)) {
            urgentAny = 1.0 + 4.0 * (1.0 - (blocked.rearS - c.s) / 700.0).coerceIn(0.0, 1.0)
        }
        val cooldown = if (urgentAny > 0 || exiting || c.lane == -1 || c.task != null) 1.2 else LANE_CHANGE_COOLDOWN
        if (time - c.lastLaneChangeTime < cooldown) return

        val aCur = accelInLane(c, c.lane, v0)
        val oldLeader = nearestAhead(link, c.lane, c.s, c, Idm.LOOKAHEAD)
        val oldFollower = followerIn(link, c.lane, c)

        var bestTarget = Vehicle.NO_LANE
        var bestScore = 0.0
        var wanted = Vehicle.NO_LANE

        for (dir in intArrayOf(1, -1)) {
            val t = c.lane + dir
            if (!laneAllowed(c, t, exiting, inDiverge, inMerge)) continue
            val responderTarget = c.task?.let { responders.targetLane(c) == t } ?: false
            if (!responderTarget && closedAhead(cw, t, c.s, 700.0) != null) continue

            var urgency = urgentAny
            urgency += routeUrgency(c, dir, exiting, centre, off)
            urgency += responders.laneUrgency(c, t)
            urgency += moveOverUrgency(c, link, dir)

            val bSafe = if (urgency > 3.0 || (urgency > 0 && c.v < 8.0)) 6.0 else SAFE_BRAKING
            val newLeader = nearestAhead(link, t, c.s, c, Idm.LOOKAHEAD)
            val newFollower = followerIn(link, t, c)
            var safe = true
            if (newLeader != null && newLeader.rear - c.s < c.type.minGap) safe = false
            var aNfOld = 0.0
            var aNfNew = 0.0
            if (safe && newFollower != null) {
                if (c.rear - newFollower.s < newFollower.type.minGap) safe = false
                else if (newFollower.role != Role.WRECK) {
                    val v0f = desiredSpeedOf(newFollower)
                    aNfOld = idmTo(newFollower, v0f, newLeader)
                    aNfNew = idmTo(newFollower, v0f, c)
                    if (aNfNew < -bSafe) safe = false
                }
            }
            val aNew = accelInLane(c, t, v0)
            if (aNew < -bSafe && !responderTarget) safe = false
            if (!safe) {
                if (urgency > 0.5) wanted = t
                continue
            }
            var aOfOld = 0.0
            var aOfNew = 0.0
            if (oldFollower != null && oldFollower.role != Role.WRECK) {
                val v0o = desiredSpeedOf(oldFollower)
                aOfOld = idmTo(oldFollower, v0o, c)
                aOfNew = idmTo(oldFollower, v0o, oldLeader)
            }
            val incentive = aNew - aCur + c.politeness * ((aNfNew - aNfOld) + (aOfNew - aOfOld))
            var threshold = CHANGE_THRESHOLD + if (dir > 0) KEEP_LEFT_BIAS else -KEEP_LEFT_BIAS
            if (dir > 0 && c.type.isHeavy) threshold += 0.2
            if (t == -1) threshold = 0.0
            val score = incentive - threshold + urgency
            if (score > bestScore) {
                bestScore = score
                bestTarget = t
            }
        }

        if (bestTarget != Vehicle.NO_LANE) {
            val duration = when {
                c.isPlayer -> 2.5
                bestTarget == -1 || c.lane == -1 -> 2.2
                c.type.isHeavy -> 4.0
                else -> 2.4 + rng.nextDouble() * 1.4
            }
            c.startLaneChange(bestTarget, time, duration)
            // Make the move visible to drivers deciding later in this same step.
            link.list(bestTarget).add(c)
            link.list(bestTarget).sortWith(BY_POSITION)
        } else {
            c.wantsLane = wanted
            c.indicator = if (wanted != Vehicle.NO_LANE) (if (wanted > c.lane) 1 else -1) else 0
        }
    }

    private fun laneAllowed(c: Vehicle, t: Int, exiting: Boolean, inDiverge: Boolean, inMerge: Boolean): Boolean {
        if (t == -1) {
            if (!inDiverge || !exiting) return false
            val zoneEnd = c.s - Road.junctionOffset(c.s) + Road.DIVERGE_END
            return zoneEnd - c.s > c.v * 2.4 + 15
        }
        if (c.lane == -1 && inDiverge) return false
        if (c.isResponder) return t in 0 until Road.LANES
        return c.canUseLane(t)
    }

    /** Extra wish to change lane in direction [dir] to follow the vehicle's route. */
    private fun routeUrgency(c: Vehicle, dir: Int, exiting: Boolean, centre: Double, off: Double): Double {
        var u = 0.0
        if (exiting) {
            val toZone = centre + Road.DIVERGE_START - c.s
            val w = 0.4 + 3.0 * (1.0 - toZone / 2000.0).coerceIn(0.0, 1.0)
            u += if (dir < 0) w else -w
            if (toZone < 0 && c.lane == 0 && dir < 0) u += 2.0
        }
        if (c.lane == -1 && off in Road.MERGE_START..Road.MERGE_END && dir > 0) {
            val progress = (off - Road.MERGE_START) / (Road.MERGE_END - Road.MERGE_START)
            u += 1.5 + 4.0 * progress
        }
        // Courtesy: move out of lane 1 for traffic joining from a slip road.
        if (c.lane == 0 && dir > 0 && !exiting) {
            val o = Road.junctionOffset(c.s)
            if (o in Road.MERGE_START - 250..Road.MERGE_END - 50) u += 0.25
        }
        return u
    }

    /** Get out of the way of an emergency vehicle with blue lights approaching from behind. */
    private fun moveOverUrgency(c: Vehicle, link: MotorwayLink, dir: Int): Double {
        if (c.isResponder) return 0.0
        val behind = followerIn(link, c.lane, c) ?: return 0.0
        if (behind.beacon != Beacon.BLUE || c.rear - behind.s > 150) return 0.0
        return when {
            dir < 0 && c.lane > 0 -> 1.4
            dir > 0 && c.lane == 0 -> 0.8
            else -> 0.0
        }
    }

    /** Give way to the right: decide when it is safe to drive onto the roundabout. */
    private fun decideEntry(c: Vehicle) {
        val link = c.link as PathLink
        val port = link.ringPort ?: return
        val ring = link.next as RingLink
        val d = link.length - GIVE_WAY_SETBACK - c.s
        // Keep looking right until reaching the line; once there, go.
        if (d > 45 || (c.committed && d < 2.5)) return
        val vApproach = max(3.0, min(c.v, link.advisorySpeed(c.s)))
        val tArr = (max(d, 0.0) + GIVE_WAY_SETBACK + c.length) / vApproach
        var clear = true
        for (o in ring.list(0)) {
            if (conflicts(ring, port, o, o.s, tArr)) { clear = false; break }
        }
        // Drivers about to join from the previous arm count as circulating traffic.
        if (clear) for (e in link.junction!!.entries) {
            if (e === port) continue
            for (o in e.link.list(0)) {
                if (!o.committed) continue
                if (conflicts(ring, port, o, e.ringS - (e.link.length - o.s), tArr)) { clear = false; break }
            }
            if (!clear) break
        }
        c.committed = clear
    }

    /** Whether vehicle [o] at ring position [os] would conflict with joining at [port] in [tArr] seconds. */
    private fun conflicts(ring: RingLink, port: RingPort, o: Vehicle, os: Double, tArr: Double): Boolean {
        val du = ring.forward(os, port.ringS)
        // Someone leaving before reaching us is no conflict.
        val exitFirst = o.ringExit?.let { ring.forward(os, it.ringS) < du } ?: false
        if (!exitFirst && du < ring.length * 0.75 && du / max(o.v, 3.0) < tArr + 2.5) return true
        return ring.forward(port.ringS, os) < o.length + 6
    }

    /** Picks an exit from the roundabout for a vehicle that entered from [from]. */
    internal fun chooseExit(c: Vehicle, j: Junction, from: PathLink?): RingPort {
        val exits = j.exits
        val onSlips = exits.filter { it.link.kind == LinkKind.ON_SLIP }
        if (c.isPlayer) {
            // Autopilot: turn back onto the other carriageway.
            val cameFrom = j.offSlip.entries.firstOrNull { it.value === from }?.key
            val want = cameFrom?.opposite ?: Carriageway.NORTH
            return onSlips.first { (it.link.next as MotorwayLink).cw == want }
        }
        val choices = exits.filter { it.link !== from?.let { f -> j.localOut.entries.firstOrNull { e -> j.localIn[e.key] === f }?.value } }
        val weights = choices.map { e ->
            when {
                from?.kind == LinkKind.OFF_SLIP && e.link.kind == LinkKind.LOCAL_OUT -> 4.0
                from?.kind == LinkKind.OFF_SLIP -> 1.0
                e.link.kind == LinkKind.ON_SLIP -> 4.0
                else -> 1.0
            }
        }
        var r = rng.nextDouble() * weights.sum()
        for (i in choices.indices) {
            r -= weights[i]
            if (r <= 0) return choices[i]
        }
        return choices.last()
    }

    /** Small helpers for a manually driven player car. */
    private fun playerAssists() {
        val p = player
        if (crashed || autopilot) return
        val link = p.link
        if (link is MotorwayLink && p.lane == -1 && !p.isChangingLane) {
            val off = Road.junctionOffset(p.s)
            // The acceleration lane is ending: merge now.
            if (off in Road.MERGE_START..Road.MERGE_END && off > Road.MERGE_END - 30) {
                p.startLaneChange(0, time, PLAYER_LANE_CHANGE_TIME)
            }
        }
        if (link is RingLink) {
            val exit = p.ringExit
            p.indicator = if (exit != null && link.forward(p.s, exit.ringS) < 40) -1 else 0
        }
    }

    // ---------------------------------------------------------------- lane bookkeeping

    private fun rebuildLists() {
        for (link in network.allLinks()) for (l in link.lanes) l.clear()
        for (c in vehicles) {
            val link = c.link
            link.list(c.lane).add(c)
            if (c.isChangingLane && c.fromLane != c.lane) link.list(c.fromLane).add(c)
        }
        for (link in network.allLinks()) for (l in link.lanes) if (l.size > 1) l.sortWith(BY_POSITION)
    }

    /** The nearest vehicle occupying [lane] of [link] whose front is ahead of [from], within [maxDist]. */
    internal fun nearestAhead(link: Link, lane: Int, from: Double, c: Vehicle?, maxDist: Double): Vehicle? {
        if (lane < link.minLane || lane > link.maxLane) return null
        val list = link.list(lane)
        if (link is RingLink) {
            var best: Vehicle? = null
            var bd = maxDist
            for (o in list) {
                if (o === c) continue
                val d = link.forward(from, o.s)
                if (d > 0 && d < bd) { bd = d; best = o }
            }
            return best
        }
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].s <= from) lo = mid + 1 else hi = mid
        }
        for (i in lo until list.size) {
            val o = list[i]
            if (o === c) continue
            return if (o.s - from < maxDist) o else null
        }
        return null
    }

    /** The nearest vehicle occupying [lane] of [link] whose front is behind [c]'s front. */
    internal fun followerIn(link: Link, lane: Int, c: Vehicle): Vehicle? {
        if (lane < link.minLane || lane > link.maxLane) return null
        val list = link.list(lane)
        for (i in list.indices.reversed()) {
            val o = list[i]
            if (o !== c && o.s < c.s) return o
        }
        return null
    }

    internal class Lead {
        var gap = Double.POSITIVE_INFINITY
        var speed = 0.0
        var vehicle: Vehicle? = null

        fun consider(g: Double, v: Double, o: Vehicle?) {
            if (g < gap) { gap = g; speed = v; vehicle = o }
        }
    }

    private val scratchLead = Lead()
    private var contLink: Link? = null
    private var contLane = 0
    private var contStart = 0.0
    private var contDist = 0.0

    /**
     * The nearest obstacle ahead of [c] in [lane], following its route onto the next roads:
     * other vehicles, the end of an acceleration lane, a give-way line, or a responder's stopping point.
     */
    internal fun findLead(c: Vehicle, lane: Int, out: Lead): Lead {
        out.gap = Double.POSITIVE_INFINITY
        out.speed = 0.0
        out.vehicle = null
        var link: Link = c.link
        var l = lane
        var from = c.s
        var base = 0.0
        for (hop in 0 until 4) {
            val limit = Idm.LOOKAHEAD - base
            if (limit <= 0) break
            val hasNext = continuation(c, link, l, from)
            val searchTo = if (link is RingLink && hasNext) min(limit, contDist) else limit
            val o = nearestAhead(link, l, from, c, searchTo)
            if (o != null) out.consider(base + link.forward(from, o.s) - occupiedLength(o), o.v, o)
            // The tail of a vehicle that has just left the roundabout is still on the ring.
            if (link is RingLink) {
                for (port in link.junction!!.exits) {
                    for (x in port.link.list(0)) {
                        if (x.rear >= 0) break
                        val d = link.forward(from, link.wrap(port.ringS + x.rear))
                        if (d < searchTo) out.consider(base + d, x.v, x)
                    }
                }
            }
            // The tail of a vehicle that has just pulled onto the roundabout is still on this approach.
            if (link is PathLink && link.ringPort != null) {
                for (r in (link.next as RingLink).list(0)) {
                    if (r.enteredFrom === link && r.ringTravelled < r.length) {
                        val tail = link.length + r.ringTravelled - r.length
                        if (tail > from - r.length) out.consider(base + tail - from, r.v, r)
                    }
                }
            }
            val stop = virtualStop(c, link, l, from)
            if (stop >= from - 1.0) out.consider(base + (stop - from), 0.0, null)
            if (!hasNext) break
            base += contDist
            if (out.gap < base) break
            link = contLink!!
            l = contLane
            from = contStart
        }
        return out
    }

    /**
     * How much of [o] occupies its own road: on the roundabout, a vehicle that has only just
     * joined has the rest of its body still on the approach.
     */
    internal fun occupiedLength(o: Vehicle): Double =
        if (o.link is RingLink && o.ringTravelled < o.length) max(0.5, o.ringTravelled) else o.length

    /** Where [c]'s route continues after the end of [link] (sets the cont* fields). */
    private fun continuation(c: Vehicle, link: Link, l: Int, from: Double): Boolean {
        when (link) {
            is MotorwayLink -> {
                if (l != -1) return false
                val off = Road.junctionOffset(from)
                if (off !in Road.DIVERGE_START..Road.DIVERGE_END) return false
                val k = link.cw.junctionAt(from - off)
                contLink = network.junction(k).offSlip.getValue(link.cw)
                contLane = 0
                contStart = 0.0
                contDist = Road.DIVERGE_END - off
                return true
            }
            is RingLink -> {
                val exit = c.ringExit ?: return false
                contLink = exit.link
                contLane = 0
                contStart = 0.0
                contDist = link.forward(from, exit.ringS)
                return true
            }
            is PathLink -> {
                val next = link.next ?: return false
                when (link.kind) {
                    LinkKind.OFF_SLIP, LinkKind.LOCAL_IN -> if (!c.committed && !(c.isPlayer && !autopilot)) return false
                    LinkKind.LOCAL_OUT -> if (!c.isPlayer) return false
                    else -> Unit
                }
                contLink = next
                contLane = link.nextLane
                contStart = link.nextS
                contDist = link.length - from
                return true
            }
            else -> return false
        }
    }

    /** A position on [link] where [c] must stop, or -infinity. */
    private fun virtualStop(c: Vehicle, link: Link, l: Int, from: Double): Double {
        if (link is MotorwayLink) {
            if (l == -1) {
                val off = Road.junctionOffset(from)
                if (off in Road.MERGE_START - 1.0..Road.MERGE_END) return from - off + Road.MERGE_END
            }
            if (link === c.link) responders.stopPoint(c, l)?.let { return it }
            return Double.NEGATIVE_INFINITY
        }
        if ((link.kind == LinkKind.OFF_SLIP || link.kind == LinkKind.LOCAL_IN) && !c.committed &&
            !(c.isPlayer && !autopilot)
        ) {
            return link.length - GIVE_WAY_SETBACK
        }
        return Double.NEGATIVE_INFINITY
    }

    /**
     * The IDM is collision free, but the player can provoke impossible situations
     * (e.g. cutting in and stopping). Keep AI vehicles from driving through each other.
     */
    private fun separateOverlappingAi() {
        for (link in network.allLinks()) {
            for (list in link.lanes) {
                for (i in list.size - 2 downTo 0) {
                    val f = list[i]
                    val l = list[i + 1]
                    if (f.isPlayer || l.isPlayer || f.role == Role.WRECK || responders.controlsPosition(f)) continue
                    val rear = l.s - occupiedLength(l)
                    if (f.s > rear - 0.2) {
                        f.s = rear - 0.2
                        f.v = min(f.v, l.v)
                        aiInterventions++
                    }
                }
            }
        }
    }

    private fun checkPlayerCollision() {
        val p = player
        for (o in vehicles) {
            if (o === p) continue
            if (abs(o.pose.z - p.pose.z) > 2.5) continue
            if (abs(o.pose.x - p.pose.x) > 25 || abs(o.pose.y - p.pose.y) > 25) continue
            if (overlaps(p, o)) {
                crash(o)
                return
            }
        }
    }

    /** Oriented-rectangle overlap test (separating axis theorem) on the vehicles' footprints. */
    private fun overlaps(a: Vehicle, b: Vehicle): Boolean {
        val axes = doubleArrayOf(a.pose.heading, a.pose.heading + Math.PI / 2, b.pose.heading, b.pose.heading + Math.PI / 2)
        for (ang in axes) {
            val ax = cos(ang)
            val ay = sin(ang)
            val ca = a.pose.x * ax + a.pose.y * ay
            val cb = b.pose.x * ax + b.pose.y * ay
            if (abs(ca - cb) > extent(a, ax, ay) + extent(b, ax, ay)) return false
        }
        return true
    }

    private fun extent(v: Vehicle, ax: Double, ay: Double): Double {
        val hl = v.length / 2 - 0.1
        val hw = v.width / 2 - 0.1
        val fx = cos(v.pose.heading)
        val fy = sin(v.pose.heading)
        return hl * abs(fx * ax + fy * ay) + hw * abs(fy * ax - fx * ay)
    }

    private fun crash(other: Vehicle) {
        val p = player
        crashed = true
        crashTime = time
        crashes++
        val wrecks = ArrayList<Vehicle>()
        wrecks += p
        p.wreckYaw = (if (rng.nextBoolean()) 1 else -1) * (0.25 + rng.nextDouble() * 0.3)
        if (other.role == Role.TRAFFIC) {
            other.wreckYaw = (if (rng.nextBoolean()) 1 else -1) * (0.15 + rng.nextDouble() * 0.3)
            wrecks += other
        }
        for (w in wrecks) {
            w.updatePose()
        }
        // The wreck stays the "player" (followed by the camera) until the player continues.
        responders.report(wrecks, crash = true)
        updateSignals()
    }

    private fun checkSpeedCamera(beforeLink: Link, before: Double) {
        val link = player.link
        if (link !is MotorwayLink || beforeLink !== link) return
        val g = gantryFor(player.s)
        if (gantryFor(before) == g) return
        if (time - signalAt(link.cw, g).since < CAMERA_GRACE_PERIOD) return
        val limitMph = limitMphAt(link.cw, player.s)
        val mph = Units.msToMph(player.v)
        // Typical enforcement threshold: limit + 10% + 2 mph.
        if (mph > limitMph * 1.1 + 2) {
            cameraFlashes++
            lastFlashTime = time
            lastFlashSpeedMph = mph.toInt()
            lastFlashLimitMph = limitMph
        }
    }

    // ---------------------------------------------------------------- traffic management

    private fun updateSignals() {
        val lo = focusY - WINDOW - 2000
        val hi = focusY + WINDOW + 2000
        signals.clear()
        for (cw in Carriageway.entries) {
            val sLo = min(cw.sOf(lo), cw.sOf(hi))
            val sHi = max(cw.sOf(lo), cw.sOf(hi))
            val link = network.motorway.getValue(cw)
            for (g in Road.gantryIndexAt(sLo)..Road.gantryIndexAt(sHi)) {
                if (!Road.hasGantry(g)) continue
                val gs = g * Road.GANTRY_SPACING
                var limit: Int? = null
                var closed = 0
                var message: String? = null
                for (inc in incidents) {
                    if (inc.cw != cw || inc.closedLanes == 0) continue
                    val d = inc.rearS - gs
                    if (d in 0.0..1000.0) {
                        limit = minLimit(limit, 50)
                        closed = closed or inc.closedLanes
                        message = if (Integer.bitCount(inc.closedLanes) > 1) "LANES CLOSED" else "LANE CLOSED"
                    } else if (d in 1000.0..2200.0) {
                        limit = minLimit(limit, 60)
                        if (message == null) message = "INCIDENT AHEAD"
                    }
                }
                // Queue protection: slow traffic downstream lowers the limit upstream.
                var sum = 0.0
                var n = 0
                for (lane in 0 until Road.LANES) for (c in link.list(lane)) {
                    if (c.role == Role.TRAFFIC && c.lane == lane && c.s >= gs && c.s < gs + 1000) { sum += c.v; n++ }
                }
                if (n >= 10) {
                    val mph = Units.msToMph(sum / n)
                    when {
                        mph < 25 -> limit = minLimit(limit, 40)
                        mph < 40 -> limit = minLimit(limit, 50)
                        mph < 50 -> limit = minLimit(limit, 60)
                    }
                    if (mph < 50 && message == null) message = "QUEUE CAUTION"
                }
                val old = previousSignals[key(cw, g)]
                val since = if (old != null && old.limitMph != limit) time else old?.since ?: -100.0
                signals[key(cw, g)] = GantrySignal(limit, closed, since, message)
            }
        }
        previousSignals.clear()
        previousSignals.putAll(signals)
    }

    private val previousSignals = HashMap<Long, GantrySignal>()

    private fun minLimit(a: Int?, b: Int) = if (a == null) b else min(a, b)

    private fun despawn() {
        val lo = focusY - WINDOW - 60
        val hi = focusY + WINDOW + 60
        for (c in vehicles) {
            if (c.isPlayer || c.link !is MotorwayLink) continue
            if (c.pose.y < lo || c.pose.y > hi) toRemove.add(c)
        }
        if (toRemove.isNotEmpty()) {
            vehicles.removeAll(toRemove.toSet())
            toRemove.clear()
        }
        responders.prune()
    }

    private fun spawn(interval: Double) {
        val window = 2 * WINDOW
        val playerVy = if (player.link is MotorwayLink) (player.link as MotorwayLink).cw.dir * player.v else 0.0
        for (cw in Carriageway.entries) {
            val link = network.motorway.getValue(cw)
            for (l in 0 until Road.LANES) {
                var count = 0
                var speedSum = 0.0
                for (c in link.list(l)) {
                    if (c.lane == l && c.role == Role.TRAFFIC) { count++; speedSum += c.v }
                }
                val target = trafficLevel.vehiclesPerKmPerLane * LANE_SHARE[l] * window / 1000.0
                if (count >= target) continue
                val c = newVehicle(link, l, 0.0, 0.0)
                val laneSpeed = if (count > 0) speedSum / count else desiredSpeedOf(c)
                val rel = cw.dir * laneSpeed - playerVy
                val fromSouth = if (rng.nextDouble() < 0.2) rng.nextBoolean() else rel > 0
                val y = if (fromSouth) focusY - WINDOW + rng.nextDouble() * 30 else focusY + WINDOW - rng.nextDouble() * 30
                c.s = cw.sOf(y)
                if (Road.hasExtraLane(c.s)) continue
                c.v = min(desiredSpeedOf(c), laneSpeed * (0.9 + 0.1 * rng.nextDouble()))
                if (fitsAt(c, link, l)) {
                    c.decidedJunction = cw.junctionAt(Road.nextJunctionCentre(c.s)).takeIf { Road.nextJunctionCentre(c.s) - c.s < 1100 } ?: Int.MIN_VALUE
                    c.updatePose()
                    vehicles.add(c)
                    link.list(l).add(c)
                    link.list(l).sortWith(BY_POSITION)
                }
            }
        }
        // Local roads feed the junctions.
        for (j in network.junctions.values) {
            if (abs(j.centreY - focusY) > 2000) continue
            for (lin in j.localIn.values) {
                val t = (localSpawnTimers[lin] ?: (rng.nextDouble() * trafficLevel.localRoadInterval)) - interval
                if (t > 0) {
                    localSpawnTimers[lin] = t
                    continue
                }
                localSpawnTimers[lin] = trafficLevel.localRoadInterval * (0.5 + rng.nextDouble())
                val first = lin.list(0).firstOrNull()
                if (first != null && first.rear < 25) continue
                val c = newVehicle(lin, 0, 0.0, Units.mphToMs(35.0))
                if (c.type == VehicleType.COACH) continue
                c.ringExit = chooseExit(c, j, lin)
                c.updatePose()
                vehicles.add(c)
                lin.list(0).add(0, c)
            }
        }
    }

    /** Whether [c] can be inserted into lane [l] without forcing anyone to brake hard. */
    internal fun fitsAt(c: Vehicle, link: Link, l: Int): Boolean {
        val leader = nearestAhead(link, l, c.s, c, Idm.LOOKAHEAD)
        if (leader != null) {
            val gap = leader.rear - c.s
            if (gap < c.type.minGap + 0.6 * c.v * c.type.timeHeadway) return false
            c.v = min(c.v, leader.v + gap / 10.0)
        }
        val follower = followerIn(link, l, c)
        if (follower != null) {
            val gap = c.rear - follower.s
            if (gap < follower.type.minGap + 0.8 * follower.v * follower.type.timeHeadway) return false
            if (follower.role != Role.WRECK && idmTo(follower, desiredSpeedOf(follower), c) < -2.0) return false
        }
        return true
    }

    private fun populate() {
        for (cw in Carriageway.entries) {
            val link = network.motorway.getValue(cw)
            val playerHere = player.link === link
            for (l in 0 until Road.LANES) {
                val spacing = 1000.0 / (trafficLevel.vehiclesPerKmPerLane * LANE_SHARE[l])
                val sA = cw.sOf(focusY - WINDOW)
                val sB = cw.sOf(focusY + WINDOW)
                val sStart = max(sA, sB)
                val sEnd = min(sA, sB)
                var leader: Vehicle? = null
                var s = sStart - rng.nextDouble() * spacing
                while (s > sEnd) {
                    if (playerHere && l == player.lane && s < player.rear && (leader == null || leader.s > player.s)) {
                        leader = player
                        s = min(s, player.rear - 30)
                    }
                    val tooClose = playerHere && l == player.lane && s > player.rear - 30 && s < player.s + 40
                    if (tooClose || Road.hasExtraLane(s)) {
                        s -= spacing
                        continue
                    }
                    val c = newVehicle(link, l, s, 0.0)
                    var v = desiredSpeedOf(c) * 0.95
                    if (leader != null) {
                        val gap = leader.rear - s
                        v = min(v, min(leader.v, (gap - c.type.minGap) / c.type.timeHeadway))
                    }
                    c.v = max(0.0, v)
                    vehicles.add(c)
                    leader = c
                    s = c.rear - max(c.type.minGap + 2.0, (spacing - 6.0) * (0.7 + 0.6 * rng.nextDouble()))
                }
            }
        }
    }

    internal fun newVehicle(link: Link, lane: Int, s: Double, v: Double, forceType: VehicleType? = null): Vehicle {
        val r = rng.nextDouble()
        var type = forceType ?: when {
            r < 0.70 -> VehicleType.CAR
            r < 0.84 -> VehicleType.VAN
            r < 0.96 -> VehicleType.LORRY
            else -> VehicleType.COACH
        }
        // Heavy vehicles keep to the nearside lanes.
        if (forceType == null && type.isHeavy && (lane >= 2 && rng.nextDouble() < 0.8 || lane == Road.LORRY_BANNED_LANE)) {
            type = VehicleType.CAR
        }
        val factor = when (type) {
            VehicleType.CAR -> 0.92 + rng.nextDouble() * 0.24 // 64–81 mph on a 70 limit
            VehicleType.VAN -> 0.9 + rng.nextDouble() * 0.18
            else -> 1.0
        }
        val color = when (type) {
            VehicleType.LORRY, VehicleType.COACH -> HEAVY_COLORS[rng.nextInt(HEAVY_COLORS.size)]
            VehicleType.VAN -> VAN_COLORS[rng.nextInt(VAN_COLORS.size)]
            VehicleType.POLICE -> POLICE_COLOR
            VehicleType.RECOVERY -> RECOVERY_COLOR
            else -> CAR_COLORS[rng.nextInt(CAR_COLORS.size)]
        }
        return Vehicle(
            id = nextId++, type = type, link = link, s = s, lane = lane, v = v,
            desiredFactor = factor,
            politeness = 0.1 + rng.nextDouble() * 0.4,
            yieldsToMergers = rng.nextDouble() < 0.75,
            color = color,
        ).also { it.nextDecisionTime = time + rng.nextDouble() }
    }

    // Declared last so that every property above is initialised first.
    init {
        reset()
    }

    companion object {
        const val MAX_STEP = 1.0 / 60.0
        const val WINDOW = 900.0
        const val JUNCTION_RANGE = 2400.0
        const val START_S = 1200.0
        const val PLAYER_TOP_SPEED = 62.0 // ≈ 139 mph
        const val PLAYER_LANE_CHANGE_TIME = 1.3
        const val LANE_CHANGE_COOLDOWN = 4.0
        const val SAFE_BRAKING = 4.0
        const val CHANGE_THRESHOLD = 0.15
        const val KEEP_LEFT_BIAS = 0.3
        const val CAMERA_GRACE_PERIOD = 10.0
        const val GIVE_WAY_SETBACK = 5.0

        /** Above this speed (≈ 38 mph) vehicles must not pass slower traffic on its left. */
        val UNDERTAKE_SPEED = Units.mphToMs(38.0)

        /** Relative use of each lane (lane 1 → lane 4). */
        val LANE_SHARE = doubleArrayOf(1.1, 1.05, 1.0, 0.85)

        const val PLAYER_COLOR = 0xFFFFC21A.toInt()
        const val POLICE_COLOR = 0xFFF4F6F8.toInt()
        const val RECOVERY_COLOR = 0xFFF2B705.toInt()
        val CAR_COLORS = intArrayOf(
            0xFFF2F2F2.toInt(), 0xFF1E1E1E.toInt(), 0xFFA8ADB3.toInt(), 0xFF5B6168.toInt(),
            0xFF1F3A68.toInt(), 0xFFB3202A.toInt(), 0xFF2F6FB5.toInt(), 0xFF2E5E3A.toInt(),
            0xFF7A2E3A.toInt(), 0xFFD9D4C7.toInt(), 0xFF3C3F44.toInt(), 0xFFE07A22.toInt(),
        )
        val VAN_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFE9E9E9.toInt(), 0xFF8A9199.toInt(), 0xFF1C4E8A.toInt(),
        )
        val HEAVY_COLORS = intArrayOf(
            0xFFEDEDED.toInt(), 0xFF2B5DA8.toInt(), 0xFFB82E2E.toInt(), 0xFF2F7D4F.toInt(),
            0xFF333333.toInt(), 0xFFD8A31A.toInt(),
        )

        internal val BY_POSITION = Comparator<Vehicle> { a, b ->
            val c = a.s.compareTo(b.s)
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }
}
