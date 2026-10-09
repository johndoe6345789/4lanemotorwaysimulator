package com.johndoe6345789.motorwaysim.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class IdmTest {
    @Test
    fun freeRoadConvergesToDesiredSpeed() {
        var v = 0.0
        val v0 = 30.0
        repeat(60 * 120) { v += Idm.freeAccel(VehicleType.CAR, v, v0) / 60.0 }
        assertEquals(v0, v, 1.0)
    }

    @Test
    fun stopsBehindStationaryObstacleWithoutCollision() {
        var s = 0.0
        var v = 31.0
        val obstacle = 400.0
        repeat(60 * 120) {
            val a = Idm.accel(VehicleType.CAR, v, 31.0, obstacle - s, 0.0)
            v = maxOf(0.0, v + a / 60.0)
            s += v / 60.0
            assertTrue("drove into the obstacle", s < obstacle)
        }
        assertEquals(0.0, v, 0.1)
        assertTrue("stopped far too early", obstacle - s < 6.0)
    }
}

class JunctionGeometryTest {
    @Test
    fun slipRoadsMeetTheMotorwayAndTheRoundabout() {
        val net = Network()
        val j = net.junction(1)
        val p = Pose()
        val q = Pose()
        for (cw in Carriageway.entries) {
            val mw = net.motorway.getValue(cw)
            // Exit slip starts where the diverge lane ends.
            j.offSlip.getValue(cw).pose(0.0, 0.0, p)
            mw.pose(cw.junctionS(1) + Road.DIVERGE_END, -1.0, q)
            assertTrue("off-slip start ${p.x},${p.y} vs ${q.x},${q.y}", hypot(p.x - q.x, p.y - q.y) < 0.5)
            assertEquals(q.heading, p.heading, 0.05)
            // Entry slip ends at the acceleration lane.
            val on = j.onSlip.getValue(cw)
            on.pose(on.length, 0.0, p)
            mw.pose(on.nextS, -1.0, q)
            assertTrue(hypot(p.x - q.x, p.y - q.y) < 0.5)
            assertEquals(0.05, p.z, 0.01)
        }
        // Every entry and exit touches the elevated ring.
        for (port in j.entries + j.exits) {
            port.link.pose(if (port.isEntry) port.link.length else 0.0, 0.0, p)
            j.ring.pose(port.ringS, 0.0, q)
            assertTrue("port ${port.name} off the ring", hypot(p.x - q.x, p.y - q.y) < 0.5)
            assertEquals(Road.RING_HEIGHT, p.z, 0.05)
        }
    }

    @Test
    fun roundaboutCirculatesClockwiseOverTheMotorway() {
        val j = Network().junction(0)
        val a = Pose()
        val b = Pose()
        j.ring.pose(0.0, 0.0, a)
        j.ring.pose(5.0, 0.0, b)
        // Clockwise seen from above: the cross product of radius and motion points down.
        val cross = (a.x - 0) * (b.y - a.y) - (a.y - j.centreY) * (b.x - a.x)
        assertTrue(cross < 0)
        assertTrue(Road.RING_RADIUS > Road.WIDTH + Road.CR_HALF + 2)
    }
}

class SimulationTest {
    private fun run(sim: Simulation, seconds: Double, check: (Simulation) -> Unit = {}) {
        var t = 0.0
        while (t < seconds) {
            sim.update(1.0 / 30.0)
            check(sim)
            t += 1.0 / 30.0
        }
    }

    private fun quietSim(seed: Long, level: TrafficLevel = TrafficLevel.MODERATE): Simulation {
        val sim = Simulation(seed)
        sim.trafficLevel = level
        sim.incidentsEnabled = false
        sim.reset()
        return sim
    }

    @Test
    fun autopilotDrivesSafelyAtEveryTrafficLevel() {
        for (level in TrafficLevel.entries) {
            val sim = quietSim(42, level)
            sim.autopilot = true
            run(sim, 300.0)
            assertFalse("crashed in $level traffic", sim.crashed)
            assertEquals("AI vehicles overlapped in $level traffic", 0, sim.aiInterventions)
            assertTrue("no traffic in $level", sim.vehicles.size > 40)
            assertTrue("made no progress in $level", sim.distanceTravelled > 1000.0)
        }
    }

    @Test
    fun bothCarriagewaysCarryTraffic() {
        val sim = quietSim(4)
        val counts = sim.vehicles.groupingBy { (it.link as? MotorwayLink)?.cw }.eachCount()
        assertTrue(counts.getOrDefault(Carriageway.NORTH, 0) > 30)
        assertTrue(counts.getOrDefault(Carriageway.SOUTH, 0) > 30)
    }

    @Test
    fun heavyVehiclesNeverUseTheRightHandLane() {
        val sim = quietSim(7, TrafficLevel.HEAVY)
        sim.autopilot = true
        run(sim, 240.0) { s ->
            for (c in s.vehicles) {
                if (c.type.rightLaneBanned && c.role == Role.TRAFFIC && c.link is MotorwayLink) {
                    assertFalse("${c.type.label} in lane 4", c.occupies(Road.RIGHT_HAND_LANE))
                }
            }
        }
    }

    @Test
    fun lightTrafficKeepsLeft() {
        val sim = quietSim(3, TrafficLevel.LIGHT)
        sim.autopilot = true
        val counts = IntArray(Road.LANES)
        run(sim, 240.0) { s -> for (c in s.vehicles) if (c.link is MotorwayLink && c.lane >= 0) counts[c.lane]++ }
        assertTrue("lane use ${counts.toList()}", counts[0] > counts[3])
        assertTrue("lane use ${counts.toList()}", counts[1] > counts[3])
    }

    @Test
    fun trafficLeavesAndJoinsAtJunctions() {
        val sim = quietSim(12, TrafficLevel.HEAVY)
        sim.autopilot = true
        val exited = HashSet<Int>()
        val circulated = HashSet<Int>()
        val joined = HashSet<Int>()
        run(sim, 400.0) { s ->
            for (c in s.vehicles) {
                when (c.link.kind) {
                    LinkKind.OFF_SLIP -> exited += c.id
                    LinkKind.RING -> circulated += c.id
                    LinkKind.MOTORWAY -> if (c.id in circulated) joined += c.id
                    else -> Unit
                }
            }
        }
        assertTrue("nobody exited", exited.size > 5)
        assertTrue("nobody went round a roundabout", circulated.size > 5)
        assertTrue("nobody joined the motorway from a slip road", joined.size > 3)
        assertEquals(0, sim.aiInterventions)
    }

    /**
     * A simple test driver: steers the player towards lateral position [target]
     * (lanes on the motorway, metres from the centre line elsewhere).
     */
    private fun steerTowards(sim: Simulation, target: Double) {
        val p = sim.player
        val scale = if (p.link is MotorwayLink) Road.LANE_WIDTH else 1.0
        val error = (target - p.lat) * scale
        val wantedHeading = -(error * 0.05).coerceIn(-0.08, 0.08)
        sim.steering = ((p.relHeading - wantedHeading) * 12).coerceIn(-1.0, 1.0)
    }

    /** Signals left, moves to lane 1 and steers into the exit lane, until the player is on the off-slip. */
    private fun takeTheNextExit(sim: Simulation, step: (Simulation) -> Unit) {
        sim.toggleIndicator(-1)
        var guard = 0
        while (sim.player.link.kind == LinkKind.MOTORWAY && guard++ < 30 * 180) {
            if (sim.player.indicator == 0) sim.toggleIndicator(-1)
            steerTowards(sim, if (Road.isDivergeZone(sim.player.s) && Road.junctionOffset(sim.player.s) > Road.DIVERGE_START + 30) -1.0 else 0.0)
            step(sim)
        }
        assertEquals(LinkKind.OFF_SLIP, sim.player.link.kind)
    }

    @Test
    fun playerCanExitGoRoundTheRoundaboutAndJoinTheOtherCarriageway() {
        val sim = quietSim(5, TrafficLevel.MODERATE)
        // Clear lane 1 ahead so the manually driven approach is safe.
        val start = sim.player.s
        sim.vehicles.removeAll {
            !it.isPlayer && it.link === sim.player.link && (it.occupies(0) || abs(it.s - start) < 40) &&
                it.s > start - 150 && it.s < start + 1600
        }
        takeTheNextExit(sim) { it.update(1 / 30.0) }
        assertFalse(sim.crashed)
        assertTrue("booked ${sim.conduct.breaches.map { it.offence }}", sim.conduct.breaches.none { it.offence == Offence.NO_EXIT_SIGNAL })
        sim.autopilot = true // let the autopilot handle the give-way and roundabout
        val seen = HashSet<LinkKind>()
        var guard = 0
        while (guard++ < 30 * 240) {
            sim.update(1 / 30.0)
            seen += sim.player.link.kind
            assertFalse("crashed on ${sim.player.link.label}", sim.crashed)
            val link = sim.player.link
            if (link is MotorwayLink && link.cw == Carriageway.SOUTH && sim.player.lane >= 0) break
        }
        assertTrue("never reached the southbound carriageway, saw $seen", (sim.player.link as? MotorwayLink)?.cw == Carriageway.SOUTH)
        assertTrue(seen.containsAll(listOf(LinkKind.OFF_SLIP, LinkKind.RING, LinkKind.ON_SLIP)))
    }

    @Test
    fun playerSteersRoundTheRoundaboutAndChoosesTheExit() {
        val sim = quietSim(8, TrafficLevel.LIGHT)
        sim.vehicles.retainAll { it.isPlayer }
        takeTheNextExit(sim, ::step)
        // Slow down on the slip road and drive onto the roundabout.
        var guard = 0
        while (sim.player.link.kind != LinkKind.RING && guard++ < 30 * 240) {
            sim.brake = sim.player.v > 11.0
            steerTowards(sim, 0.0)
            step(sim)
        }
        sim.brake = false
        assertEquals(LinkKind.RING, sim.player.link.kind)
        // Keep to the inside for a while: still circulating.
        repeat(30 * 4) {
            steerTowards(sim, 1.5)
            step(sim)
        }
        assertEquals(LinkKind.RING, sim.player.link.kind)
        // Signal left and move to the outside to leave at the next exit.
        sim.toggleIndicator(-1)
        var exit: RingPort? = null
        guard = 0
        while (sim.player.link.kind == LinkKind.RING && guard++ < 30 * 60) {
            exit = sim.player.ringExit
            steerTowards(sim, -2.4)
            step(sim)
        }
        assertNotNull(exit)
        assertTrue("left by ${sim.player.link.label}, expected ${exit!!.name}, lat ${sim.player.lat}", sim.player.link === exit.link)
        assertFalse(sim.crashed)
        assertEquals("indicator not cancelled", 0, sim.player.indicator)
    }

    private fun step(sim: Simulation) {
        sim.vehicles.retainAll { it.isPlayer }
        sim.update(1 / 30.0)
    }

    @Test
    fun policeAndRecoveryClearABreakdown() {
        val sim = quietSim(11)
        sim.autopilot = true
        sim.triggerIncident(2)
        val inc = sim.incidents.single()
        assertNotNull("no patrol on scene", inc.police)
        val laneMask = inc.closedLanes
        assertTrue(laneMask != 0)
        var sawRecovery = false
        var sawLoading = false
        var sawCarrying = false
        run(sim, 120.0) { s ->
            if (s.incidents.isNotEmpty()) {
                val i = s.incidents[0]
                if (i.recovery != null) sawRecovery = true
                if (i.stage == Incident.Stage.LOADING) sawLoading = true
            }
            if (s.vehicles.any { it.carrying != null }) sawCarrying = true
        }
        assertTrue("recovery never came", sawRecovery)
        assertTrue("never loaded", sawLoading)
        assertTrue("never carried away", sawCarrying)
        assertTrue("incident not cleared", sim.incidents.isEmpty())
        assertFalse(sim.crashed)
    }

    @Test
    fun crashIsAttendedAndThePlayerCanCarryOn() {
        val sim = quietSim(21)
        sim.vehicles.retainAll { it.isPlayer }
        val p = sim.player
        val link = p.link
        // A lorry closing in fast right behind the player, who brakes hard.
        val lorry = Vehicle(9999, VehicleType.LORRY, link, p.rear - 3.0, p.lane, p.v + 8.0, 1.0, 0.2, false, 0)
        sim.vehicles.add(lorry)
        sim.brake = true
        var guard = 0
        while (!sim.crashed && guard++ < 300) sim.update(1 / 30.0)
        assertTrue(sim.crashed)
        val inc = sim.incidents.single()
        assertTrue(inc.crash)
        assertEquals(2, inc.wrecks.size)
        // Watch the emergency services work: they recover the player's car too.
        var sawPolice = false
        guard = 0
        while (sim.crashed && guard++ < 30 * 400) {
            sim.update(1 / 30.0)
            if (inc.police != null && inc.stage >= Incident.Stage.POLICE_ON_SCENE) sawPolice = true
        }
        assertTrue("police never arrived", sawPolice)
        assertFalse("player never recovered", sim.crashed)
        assertTrue(sim.player.role == Role.TRAFFIC && sim.player.isPlayer)
        assertTrue(sim.vehicles.count { it.isPlayer } == 1)
    }

    @Test
    fun playerCanContinueStraightAfterACrash() {
        val sim = quietSim(22)
        val other = sim.vehicles.first { !it.isPlayer && it.link === sim.player.link && it.lane == sim.player.lane && it.s > sim.player.s }
        sim.vehicles.retainAll { it.isPlayer || it === other }
        other.v = 0.0
        sim.throttle = true
        var guard = 0
        while (!sim.crashed && guard++ < 30 * 120) sim.update(1 / 30.0)
        assertTrue(sim.crashed)
        sim.continueAfterCrash()
        assertFalse(sim.crashed)
        assertTrue(sim.player.role == Role.TRAFFIC)
        run(sim, 5.0)
        assertTrue(sim.incidents.isNotEmpty())
    }

    @Test
    fun gantriesCloseTheLaneUpstreamOfAnIncident() {
        val sim = quietSim(13)
        sim.triggerIncident(1)
        val inc = sim.incidents.single()
        val cw = inc.cw!!
        val g = sim.gantryFor(inc.rearS)
        val sig = sim.signalAt(cw, g)
        assertEquals(50, sig.limitMph)
        assertTrue(sig.closedLanes == inc.closedLanes)
    }

    @Test
    fun speedingPastAGantryTriggersTheCamera() {
        val sim = quietSim(5, TrafficLevel.LIGHT)
        sim.vehicles.retainAll { it.isPlayer }
        sim.player.v = Units.mphToMs(100.0)
        var t = 0.0
        while (t < 60.0 && sim.cameraFlashes == 0) {
            sim.vehicles.retainAll { it.isPlayer }
            sim.update(1.0 / 30.0)
            t += 1.0 / 30.0
        }
        assertTrue(sim.cameraFlashes > 0)
    }

    @Test
    fun playerChangesLaneBySteeringAndTheIndicatorCancels() {
        val sim = quietSim(9)
        sim.vehicles.retainAll { it.isPlayer }
        val start = sim.player.lane
        sim.toggleIndicator(1)
        assertEquals(1, sim.player.indicator)
        run(sim, 2.0) { it.vehicles.retainAll { v -> v.isPlayer } }
        run(sim, 8.0) {
            it.vehicles.retainAll { v -> v.isPlayer }
            steerTowards(it, start + 1.0)
        }
        assertEquals(start + 1, sim.player.lane)
        assertEquals(start + 1.0, sim.player.lat, 0.1)
        assertEquals(0, sim.player.indicator)
        val cw = (sim.player.link as MotorwayLink).cw
        assertEquals(cw.worldX(Road.laneCenter(start + 1.0)), sim.player.pose.x, 0.4)
        assertTrue(abs(sim.player.pose.heading - cw.heading) < 0.02)
        assertTrue(sim.conduct.breaches.isEmpty())
    }

    @Test
    fun changingLaneWithoutSignallingIsNoted() {
        val sim = quietSim(9)
        sim.vehicles.retainAll { it.isPlayer }
        val start = sim.player.lane
        run(sim, 8.0) {
            it.vehicles.retainAll { v -> v.isPlayer }
            steerTowards(it, start + 1.0)
        }
        assertEquals(start + 1, sim.player.lane)
        val b = sim.conduct.breaches.single()
        assertEquals(Offence.NO_SIGNAL, b.offence)
        assertEquals(0, sim.conduct.points)
    }

    @Test
    fun heavyVehicleInTheRightHandLaneGetsPoints() {
        val sim = quietSim(14)
        sim.reset(VehicleType.LORRY)
        assertEquals("HGVs start in lane 1", 0, sim.player.lane)
        sim.vehicles.retainAll { it.isPlayer }
        run(sim, 30.0) {
            it.vehicles.retainAll { v -> v.isPlayer }
            if (it.player.indicator != 1) it.toggleIndicator(1)
            steerTowards(it, Road.RIGHT_HAND_LANE.toDouble())
        }
        assertTrue(sim.conduct.breaches.any { it.offence == Offence.RIGHT_LANE })
        assertTrue(sim.conduct.points >= 3)
    }

    @Test
    fun tailgatingGetsPointsAndTwelvePointsIsABan() {
        val sim = quietSim(15)
        val p = sim.player
        sim.vehicles.retainAll { it.isPlayer }
        val lead = Vehicle(9999, VehicleType.LORRY, p.link, p.s + 20.0 + VehicleType.LORRY.length, p.lane, p.v, 1.0, 0.2, false, 0)
        sim.vehicles.add(lead)
        run(sim, 12.0) {
            it.vehicles.retainAll { v -> v.isPlayer || v === lead }
            p.v = lead.v // sit 20 m behind at motorway speed: well under two seconds
            steerTowards(it, p.lane.toDouble())
        }
        assertTrue(sim.conduct.breaches.any { it.offence == Offence.TAILGATING })
        assertFalse(sim.conduct.banned)
        sim.conduct.book(Offence.SPEEDING, 0.0)
        sim.conduct.book(Offence.UNDERTAKING, 0.0)
        sim.conduct.book(Offence.RED_X, 0.0)
        assertTrue(sim.conduct.points >= 12)
        assertTrue(sim.conduct.banned)
        sim.reset()
        assertEquals(0, sim.conduct.points)
    }

    @Test
    fun aiTrafficSignalsBeforeChangingLane() {
        val sim = quietSim(16, TrafficLevel.HEAVY)
        sim.autopilot = true
        val prevLane = HashMap<Int, Int>()
        val prevLink = HashMap<Int, Link>()
        val signalOn = HashMap<Int, Pair<Int, Double>>()
        var changes = 0
        var unsignalled = 0
        run(sim, 240.0) { s ->
            for (c in s.vehicles) {
                if (c.isPlayer || c.role != Role.TRAFFIC) continue
                val link = c.link
                val before = prevLane[c.id]
                if (link is MotorwayLink && prevLink[c.id] === link && before != null && before != c.lane) {
                    changes++
                    val dir = if (c.lane > before) 1 else -1
                    val sig = signalOn[c.id]
                    if (sig == null || sig.first != dir || s.time - sig.second < 0.6) unsignalled++
                }
                prevLane[c.id] = c.lane
                prevLink[c.id] = link
                val cur = signalOn[c.id]
                if (c.indicator == 0) signalOn.remove(c.id)
                else if (cur == null || cur.first != c.indicator) signalOn[c.id] = c.indicator to s.time
            }
        }
        assertTrue("too few lane changes: $changes", changes > 100)
        assertEquals("unsignalled lane changes out of $changes", 0, unsignalled)
    }
}
