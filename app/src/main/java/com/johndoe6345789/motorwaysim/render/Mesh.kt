package com.johndoe6345789.motorwaysim.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle soup in the lit vertex format: position (3), normal (3) and colour (4),
 * where the colour's alpha is an emissive factor (1 = unaffected by lighting).
 */
class Mesh(val data: FloatArray) {
    val vertexCount = data.size / STRIDE
    val id = nextId++

    /** GL buffer name, owned by the GL backend (0 = not uploaded). */
    var buffer = 0

    companion object {
        const val STRIDE = 10
        private var nextId = 1
    }
}

/** Accumulates triangles for a [Mesh]. Quads are given counter-clockwise when seen from the front. */
class MeshBuilder(capacity: Int = 4096) {
    private var data = FloatArray(capacity * Mesh.STRIDE)
    private var size = 0
    private var r = 1f
    private var g = 1f
    private var b = 1f
    private var e = 0f

    val isEmpty get() = size == 0

    fun color(argb: Int, emissive: Float = 0f): MeshBuilder {
        r = ((argb shr 16) and 0xFF) / 255f
        g = ((argb shr 8) and 0xFF) / 255f
        b = (argb and 0xFF) / 255f
        e = emissive
        return this
    }

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    private fun vertex(x: Double, y: Double, z: Double, nx: Float, ny: Float, nz: Float) {
        ensure(Mesh.STRIDE)
        val d = data
        var i = size
        d[i++] = x.toFloat(); d[i++] = y.toFloat(); d[i++] = z.toFloat()
        d[i++] = nx; d[i++] = ny; d[i++] = nz
        d[i++] = r; d[i++] = g; d[i++] = b; d[i++] = e
        size = i
    }

    /** A flat quad through four corners in order; its normal follows the right-hand rule. */
    fun quad(
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double,
    ) {
        val ax = x1 - x0; val ay = y1 - y0; val az = z1 - z0
        val bx = x3 - x0; val by = y3 - y0; val bz = z3 - z0
        var nx = ay * bz - az * by
        var ny = az * bx - ax * bz
        var nz = ax * by - ay * bx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 0) { nx /= l; ny /= l; nz /= l } else nz = 1.0
        val fx = nx.toFloat(); val fy = ny.toFloat(); val fz = nz.toFloat()
        vertex(x0, y0, z0, fx, fy, fz); vertex(x1, y1, z1, fx, fy, fz); vertex(x2, y2, z2, fx, fy, fz)
        vertex(x0, y0, z0, fx, fy, fz); vertex(x2, y2, z2, fx, fy, fz); vertex(x3, y3, z3, fx, fy, fz)
    }

    /** Horizontal rectangle facing up. */
    fun flat(x0: Double, y0: Double, x1: Double, y1: Double, z: Double) =
        quad(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z)

    /** Axis-aligned box spanning the given ranges. */
    fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, bottom: Boolean = false) {
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1) // top
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1) // -y
        quad(x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1) // +y
        quad(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1) // -x
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1) // +x
        if (bottom) quad(x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0)
    }

    /** A box centred on (cx, cy) with its length along [heading] (radians from east). */
    fun orientedBox(cx: Double, cy: Double, z0: Double, z1: Double, length: Double, width: Double, heading: Double) {
        val fx = cos(heading) * length / 2
        val fy = sin(heading) * length / 2
        val rx = sin(heading) * width / 2
        val ry = -cos(heading) * width / 2
        // Corners: rear-left, rear-right, front-right, front-left.
        val ax = cx - fx - rx; val ay = cy - fy - ry
        val bx = cx - fx + rx; val by = cy - fy + ry
        val ccx = cx + fx + rx; val ccy = cy + fy + ry
        val dx = cx + fx - rx; val dy = cy + fy - ry
        quad(ax, ay, z1, bx, by, z1, ccx, ccy, z1, dx, dy, z1) // top
        quad(ax, ay, z0, bx, by, z0, bx, by, z1, ax, ay, z1) // rear
        quad(ccx, ccy, z0, dx, dy, z0, dx, dy, z1, ccx, ccy, z1) // front
        quad(dx, dy, z0, ax, ay, z0, ax, ay, z1, dx, dy, z1) // left
        quad(bx, by, z0, ccx, ccy, z0, ccx, ccy, z1, bx, by, z1) // right
    }

    /** Vertical cylinder. */
    fun cylinder(cx: Double, cy: Double, z0: Double, z1: Double, radius: Double, segments: Int = 8) {
        for (i in 0 until segments) {
            val a0 = 2 * Math.PI * i / segments
            val a1 = 2 * Math.PI * (i + 1) / segments
            val x0 = cx + cos(a0) * radius; val y0 = cy + sin(a0) * radius
            val x1 = cx + cos(a1) * radius; val y1 = cy + sin(a1) * radius
            quad(x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1)
            quad(cx, cy, z1, x0, y0, z1, x1, y1, z1, x1, y1, z1)
        }
    }

    /** A wheel: cylinder along the x axis. */
    fun wheel(cx: Double, cy: Double, cz: Double, radius: Double, halfWidth: Double, segments: Int = 10) {
        val xa = cx - halfWidth
        val xb = cx + halfWidth
        for (i in 0 until segments) {
            val a0 = 2 * Math.PI * i / segments
            val a1 = 2 * Math.PI * (i + 1) / segments
            val y0 = cy + cos(a0) * radius; val z0 = cz + sin(a0) * radius
            val y1 = cy + cos(a1) * radius; val z1 = cz + sin(a1) * radius
            quad(xa, y0, z0, xa, y1, z1, xb, y1, z1, xb, y0, z0)
            quad(xb, cy, cz, xb, y0, z0, xb, y1, z1, xb, y1, z1)
            quad(xa, cy, cz, xa, y1, z1, xa, y0, z0, xa, y0, z0)
        }
    }

    /** A low-poly tree crown: an octahedron-like double pyramid. */
    fun crown(cx: Double, cy: Double, z0: Double, z1: Double, radius: Double, segments: Int = 6) {
        val zm = z0 + (z1 - z0) * 0.4
        for (i in 0 until segments) {
            val a0 = 2 * Math.PI * i / segments
            val a1 = 2 * Math.PI * (i + 1) / segments
            val x0 = cx + cos(a0) * radius; val y0 = cy + sin(a0) * radius
            val x1 = cx + cos(a1) * radius; val y1 = cy + sin(a1) * radius
            quad(x0, y0, zm, x1, y1, zm, cx, cy, z1, cx, cy, z1)
            quad(x1, y1, zm, x0, y0, zm, cx, cy, z0, cx, cy, z0)
        }
    }

    fun build(): Mesh = Mesh(data.copyOf(size))
}
