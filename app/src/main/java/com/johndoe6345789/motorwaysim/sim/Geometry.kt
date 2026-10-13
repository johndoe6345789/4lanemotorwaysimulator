package com.johndoe6345789.motorwaysim.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A position and direction in the world. x is east, y is north and z is up (metres).
 * [heading] is the direction of travel in radians measured from east (π/2 = north).
 */
class Pose {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var heading = 0.0

    fun set(o: Pose) {
        x = o.x; y = o.y; z = o.z; heading = o.heading
    }
}

/** Wraps an angle into (-π, π]. */
fun wrapAngle(a: Double): Double {
    var r = a % (2 * PI)
    if (r <= -PI) r += 2 * PI
    if (r > PI) r -= 2 * PI
    return r
}

/**
 * A road centreline: a densely sampled 3D polyline parametrised by arc length.
 * Positions before the start or after the end are extrapolated in a straight line.
 */
class Path(private val xs: DoubleArray, private val ys: DoubleArray, zFn: (Double) -> Double) {
    private val n = xs.size
    private val cum = DoubleArray(n)
    private val zs: DoubleArray
    private val headings: DoubleArray

    /** Comfortable speed at each sample, accounting for curves further ahead. */
    private val advisory: DoubleArray

    init {
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val len = cum[n - 1]
        zs = DoubleArray(n) { zFn(if (len > 0) cum[it] / len else 0.0) }
        // Tangent at each sample: average of the adjoining segments for smooth turning.
        val seg = DoubleArray(n - 1) { atan2(ys[it + 1] - ys[it], xs[it + 1] - xs[it]) }
        headings = DoubleArray(n) {
            when (it) {
                0 -> seg[0]
                n - 1 -> seg[n - 2]
                else -> seg[it - 1] + wrapAngle(seg[it] - seg[it - 1]) / 2
            }
        }
        // Curve speed: v = sqrt(a_lat / curvature), measured over a few metres.
        val curve = DoubleArray(n) {
            val a = max(0, it - 4)
            val b = min(n - 1, it + 4)
            val ds = cum[b] - cum[a]
            val k = if (ds > 0) abs(wrapAngle(headings[b] - headings[a])) / ds else 0.0
            if (k < 1e-5) MAX_ADVISORY else min(MAX_ADVISORY, sqrt(LATERAL_ACCEL / k))
        }
        // Anticipate: the speed now from which a curve ahead can be reached by gentle braking.
        advisory = DoubleArray(n) { i ->
            var best = curve[i]
            var j = i + 1
            while (j < n && cum[j] - cum[i] < 150) {
                best = min(best, sqrt(curve[j] * curve[j] + 2 * 1.5 * (cum[j] - cum[i])))
                j++
            }
            best
        }
    }

    val length get() = cum[n - 1]

    private fun index(s: Double): Int {
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }

    /** Pose at distance [s] along the path, offset [lateral] metres to the right. */
    fun pose(s: Double, out: Pose, lateral: Double = 0.0) {
        when {
            s <= 0.0 -> extrapolate(0, s, out)
            s >= length -> extrapolate(n - 1, s - length, out)
            else -> {
                val i = index(s)
                val seg = cum[i + 1] - cum[i]
                val f = if (seg > 0) (s - cum[i]) / seg else 0.0
                out.x = xs[i] + (xs[i + 1] - xs[i]) * f
                out.y = ys[i] + (ys[i + 1] - ys[i]) * f
                out.z = zs[i] + (zs[i + 1] - zs[i]) * f
                out.heading = headings[i] + wrapAngle(headings[i + 1] - headings[i]) * f
            }
        }
        if (lateral != 0.0) {
            out.x += lateral * sin(out.heading)
            out.y -= lateral * cos(out.heading)
        }
    }

    private fun extrapolate(i: Int, d: Double, out: Pose) {
        val h = headings[i]
        out.x = xs[i] + cos(h) * d
        out.y = ys[i] + sin(h) * d
        out.z = zs[i]
        out.heading = h
    }

    /** Comfortable speed (m/s) at [s] given the bends ahead. */
    fun advisorySpeed(s: Double): Double = advisory[index(s.coerceIn(0.0, length))]

    /** The same path rotated by 180° about (cx, cy). */
    fun rotated180(cx: Double, cy: Double, zFn: (Double) -> Double): Path =
        Path(DoubleArray(n) { 2 * cx - xs[it] }, DoubleArray(n) { 2 * cy - ys[it] }, zFn)

    companion object {
        const val LATERAL_ACCEL = 2.6
        const val MAX_ADVISORY = 40.0

        /** A cubic Bézier curve from (x0, y0) heading [h0] to (x1, y1) heading [h1]. */
        fun bezier(
            x0: Double, y0: Double, h0: Double, x1: Double, y1: Double, h1: Double,
            zFn: (Double) -> Double, handle: Double = hypot(x1 - x0, y1 - y0) / 3, handleEnd: Double = handle,
        ): Path {
            val c1x = x0 + cos(h0) * handle
            val c1y = y0 + sin(h0) * handle
            val c2x = x1 - cos(h1) * handleEnd
            val c2y = y1 - sin(h1) * handleEnd
            val count = max(8, ceil(hypot(x1 - x0, y1 - y0) / 0.75).toInt() + 1)
            val xs = DoubleArray(count)
            val ys = DoubleArray(count)
            for (i in 0 until count) {
                val t = i.toDouble() / (count - 1)
                val u = 1 - t
                val a = u * u * u
                val b = 3 * u * u * t
                val c = 3 * u * t * t
                val d = t * t * t
                xs[i] = a * x0 + b * c1x + c * c2x + d * x1
                ys[i] = a * y0 + b * c1y + c * c2y + d * y1
            }
            return Path(xs, ys, zFn)
        }

        /** A circular arc from angle [a0] sweeping [sweep] radians (negative = clockwise). */
        fun arc(cx: Double, cy: Double, r: Double, a0: Double, sweep: Double, z: Double): Path {
            val count = max(8, ceil(abs(sweep) * r / 0.75).toInt() + 1)
            val xs = DoubleArray(count) { cx + r * cos(a0 + sweep * it / (count - 1)) }
            val ys = DoubleArray(count) { cy + r * sin(a0 + sweep * it / (count - 1)) }
            return Path(xs, ys) { z }
        }

        /** Elevation rising smoothly from [z0] to [z1] between fractions [u0] and [u1] of the length. */
        fun ramp(z0: Double, z1: Double, u0: Double, u1: Double): (Double) -> Double = { u ->
            val t = ((u - u0) / (u1 - u0)).coerceIn(0.0, 1.0)
            z0 + (z1 - z0) * t * t * (3 - 2 * t)
        }
    }
}
