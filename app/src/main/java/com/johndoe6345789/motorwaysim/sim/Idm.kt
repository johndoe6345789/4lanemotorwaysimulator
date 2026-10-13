package com.johndoe6345789.motorwaysim.sim

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The Intelligent Driver Model (Treiber, Hennecke & Helbing, 2000): a
 * collision-free car-following model giving a vehicle's acceleration from its
 * speed, desired speed, and the gap and approach rate to the vehicle ahead.
 */
object Idm {
    /** Hardest braking any vehicle can achieve (m/s²). */
    const val MAX_BRAKING = 9.0

    /** Leaders further away than this have no meaningful influence. */
    const val LOOKAHEAD = 300.0

    /** Free-road acceleration towards the desired speed [v0]. */
    fun freeAccel(type: VehicleType, v: Double, v0: Double): Double {
        if (v0 <= 0.0) return -type.comfortDecel
        val r = v / v0
        val a = type.maxAccel * (1 - r * r * r * r)
        // Slow down comfortably (not abruptly) when above the desired speed, e.g. after a limit drop.
        return max(a, -type.comfortDecel)
    }

    /**
     * Acceleration of a vehicle of [type] driving at [v] towards [v0], following a
     * leader [gap] metres ahead (bumper to bumper) that drives at [leaderV].
     * Pass [gap] = [Double.POSITIVE_INFINITY] for a free road.
     */
    fun accel(
        type: VehicleType, v: Double, v0: Double, gap: Double, leaderV: Double,
        timeHeadway: Double = type.timeHeadway,
    ): Double {
        val free = freeAccel(type, v, v0)
        if (gap >= LOOKAHEAD) return free
        val dv = v - leaderV
        val sStar = type.minGap +
            max(0.0, v * timeHeadway + v * dv / (2 * sqrt(type.maxAccel * type.comfortDecel)))
        val g = max(gap, 0.01)
        val ratio = sStar / g
        val a = free - type.maxAccel * ratio * ratio
        return a.coerceIn(-MAX_BRAKING, type.maxAccel)
    }
}
