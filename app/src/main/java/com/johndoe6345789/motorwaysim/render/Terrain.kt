package com.johndoe6345789.motorwaysim.render

import com.johndoe6345789.motorwaysim.sim.Road
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rolling countryside either side of the motorway. Flat along the motorway corridor, around
 * junctions (slip roads and local roads) and at the overbridges; hills rise further out.
 */
object Terrain {
    /** Overbridges carry a farm road over the motorway halfway between junctions. */
    fun bridgeY(y: Double): Double = Road.junctionY(Road.junctionNearest(y - Road.JUNCTION_SPACING / 2)) + Road.JUNCTION_SPACING / 2

    fun height(x: Double, y: Double): Double {
        val ax = abs(x)
        var w = smooth(55.0, 150.0, ax) * (1 - smooth(520.0, 640.0, ax))
        if (w <= 0) return 0.0
        val dj = abs(y - Road.junctionY(Road.junctionNearest(y)))
        if (dj < 1100 && ax < 900) w *= max(smooth(1000.0, 1100.0, dj), max(smooth(60.0, 200.0, dj) * smooth(140.0, 260.0, ax), smooth(780.0, 900.0, ax)))
        val db = abs(y - bridgeY(y))
        if (ax < 260) w *= smooth(20.0, 70.0, db)
        if (w <= 0) return 0.0
        val h = 9.0 + 7.0 * sin(x / 121.0 + 0.7) * cos(y / 163.0) + 5.0 * sin(y / 71.0 + x / 213.0) + 4.0 * cos(x / 57.0 - y / 97.0)
        return w * max(0.0, h)
    }

    private fun smooth(e0: Double, e1: Double, v: Double): Double {
        val t = min(1.0, max(0.0, (v - e0) / (e1 - e0)))
        return t * t * (3 - 2 * t)
    }
}
