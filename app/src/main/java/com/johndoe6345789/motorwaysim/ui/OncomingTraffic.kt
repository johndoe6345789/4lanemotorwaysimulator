package com.johndoe6345789.motorwaysim.ui

import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.Units
import com.johndoe6345789.motorwaysim.sim.VehicleType
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Scenery traffic on the opposite carriageway. Every vehicle in a lane moves at
 * that lane's speed, so they never interact and need no driver model.
 */
class OncomingTraffic {
    class Car(val lane: Int, var s: Double, val type: VehicleType, val color: Int, val variant: Int) {
        /** Oncoming vehicles face down the screen: [s] is the front, the rear is further up the road. */
        val rearS get() = s + type.length
    }

    val cars = ArrayList<Car>()
    private val rng = Random(17)
    private var variant = 0

    fun clear() = cars.clear()

    fun update(dt: Double, sim: Simulation, camera: Camera) {
        if (camera.ppm <= 0f) return
        for (c in cars) c.s -= LANE_SPEEDS[c.lane] * dt
        val bottom = camera.sBottom - 40
        cars.removeAll { it.rearS < bottom || it.s > camera.sTop + 400 }

        val perKm = sim.trafficLevel.vehiclesPerKmPerLane
        for (lane in 0 until Road.LANES) {
            var top = bottom
            for (c in cars) if (c.lane == lane && c.rearS > top) top = c.rearS
            while (top < camera.sTop + 60) {
                val meanGap = max(15.0, 1000.0 / (perKm * Simulation.LANE_SHARE[lane]) - 5.0)
                val gap = 10.0 - meanGap * ln(1.0 - rng.nextDouble()) * 0.9
                val type = pickType(lane)
                val car = Car(lane, top + gap, type, pickColor(type), variant++)
                cars.add(car)
                top = car.rearS
            }
        }
    }

    private fun pickType(lane: Int): VehicleType {
        val r = rng.nextDouble()
        val lorryShare = when (lane) {
            0 -> 0.3
            1 -> 0.12
            else -> 0.0
        }
        return when {
            r < lorryShare -> VehicleType.LORRY
            lane <= 1 && r < lorryShare + 0.03 -> VehicleType.COACH
            r < 0.85 -> VehicleType.CAR
            else -> VehicleType.VAN
        }
    }

    private fun pickColor(type: VehicleType): Int = when (type) {
        VehicleType.LORRY, VehicleType.COACH -> Simulation.HEAVY_COLORS.random(rng)
        VehicleType.VAN -> Simulation.VAN_COLORS.random(rng)
        else -> Simulation.CAR_COLORS.random(rng)
    }

    companion object {
        /** Speed of each opposite lane, nearside → offside (m/s). */
        val LANE_SPEEDS = doubleArrayOf(55.0, 63.0, 68.0, 72.0).map { Units.mphToMs(it) }.toDoubleArray()
    }
}
