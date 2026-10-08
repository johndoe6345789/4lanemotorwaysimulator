package com.johndoe6345789.motorwaysim.ui

import com.johndoe6345789.motorwaysim.sim.Road
import kotlin.math.min

/** Maps road coordinates (metres) to screen pixels for a top-down view that follows the player. */
class Camera {
    var width = 0f
        private set
    var height = 0f
        private set

    /** Pixels per metre. */
    var ppm = 1f
        private set

    /** Lateral road coordinate shown at the horizontal centre of the screen. */
    var centerX = 0.0
        private set

    /** Screen y of the followed position. */
    var focusY = 0f
        private set
    var focusS = 0.0
        private set

    fun layout(w: Float, h: Float, zoomIndex: Int, followS: Double) {
        width = w
        height = h
        val fit = if (h >= w) w / 26f else min(w / 26f, h / 45f)
        ppm = fit * ZOOMS[zoomIndex]
        val visibleWidth = w / ppm
        centerX = (-3.0 + visibleWidth / 2).coerceIn(Road.WIDTH / 2, Scene.CR_CENTRE)
        focusY = h * 0.70f
        focusS = followS
    }

    fun sx(x: Double): Float = (width / 2 + (x - centerX) * ppm).toFloat()
    fun sy(s: Double): Float = (focusY - (s - focusS) * ppm).toFloat()

    /** Road position at the top edge of the screen. */
    val sTop get() = focusS + focusY / ppm

    /** Road position at the bottom edge of the screen. */
    val sBottom get() = focusS - (height - focusY) / ppm

    val xLeft get() = centerX - width / 2 / ppm
    val xRight get() = centerX + width / 2 / ppm

    companion object {
        val ZOOMS = floatArrayOf(1f, 0.62f, 0.4f)
        val ZOOM_NAMES = arrayOf("Near", "Mid", "Far")
    }
}

/** Lateral layout of the whole dual carriageway (metres from the outer edge of our hard shoulder). */
object Scene {
    const val CR_WIDTH = 3.0
    const val CR_CENTRE = Road.WIDTH + CR_WIDTH / 2
    const val OPP_LEFT = Road.WIDTH + CR_WIDTH
    const val OPP_RIGHT = OPP_LEFT + Road.WIDTH

    /** Centre of lane [lane] (0 = nearside) on the opposite carriageway. */
    fun oppositeLaneCenter(lane: Int): Double = OPP_RIGHT - Road.laneCenter(lane.toDouble())
}
