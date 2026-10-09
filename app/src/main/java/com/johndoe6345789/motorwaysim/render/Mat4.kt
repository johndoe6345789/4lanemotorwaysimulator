package com.johndoe6345789.motorwaysim.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 4×4 matrices stored column-major in float arrays, as OpenGL expects. */
object Mat4 {
    fun identity(m: FloatArray) {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun perspective(m: FloatArray, fovY: Double, aspect: Double, near: Double, far: Double) {
        val f = 1.0 / tan(fovY / 2)
        m.fill(0f)
        m[0] = (f / aspect).toFloat()
        m[5] = f.toFloat()
        m[10] = ((far + near) / (near - far)).toFloat()
        m[11] = -1f
        m[14] = (2 * far * near / (near - far)).toFloat()
    }

    fun lookAt(
        m: FloatArray, ex: Double, ey: Double, ez: Double, cx: Double, cy: Double, cz: Double,
        ux: Double, uy: Double, uz: Double,
    ) {
        var fx = cx - ex
        var fy = cy - ey
        var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= sl; sy /= sl; sz /= sl
        val vx = sy * fz - sz * fy
        val vy = sz * fx - sx * fz
        val vz = sx * fy - sy * fx
        m[0] = sx.toFloat(); m[4] = sy.toFloat(); m[8] = sz.toFloat()
        m[1] = vx.toFloat(); m[5] = vy.toFloat(); m[9] = vz.toFloat()
        m[2] = (-fx).toFloat(); m[6] = (-fy).toFloat(); m[10] = (-fz).toFloat()
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = (-(sx * ex + sy * ey + sz * ez)).toFloat()
        m[13] = (-(vx * ex + vy * ey + vz * ez)).toFloat()
        m[14] = (fx * ex + fy * ey + fz * ez).toFloat()
        m[15] = 1f
    }

    /** out = a × b. [out] must not be [a] or [b]. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = s
            }
        }
    }

    /**
     * Model matrix for an object whose local +y axis points along world [heading]
     * (radians from east), tilted nose-up by [pitch], translated to (tx, ty, tz).
     */
    fun model(m: FloatArray, tx: Double, ty: Double, tz: Double, heading: Double, pitch: Double = 0.0) {
        val t = heading - Math.PI / 2
        val c = cos(t)
        val s = sin(t)
        val cp = cos(pitch)
        val sp = sin(pitch)
        m[0] = c.toFloat(); m[1] = s.toFloat(); m[2] = 0f; m[3] = 0f
        m[4] = (-s * cp).toFloat(); m[5] = (c * cp).toFloat(); m[6] = sp.toFloat(); m[7] = 0f
        m[8] = (s * sp).toFloat(); m[9] = (-c * sp).toFloat(); m[10] = cp.toFloat(); m[11] = 0f
        m[12] = tx.toFloat(); m[13] = ty.toFloat(); m[14] = tz.toFloat(); m[15] = 1f
    }

    fun translation(m: FloatArray, tx: Double, ty: Double, tz: Double) {
        identity(m)
        m[12] = tx.toFloat(); m[13] = ty.toFloat(); m[14] = tz.toFloat()
    }
}
