package com.johndoe6345789.motorwaysim.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

class SimulationTest {
    private fun run(sim: Simulation, seconds: Double, check: (Simulation) -> Unit = {}) {
        var t = 0.0
        while (t < seconds) {
            sim.update(1.0 / 30.0)
            check(sim)
            t += 1.0 / 30.0
        }
    }

    @Test
    fun autopilotDrivesSafelyAtEveryTrafficLevel() {
        for (level in TrafficLevel.entries) {
            val sim = Simulation(seed = 42)
            sim.trafficLevel = level
            sim.incidentsEnabled = false
            sim.reset()
            sim.autopilot = true
            run(sim, 300.0)
            assertFalse("crashed in $level traffic", sim.crashed)
            assertEquals("AI vehicles overlapped in $level traffic", 0, sim.aiInterventions)
            assertTrue("no traffic in $level", sim.vehicles.size > 20)
            assertTrue("made no progress in $level", sim.distanceTravelled > 1000.0)
        }
    }

    @Test
    fun lorriesNeverUseTheOutsideLane() {
        val sim = Simulation(seed = 7)
        sim.trafficLevel = TrafficLevel.HEAVY
        sim.reset()
        sim.autopilot = true
        run(sim, 300.0) { s ->
            for (c in s.vehicles) {
                if (c.type == VehicleType.LORRY) {
                    assertFalse("lorry in lane 4", c.occupies(Road.LORRY_BANNED_LANE))
                }
            }
        }
    }

    @Test
    fun lightTrafficKeepsLeft() {
        val sim = Simulation(seed = 3)
        sim.trafficLevel = TrafficLevel.LIGHT
        sim.incidentsEnabled = false
        sim.reset()
        sim.autopilot = true
        val counts = IntArray(Road.LANES)
        run(sim, 240.0) { s -> for (c in s.vehicles) counts[c.lane]++ }
        assertTrue("lane use ${counts.toList()}", counts[0] > counts[3])
        assertTrue("lane use ${counts.toList()}", counts[1] > counts[3])
    }

    @Test
    fun trafficFlowsPastAnIncident() {
        val sim = Simulation(seed = 11)
        sim.trafficLevel = TrafficLevel.MODERATE
        sim.incidentsEnabled = false
        sim.reset()
        sim.autopilot = true
        sim.triggerIncident()
        val incident = sim.incidents.single()
        val closedSomewhere = (0..3).any {
            sim.signalAt(Road.gantryIndexAt(incident.s) - it.toLong()).closedLane == incident.lane
        }
        assertTrue("no red X shown upstream of the incident", closedSomewhere)
        val incidentPos = incident.s
        run(sim, 240.0)
        assertFalse(sim.crashed)
        assertTrue("player never got past the incident", sim.player.s > incidentPos + 200)
    }

    @Test
    fun speedingPastAGantryTriggersTheCamera() {
        val sim = Simulation(seed = 5)
        sim.trafficLevel = TrafficLevel.LIGHT
        sim.incidentsEnabled = false
        sim.reset()
        // Clear the road so nothing gets in the way.
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
    fun playerCanChangeLanes() {
        val sim = Simulation(seed = 9)
        sim.reset()
        val start = sim.player.lane
        sim.steer(1)
        assertEquals(start + 1, sim.player.lane)
        assertTrue(sim.player.isChangingLane)
        sim.vehicles.retainAll { it.isPlayer }
        run(sim, 2.0) { it.vehicles.retainAll { v -> v.isPlayer } }
        assertFalse(sim.player.isChangingLane)
        assertEquals(Road.laneCenter((start + 1).toDouble()), sim.player.x, 1e-9)
    }

    @Test
    fun brakingHardInFrontOfTrafficCanCauseACrash() {
        val sim = Simulation(seed = 21)
        sim.reset()
        sim.vehicles.retainAll { it.isPlayer }
        val p = sim.player
        // A lorry closing in fast right behind the player.
        sim.vehicles.add(
            Vehicle(999, VehicleType.LORRY, p.rear - 3.0, p.lane, p.v + 8.0, 1.0, 0.2, false, 0)
        )
        sim.brake = true
        run(sim, 10.0)
        assertTrue(sim.crashed)
    }
}
