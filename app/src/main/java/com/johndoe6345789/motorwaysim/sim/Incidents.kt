package com.johndoe6345789.motorwaysim.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A breakdown or crash and the emergency response to it. */
class Incident(val id: Int, val cw: Carriageway?, val crash: Boolean) {
    enum class Stage { WAITING_FOR_POLICE, POLICE_EN_ROUTE, POLICE_ON_SCENE, RECOVERY_EN_ROUTE, LOADING, CLEARING, DONE }

    val wrecks = ArrayList<Vehicle>()

    /** A breakdown that made it onto the hard shoulder (Rule 277): no lanes need closing. */
    var hardShoulder = false
        internal set

    /** Waiting for the player, driving a police car or recovery truck, to attend. */
    var awaitingPlayer = false
        internal set
    var police: Vehicle? = null
    var recovery: Vehicle? = null
    var stage = Stage.WAITING_FOR_POLICE
        internal set
    var stageTime = 0.0
        internal set

    /** Bit i set: lane index i is blocked. */
    val closedLanes: Int
        get() {
            var mask = 0
            for (w in wrecks) if (w.link is MotorwayLink && w.lane >= 0) mask = mask or (1 shl w.lane)
            return mask
        }

    fun isClosed(lane: Int) = lane >= 0 && (closedLanes shr lane) and 1 == 1

    /** Rearmost point of the scene (carriageway position). */
    val rearS: Double get() = wrecks.minOfOrNull { it.rear } ?: 0.0
    val frontS: Double get() = wrecks.maxOfOrNull { it.s } ?: 0.0

    /** Front of the whole scene, including a recovery truck parked ahead of the wreck. */
    val sceneFrontS: Double
        get() = max(frontS, recovery?.takeIf { it.task?.arrived == true || it.rear > frontS }?.s ?: frontS)

    companion object {
        /** The lane a wreck stands in (-1: the hard shoulder or a junction's extra lane). */
        fun blockedLane(w: Vehicle) = w.lane
    }
}

/**
 * Police and recovery. When an incident is reported, a police car is sent with blue
 * lights. It parks behind the incident in the closed lane to protect it. A recovery
 * truck with amber beacons then crawls past the scene, pulls in ahead of the
 * broken-down or crashed vehicle, reverses up to it and winches it onto the flatbed
 * before driving away. Once everything is cleared the police leave and the lanes
 * reopen.
 */
class Responders(private val sim: Simulation) {
    private var nextIncidentId = 1

    fun report(wrecks: List<Vehicle>, crash: Boolean) {
        val onMotorway = wrecks.all { it.link is MotorwayLink && it.link === wrecks[0].link }
        val cw = if (onMotorway) (wrecks[0].link as MotorwayLink).cw else null
        val inc = Incident(nextIncidentId++, cw, crash)
        for (w in wrecks) {
            w.role = Role.WRECK
            w.v = 0.0
            w.acc = 0.0
            w.hazards = true
            w.indicator = 0
            w.task = null
            w.exitJunction = null
            w.ringExit = null
            inc.wrecks += w
        }
        sim.incidents += inc
        inc.hardShoulder = !crash && cw != null && inc.wrecks.all { it.lane == -1 }
        val player = sim.player
        if (inc.hardShoulder) {
            // Off the running lanes: just the recovery truck is needed.
            setStage(inc, Incident.Stage.POLICE_ON_SCENE)
            sim.post("Breakdown on the hard shoulder ahead: recovery on its way. Give it room (Rule 264)")
            return
        }
        if (!crash && cw != null && player.type == VehicleType.POLICE && !sim.crashed && !sim.autopilot) {
            inc.awaitingPlayer = true
            sim.post("Control: breakdown in ${laneText(inc.closedLanes)} ahead. Attend with blue lights and stop behind it", warning = true)
            return
        }
        if (!crash && cw != null) {
            // A traffic patrol is already on scene, protecting the broken-down vehicle.
            val link = sim.network.motorway.getValue(cw)
            val rearmost = inc.wrecks.minBy { it.rear }
            val lane = Incident.blockedLane(rearmost)
            val stopS = rearmost.rear - POLICE_GAP
            sim.vehicles.removeAll { !it.isPlayer && it.link === link && it.occupies(lane) && it.s > stopS - 15 && it.rear < rearmost.rear }
            val police = sim.newVehicle(link, lane, stopS, 0.0, VehicleType.POLICE)
            equip(police, inc)
            police.task!!.apply { this.lane = lane; this.stopS = stopS; arrived = true }
            police.updatePose()
            sim.vehicles.add(police)
            inc.police = police
            setStage(inc, Incident.Stage.POLICE_ON_SCENE)
        }
        val lanes = if (cw != null) laneText(inc.closedLanes) else ""
        sim.post(
            when {
                crash && cw != null -> "Crash: $lanes blocked. Police and recovery called"
                crash -> "Crash at the junction. Recovery called"
                else -> "Breakdown: $lanes blocked. Police on the way"
            },
            warning = true,
        )
    }

    private fun laneText(mask: Int): String {
        val lanes = (0 until Road.LANES).filter { (mask shr it) and 1 == 1 }.map { it + 1 }
        return if (lanes.size == 1) "lane ${lanes[0]}" else "lanes ${lanes.joinToString(" & ")}"
    }

    fun update(dt: Double) {
        val iterator = sim.incidents.iterator()
        while (iterator.hasNext()) {
            val inc = iterator.next()
            inc.stageTime += dt
            if (inc.cw == null) updateLocal(inc) else updateMotorway(inc, dt)
            if (inc.stage == Incident.Stage.DONE) iterator.remove()
        }
    }

    /** Off the motorway the scene is simply cleared after a while. */
    private fun updateLocal(inc: Incident) {
        if (inc.stageTime < LOCAL_CLEAR_TIME) return
        val gone = inc.wrecks.filter { !it.isPlayer }
        sim.vehicles.removeAll(gone.toSet())
        inc.wrecks.removeAll(gone.toSet())
        if (inc.wrecks.isEmpty()) {
            inc.stage = Incident.Stage.DONE
            sim.post("The junction has been cleared")
        }
    }

    private fun setStage(inc: Incident, stage: Incident.Stage) {
        inc.stage = stage
        inc.stageTime = 0.0
    }

    private fun updateMotorway(inc: Incident, dt: Double) {
        val cw = inc.cw!!
        val link = sim.network.motorway.getValue(cw)
        when (inc.stage) {
            Incident.Stage.WAITING_FOR_POLICE -> if (inc.awaitingPlayer) {
                if (sim.autopilot || sim.crashed) {
                    // The player has handed over: send a patrol instead.
                    inc.awaitingPlayer = false
                    inc.stageTime = 0.0
                } else if (playerProtecting(inc, link)) {
                    inc.awaitingPlayer = false
                    sim.post("Scene protected. Recovery truck on its way")
                    setStage(inc, Incident.Stage.POLICE_ON_SCENE)
                } else if (inc.stageTime > PLAYER_TIMEOUT) {
                    inc.awaitingPlayer = false
                    inc.stageTime = 0.0
                }
            } else if (inc.stageTime > POLICE_DELAY) {
                val rearmost = inc.wrecks.minByOrNull { it.rear } ?: return setStage(inc, Incident.Stage.CLEARING)
                val police = dispatch(inc, link, VehicleType.POLICE) ?: return
                police.task!!.apply {
                    lane = Incident.blockedLane(rearmost)
                    stopS = rearmost.rear - POLICE_GAP
                }
                inc.police = police
                setStage(inc, Incident.Stage.POLICE_EN_ROUTE)
            }
            Incident.Stage.POLICE_EN_ROUTE -> {
                val p = inc.police!!
                val t = p.task!!
                if (inc.stageTime > ARRIVAL_TIMEOUT) teleport(p, link, t.lane, t.stopS)
                if (p.lane == t.lane && !p.isChangingLane && p.s > t.stopS - 6 && p.s < inc.rearS && p.v < 0.8) {
                    t.arrived = true
                    p.v = 0.0
                    sim.post("Police on scene")
                    setStage(inc, Incident.Stage.POLICE_ON_SCENE)
                }
            }
            Incident.Stage.POLICE_ON_SCENE -> if (inc.stageTime > if (inc.crash) RECOVERY_DELAY else 1.0) {
                val wreck = inc.wrecks.maxByOrNull { it.s } ?: return setStage(inc, Incident.Stage.CLEARING)
                val p = sim.player
                if (p.type == VehicleType.RECOVERY && !sim.crashed && !sim.autopilot && p.task == null && inc.stageTime < PLAYER_TIMEOUT) {
                    // The player is the recovery truck: wait for them to pull in ahead of the casualty.
                    if (!inc.awaitingPlayer) {
                        inc.awaitingPlayer = true
                        val where = if (wreck.lane < 0) "on the hard shoulder" else "in lane ${wreck.lane + 1}"
                        sim.post("Recovery job: vehicle $where ahead. Pull in ahead of it and stop", warning = true)
                    }
                    if (playerReadyToRecover(wreck, link)) {
                        inc.awaitingPlayer = false
                        p.task = Task(inc).apply {
                            target = wreck
                            lane = wreck.lane
                            stopS = p.s
                            arrived = true
                        }
                        p.v = 0.0
                        p.hazards = true
                        inc.recovery = p
                        setStage(inc, Incident.Stage.LOADING)
                    }
                    return
                }
                inc.awaitingPlayer = false
                val truck = dispatch(inc, link, VehicleType.RECOVERY) ?: return
                val lane = Incident.blockedLane(wreck)
                truck.task!!.apply {
                    target = wreck
                    this.lane = lane
                    approachLane = pickApproachLane(inc, lane, truck)
                    stopS = wreck.s + RECOVERY_PULL_IN + truck.length
                }
                inc.recovery = truck
                sim.post("Recovery truck on its way")
                setStage(inc, Incident.Stage.RECOVERY_EN_ROUTE)
            }
            Incident.Stage.RECOVERY_EN_ROUTE -> {
                val truck = inc.recovery!!
                val t = truck.task!!
                val wreck = t.target!!
                if (inc.stageTime > ARRIVAL_TIMEOUT) teleport(truck, link, t.lane, t.stopS)
                // Having crawled past the scene, pull into the closed lane ahead of the wreck.
                if (truck.lane == t.approachLane && !truck.isChangingLane && truck.rear > wreck.s + 3) {
                    truck.indicator = if (t.lane > truck.lane) 1 else -1
                    // Only once nobody is alongside or just behind in that lane (e.g. traffic using an exit lane).
                    val behind = sim.followerIn(link, t.lane, truck)
                    val ahead = sim.nearestAhead(link, t.lane, truck.rear, truck, 40.0)
                    if ((behind == null || truck.rear - behind.s > 4 + behind.v) && (ahead == null || ahead.rear - truck.s > 3)) {
                        truck.startLaneChange(t.lane, sim.time, 2.2)
                    }
                }
                if (truck.lane == t.lane && !truck.isChangingLane && truck.s > t.stopS - 6 && truck.v < 0.8) {
                    t.arrived = true
                    truck.v = 0.0
                    truck.hazards = true
                    setStage(inc, Incident.Stage.LOADING)
                }
            }
            Incident.Stage.LOADING -> {
                val truck = inc.recovery!!
                val wreck = truck.task!!.target!!
                // Reverse up to the wreck, then winch it aboard.
                val parkS = wreck.s + RECOVERY_BACK_GAP + truck.length
                if (truck.free) truck.lat += (wreck.lane - truck.lat) * min(1.0, dt * 1.5) // line up with the casualty
                if (truck.s > parkS + 0.05) {
                    // Only reverse when nobody else is in the way.
                    val behind = sim.followerIn(link, truck.lane, truck)
                    if (behind === wreck || behind == null || behind.s < truck.rear - REVERSE_SPEED * dt - 1.0) {
                        truck.s = max(parkS, truck.s - REVERSE_SPEED * dt)
                    }
                    return
                }
                wreck.loader = truck
                wreck.loadProgress = min(1.0, wreck.loadProgress + dt / WINCH_TIME)
                if (wreck.loadProgress < 1.0) return
                sim.vehicles.remove(wreck)
                inc.wrecks.remove(wreck)
                truck.carrying = wreck
                wreck.loader = null
                truck.task = null
                truck.hazards = false
                inc.recovery = null
                val where = if (wreck.lane < 0) "the hard shoulder" else "lane ${wreck.lane + 1}"
                if (truck.isPlayer) {
                    sim.jobsDone++
                    sim.post("Job done: the vehicle is on your flatbed. Build up speed, signal and rejoin (Rule 278)")
                } else sim.post("Recovery truck has cleared $where")
                if (wreck.isPlayer) sim.continueAfterCrash()
                setStage(inc, if (inc.wrecks.isEmpty()) Incident.Stage.CLEARING else Incident.Stage.POLICE_ON_SCENE)
            }
            Incident.Stage.CLEARING -> if (inc.stageTime > POLICE_LEAVE_DELAY) {
                inc.police?.let { release(it) }
                inc.police = null
                sim.post("Incident cleared: all lanes open")
                setStage(inc, Incident.Stage.DONE)
            }
            Incident.Stage.DONE -> Unit
        }
    }

    /**
     * Creates a responder upstream of the incident: close to it when that is out of the
     * player's sight, otherwise at the upstream edge of the simulated area.
     */
    private fun dispatch(inc: Incident, link: MotorwayLink, type: VehicleType): Vehicle? {
        val cw = link.cw
        val edge = min(cw.sOf(sim.focusY - Simulation.WINDOW + 40), cw.sOf(sim.focusY + Simulation.WINDOW - 40))
        val near = inc.rearS - 260
        val p = sim.player
        val playerS = if (p.link === link && !sim.crashed) p.s else Double.NEGATIVE_INFINITY
        var s = if (!inc.crash && near > playerS + 350) near else min(edge, inc.rearS - 300)
        // Not in a junction's extra-lane section: move on past it.
        val off = Road.junctionOffset(s)
        if (off in Road.DIVERGE_START..Road.DIVERGE_END) s += Road.DIVERGE_END - off + 20
        if (off in Road.MERGE_START..Road.MERGE_END) s += Road.MERGE_END - off + 20
        for (lane in intArrayOf(3, 2, 1, 0)) {
            val c = sim.newVehicle(link, lane, s, 0.0, type)
            val lead = sim.findLead(c, lane, Simulation.Lead())
            c.v = min(if (type == VehicleType.POLICE) 30.0 else 25.0, (lead.vehicle?.v ?: 30.0) + 2)
            if (!sim.fitsAt(c, link, lane)) continue
            equip(c, inc)
            c.updatePose()
            sim.vehicles.add(c)
            link.list(lane).add(c)
            link.list(lane).sortWith(Simulation.BY_POSITION)
            return c
        }
        return null
    }

    private fun equip(c: Vehicle, inc: Incident) {
        c.role = if (c.type == VehicleType.POLICE) Role.POLICE else Role.RECOVERY
        c.beacon = if (c.type == VehicleType.POLICE) Beacon.BLUE else Beacon.AMBER
        c.task = Task(inc)
    }

    /** The player's police car is stopped behind the incident, in its lane, with blue lights on. */
    private fun playerProtecting(inc: Incident, link: MotorwayLink): Boolean {
        val p = sim.player
        if (p.link !== link || p.v > 0.6) return false
        val rearmost = inc.wrecks.minByOrNull { it.rear } ?: return false
        if (!p.occupies(rearmost.lane) || p.s > rearmost.rear - 1 || p.s < rearmost.rear - 60) return false
        if (p.beacon != Beacon.BLUE) {
            if ((inc.stageTime * 10).toInt() % 80 == 0) sim.post("Switch on your blue lights to protect the scene", warning = true)
            return false
        }
        return true
    }

    /** The player's recovery truck is stopped ahead of the casualty vehicle, in its lane. */
    private fun playerReadyToRecover(wreck: Vehicle, link: MotorwayLink): Boolean {
        val p = sim.player
        if (p.link !== link || p.v > 0.6) return false
        val inLane = if (wreck.lane < 0) p.lat < -0.3 else p.occupies(wreck.lane)
        return inLane && p.rear > wreck.s + 0.5 && p.rear < wreck.s + 45
    }

    /** An open lane next to (or near) the casualty's lane that [truck] may use (Rule 265). */
    private fun pickApproachLane(inc: Incident, lane: Int, truck: Vehicle): Int {
        if (lane < 0) return 0
        val candidates = listOf(lane + 1, lane - 1, lane + 2, lane - 2)
        return candidates.firstOrNull { it in 0 until Road.LANES && !inc.isClosed(it) && truck.canUseLane(it) } ?: lane
    }

    private fun teleport(v: Vehicle, link: MotorwayLink, lane: Int, s: Double) {
        v.moveTo(link, s, lane)
        v.v = 0.0
        v.updatePose()
    }

    /** A responder whose job is done rejoins the traffic. */
    private fun release(v: Vehicle) {
        v.task = null
        v.beacon = Beacon.NONE
        v.hazards = false
    }

    /** Drops incidents that have left the simulated area, and re-sends responders that did. */
    fun prune() {
        val alive = sim.vehicles.toHashSet()
        val iterator = sim.incidents.iterator()
        while (iterator.hasNext()) {
            val inc = iterator.next()
            if (inc.wrecks.any { it !in alive }) {
                inc.police?.let { if (it in alive) release(it) }
                inc.recovery?.let { if (it in alive) release(it) }
                for (w in inc.wrecks) w.loader = null
                sim.vehicles.removeAll(inc.wrecks.filter { !it.isPlayer }.toSet())
                iterator.remove()
                continue
            }
            if (inc.police != null && inc.police !in alive) {
                inc.police = null
                if (inc.stage == Incident.Stage.POLICE_EN_ROUTE) setStage(inc, Incident.Stage.WAITING_FOR_POLICE)
            }
            if (inc.recovery != null && inc.recovery !in alive) {
                inc.recovery = null
                for (w in inc.wrecks) { w.loader = null; w.loadProgress = 0.0 }
                setStage(inc, Incident.Stage.POLICE_ON_SCENE)
            }
        }
    }

    // ---------------------------------------------------------------- driving hooks

    /** Speed a responder wants to drive at, or null to drive like normal traffic. */
    fun desiredSpeed(c: Vehicle): Double? {
        val t = c.task ?: return null
        if (t.arrived) return 1.0
        return when (c.role) {
            Role.POLICE -> POLICE_SPEED
            Role.RECOVERY -> {
                val w = t.target ?: return RECOVERY_SPEED
                // Crawl past the scene before pulling in.
                if (w.s - c.s < 250 || c.rear > w.s) CRAWL_SPEED else RECOVERY_SPEED
            }
            else -> null
        }
    }

    /** The lane a responder is heading for, or [Vehicle.NO_LANE]. */
    fun targetLane(c: Vehicle): Int {
        val t = c.task ?: return Vehicle.NO_LANE
        if (c.role == Role.RECOVERY) {
            val w = t.target ?: return Vehicle.NO_LANE
            return if (c.rear > w.s + 3) t.lane else t.approachLane
        }
        return t.lane
    }

    fun laneUrgency(c: Vehicle, t: Int): Double {
        val task = c.task ?: return 0.0
        val target = targetLane(c)
        if (target == Vehicle.NO_LANE || c.s < task.incident.rearS - 1200) return 0.0
        if (c.lane == target) return -4.0
        return if ((t - c.lane) * (target - c.lane) > 0) 4.0 else -4.0
    }

    /** Where a responder should stop in [lane] (carriageway position), if anywhere. */
    fun stopPoint(c: Vehicle, lane: Int): Double? {
        val t = c.task ?: return null
        if (lane != t.lane) return null
        if (c.role == Role.RECOVERY) {
            val w = t.target ?: return null
            if (c.rear <= w.s) return null
        }
        // Having overshot, stop as soon as is comfortable; the truck reverses back to the casualty anyway.
        return max(t.stopS, c.s + c.v * c.v / 3.0 + 1.0)
    }

    /** True while the incident logic moves this vehicle itself (a truck reversing or loading). */
    fun controlsPosition(c: Vehicle): Boolean =
        c.type == VehicleType.RECOVERY && c.task?.arrived == true

    companion object {
        const val POLICE_DELAY = 5.0
        const val RECOVERY_DELAY = 6.0
        const val POLICE_LEAVE_DELAY = 8.0
        const val ARRIVAL_TIMEOUT = 150.0
        const val PLAYER_TIMEOUT = 180.0
        const val LOCAL_CLEAR_TIME = 30.0
        const val POLICE_GAP = 18.0
        const val RECOVERY_PULL_IN = 26.0
        const val RECOVERY_BACK_GAP = 2.5
        const val REVERSE_SPEED = 2.5
        const val WINCH_TIME = 9.0
        const val POLICE_SPEED = 38.0
        const val RECOVERY_SPEED = 30.0
        const val CRAWL_SPEED = 7.0
    }
}
