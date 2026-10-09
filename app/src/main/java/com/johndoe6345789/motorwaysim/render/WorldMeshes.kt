package com.johndoe6345789.motorwaysim.render

import com.johndoe6345789.motorwaysim.sim.Carriageway
import com.johndoe6345789.motorwaysim.sim.Junction
import com.johndoe6345789.motorwaysim.sim.LinkKind
import com.johndoe6345789.motorwaysim.sim.PathLink
import com.johndoe6345789.motorwaysim.sim.Pose
import com.johndoe6345789.motorwaysim.sim.Road
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A sign face: a vertical rectangle in world space showing an atlas region. */
class SignFace(
    /** Bottom-left and bottom-right corners as seen by the viewer. */
    val x0: Double, val y0: Double, val x1: Double, val y1: Double,
    val zBottom: Double, val zTop: Double,
    val key: String,
)

/** Static geometry for a stretch of road, positioned at [originY] (world y of its local origin). */
class WorldPiece(val mesh: Mesh, val originY: Double, val signs: List<SignFace>, val terrain: Mesh? = null)

/**
 * Builds the static scenery: motorway chunks of [CHUNK] metres and whole junctions.
 * Vertices are relative to each piece's origin so that floats stay precise far from (0, 0).
 */
object WorldMeshes {
    const val CHUNK = 100.0

    private val ASPHALT = 0xFF3D4045.toInt()
    private val SHOULDER = 0xFF4A4D52.toInt()
    private val PAINT = 0xFFE6E6E0.toInt()
    private val CONCRETE = 0xFF9C9E9C.toInt()
    private val BARRIER = 0xFFC2C4C2.toInt()
    private val DECK = 0xFF8A8C8E.toInt()
    private val STEEL = 0xFF6A7078.toInt()
    private val HOUSING = 0xFF2C2F33.toInt()
    private val STUD_RED = 0xFFFF3030.toInt()
    private val STUD_WHITE = 0xFFFFFFFF.toInt()
    private val STUD_AMBER = 0xFFFFB000.toInt()
    private val STUD_GREEN = 0xFF30E060.toInt()
    private val HEDGE = 0xFF3D6B32.toInt()
    private val TRUNK = 0xFF5A4630.toInt()
    private val TREE_A = 0xFF35662E.toInt()
    private val TREE_B = 0xFF4A7A34.toInt()
    private val TREE_C = 0xFF2C5426.toInt()
    private val POST = 0xFF7D8288.toInt()
    private val LAMP = 0xFFF5F0DC.toInt()
    val GRASS = 0xFF54803C.toInt()

    /**
     * The ground plane, drawn centred under the camera. It is tiled (huge triangles lose depth
     * precision when clipped) and sits a little below road level so roads never z-fight with it.
     */
    val ground: Mesh by lazy {
        val b = MeshBuilder(32 * 32 * 6).color(GRASS)
        val tile = 250.0
        for (i in -16 until 16) for (j in -16 until 16) b.flat(i * tile, j * tile, (i + 1) * tile, (j + 1) * tile, -0.15)
        b.build()
    }

    // ------------------------------------------------------------------ motorway

    fun chunk(index: Int): WorldPiece {
        val y0 = index * CHUNK
        val b = MeshBuilder(8192)
        val signs = ArrayList<SignFace>()
        for (cw in Carriageway.entries) carriageway(b, signs, cw, y0)
        centralReservation(b, y0)
        roadside(b, y0)
        val by = Terrain.bridgeY(y0 + CHUNK / 2)
        if (by >= y0 && by < y0 + CHUNK) overbridge(b, by - y0)
        return WorldPiece(b.build(), y0, signs, terrain(y0))
    }

    /** Rolling fields either side of the motorway for one chunk. */
    private fun terrain(y0: Double): Mesh {
        val b = MeshBuilder(4096).color(GRASS)
        val step = 20.0
        for (side in doubleArrayOf(-1.0, 1.0)) {
            var x = 40.0
            while (x < 620.0) {
                var y = 0.0
                while (y < CHUNK - 1e-6) {
                    val xa = side * x
                    val xb = side * (x + step)
                    val h00 = Terrain.height(xa, y0 + y) - 0.1
                    val h10 = Terrain.height(xb, y0 + y) - 0.1
                    val h11 = Terrain.height(xb, y0 + y + step) - 0.1
                    val h01 = Terrain.height(xa, y0 + y + step) - 0.1
                    if (side > 0) b.quad(xa, y, h00, xb, y, h10, xb, y + step, h11, xa, y + step, h01)
                    else b.quad(xb, y, h10, xa, y, h00, xa, y + step, h01, xb, y + step, h11)
                    y += step
                }
                x += step
            }
        }
        return b.build()
    }

    /** A farm road bridge over the motorway, with earth approach embankments. */
    private fun overbridge(b: MeshBuilder, y: Double) {
        val half = 46.0
        val deck = 7.2
        val w = 4.5
        // Deck, its edges and underside.
        b.color(ASPHALT, 2f)
        b.flat(-half, y - w, half, y + w, deck)
        b.color(DECK)
        b.box(-half, half, y - w - 0.4, y - w, deck - 1.2, deck + 1.0)
        b.box(-half, half, y + w, y + w + 0.4, deck - 1.2, deck + 1.0)
        b.quad(-half, y + w, deck - 1.2, half, y + w, deck - 1.2, half, y - w, deck - 1.2, -half, y - w, deck - 1.2)
        b.color(PAINT)
        var dx = -half
        while (dx < half) { b.flat(dx, y - 0.07, dx + 3, y + 0.07, deck + 0.02); dx += 6 }
        // Piers: central reservation and both verges, and abutments.
        b.color(CONCRETE)
        for (px in doubleArrayOf(0.0, -23.6, 23.6)) b.box(px - 0.6, px + 0.6, y - w + 0.5, y + w - 0.5, 0.0, deck - 1.2)
        for (sx in doubleArrayOf(-1.0, 1.0)) b.box(sx * half - 0.8, sx * half + 0.8, y - w - 0.4, y + w + 0.4, 0.0, deck)
        // Embankments: the road slopes down to the fields; earth sides fall away.
        for (sx in doubleArrayOf(-1.0, 1.0)) {
            val n = 12
            for (i in 0 until n) {
                val xa = sx * (half + 130.0 * i / n)
                val xb = sx * (half + 130.0 * (i + 1) / n)
                val za = deck * (1 - i.toDouble() / n).let { it * it * (3 - 2 * it) }
                val zb = deck * (1 - (i + 1).toDouble() / n).let { it * it * (3 - 2 * it) }
                val sa = w + za * 1.6
                val sb = w + zb * 1.6
                val (x0, x1) = if (sx > 0) xa to xb else xb to xa
                val (z0, z1) = if (sx > 0) za to zb else zb to za
                val (s0, s1) = if (sx > 0) sa to sb else sb to sa
                b.color(ASPHALT, 2f)
                b.quad(x0, y - w, z0, x1, y - w, z1, x1, y + w, z1, x0, y + w, z0)
                b.color(GRASS)
                b.quad(x0, y - s0, -0.1, x1, y - s1, -0.1, x1, y - w, z1, x0, y - w, z0)
                b.quad(x1, y + s1, -0.1, x0, y + s0, -0.1, x0, y + w, z0, x1, y + w, z1)
            }
        }
    }

    private class Ctx(val b: MeshBuilder, val cw: Carriageway, val y0: Double) {
        fun x(m: Double) = cw.worldX(m)
        fun y(s: Double) = cw.worldY(s) - y0

        /** Flat rectangle across lateral range [m0, m1] and carriageway range [sa, sb]. */
        fun strip(m0: Double, m1: Double, sa: Double, sb: Double, z: Double) {
            val xa = x(m0); val xb = x(m1); val ya = y(sa); val yb = y(sb)
            b.quad(xa, ya, z, xb, ya, z, xb, yb, z, xa, yb, z)
        }

        fun stud(m: Double, s: Double, color: Int) {
            b.color(color, 1f)
            val xc = x(m); val yc = y(s)
            b.box(xc - 0.08, xc + 0.08, yc - 0.12, yc + 0.12, 0.0, 0.04)
        }
    }

    private fun carriageway(b: MeshBuilder, signs: MutableList<SignFace>, cw: Carriageway, y0: Double) {
        val c = Ctx(b, cw, y0)
        val sA = cw.sOf(y0)
        val sB = cw.sOf(y0 + CHUNK)
        val sLo = min(sA, sB)
        val sHi = max(sA, sB)
        val hs = Road.HARD_SHOULDER
        val lw = Road.LANE_WIDTH
        val offside = hs + Road.LANES * lw
        val step = 10.0
        var s = sLo
        while (s < sHi - 1e-6) {
            val sb = min(sHi, s + step)
            val extra = Road.hasExtraLane((s + sb) / 2)
            b.color(ASPHALT, 2f)
            c.strip(hs, Road.WIDTH, s, sb, 0.0)
            b.color(if (extra) ASPHALT else SHOULDER, 2f)
            c.strip(if (extra) -0.35 else 0.0, hs, s, sb, 0.0)
            b.color(PAINT)
            c.strip(offside - 0.1, offside + 0.1, s, sb, 0.025)
            if (!extra) c.strip(hs - 0.1, hs + 0.1, s, sb, 0.025)
            s = sb
        }
        // Dashed markings: lane lines are 2 m marks with 7 m gaps; the extra lane at
        // junctions is separated by a bold broken line.
        b.color(PAINT)
        for (i in 1 until Road.LANES) dashes(c, hs + i * lw, 0.075, 9.0, 2.0, sLo, sHi) { true }
        dashes(c, hs, 0.15, 4.0, 2.5, sLo, sHi) { Road.hasExtraLane(it) }

        // Road studs ("cat's eyes") every 18 m.
        var k = ceil((sLo - 5.5) / 18).toLong()
        while (k * 18 + 5.5 < sHi) {
            val st = k * 18 + 5.5
            val extra = Road.hasExtraLane(st)
            c.stud(hs - 0.3, st, if (extra) STUD_GREEN else STUD_RED)
            for (i in 1 until Road.LANES) c.stud(hs + i * lw, st, STUD_WHITE)
            c.stud(offside + 0.3, st, STUD_AMBER)
            k++
        }

        // Marker posts every 100 m and driver location signs every 500 m.
        var p = ceil(sLo / 100).toLong()
        while (p * 100 < sHi) {
            val ps = p * 100.0
            if (!Road.hasExtraLane(ps)) {
                b.color(STUD_WHITE)
                val xc = c.x(-0.9); val yc = c.y(ps)
                b.box(xc - 0.07, xc + 0.07, yc - 0.07, yc + 0.07, 0.0, 1.0)
                b.color(0xFF101010.toInt())
                b.box(xc - 0.075, xc + 0.075, yc - 0.075, yc + 0.075, 0.75, 0.9)
                if (Math.floorMod(p, 5L) == 0L && abs(c.y(ps) + y0 - Terrain.bridgeY(c.y(ps) + y0)) > 15) {
                    post(b, c.x(-2.4), c.y(ps), 2.4)
                    signs += face(c, -3.0, -1.8, ps, 1.2, 2.3, if (cw == Carriageway.NORTH) "dls_A" else "dls_B")
                }
            }
            p++
        }

        // Advance direction sign and 300/200/100 yard countdown markers before each exit.
        val first = floor((sLo - Road.DIVERGE_START + 900) / Road.JUNCTION_SPACING).toLong()
        val last = ceil((sHi - Road.DIVERGE_START + 900) / Road.JUNCTION_SPACING).toLong()
        for (j in first..last) {
            val centre = j * Road.JUNCTION_SPACING
            val div = centre + Road.DIVERGE_START
            val number = Road.FIRST_JUNCTION_NUMBER + cw.junctionAt(centre)
            val adv = div - 800
            if (adv in sLo..<sHi) {
                post(b, c.x(-3.2), c.y(adv), 5.6)
                post(b, c.x(-9.8), c.y(adv), 5.6)
                signs += face(c, -10.3, -2.7, adv, 2.2, 5.6, "adv_${number}_${cw.name}")
            }
            for ((n, yards) in listOf(3 to 300, 2 to 200, 1 to 100)) {
                val cs = div - yards * 0.9144
                if (cs in sLo..<sHi) {
                    post(b, c.x(-1.9), c.y(cs), 2.6)
                    signs += face(c, -2.4, -1.4, cs, 0.6, 2.6, "cd$n")
                }
            }
        }

        // Gantries.
        var g = ceil(sLo / Road.GANTRY_SPACING).toLong()
        while (g * Road.GANTRY_SPACING < sHi) {
            if (Road.hasGantry(g)) gantry(c, g * Road.GANTRY_SPACING)
            g++
        }

        // Boundary hedge, except around junctions.
        b.color(HEDGE)
        var hs0 = sLo
        while (hs0 < sHi - 1e-6) {
            val hs1 = min(sHi, hs0 + 20)
            val mid = c.y((hs0 + hs1) / 2) + y0
            if (abs(Road.junctionOffset((hs0 + hs1) / 2)) > 1000 && abs(mid - Terrain.bridgeY(mid)) > 15) {
                val xa = c.x(HEDGE_M - 0.7); val xb = c.x(HEDGE_M + 0.7)
                val ya = c.y(hs0); val yb = c.y(hs1)
                b.box(min(xa, xb), max(xa, xb), min(ya, yb), max(ya, yb), 0.0, 1.5)
            }
            hs0 = hs1
        }
    }

    private const val HEDGE_M = -14.0

    private fun dashes(c: Ctx, m: Double, half: Double, period: Double, length: Double, sLo: Double, sHi: Double, where: (Double) -> Boolean) {
        var k = ceil(sLo / period).toLong()
        while (k * period < sHi) {
            val a = k * period
            if (where(a + length / 2)) c.strip(m - half, m + half, a, a + length, 0.025)
            k++
        }
    }

    private fun post(b: MeshBuilder, x: Double, y: Double, height: Double) {
        b.color(POST)
        b.box(x - 0.08, x + 0.08, y - 0.08, y + 0.08, 0.0, height)
    }

    /** A sign face across lateral range [m0, m1] at carriageway position [s], facing oncoming traffic. */
    private fun face(c: Ctx, m0: Double, m1: Double, s: Double, zBottom: Double, zTop: Double, key: String): SignFace {
        val y = c.y(s) - c.cw.dir * 0.12 + c.y0
        // Seen by approaching drivers, the lower lateral position is on their left.
        return SignFace(c.x(m0), y, c.x(m1), y, zBottom, zTop, key)
    }

    private fun gantry(c: Ctx, s: Double) {
        val b = c.b
        val y = c.y(s)
        val xa = c.x(-1.8)
        val xb = c.x(Road.WIDTH + 1.15)
        b.color(STEEL)
        for (m in doubleArrayOf(-1.5, Road.WIDTH + 0.85)) {
            val xm = c.x(m)
            b.box(xm - 0.3, xm + 0.3, y - 0.3, y + 0.3, 0.0, 7.6)
        }
        b.box(min(xa, xb), max(xa, xb), y - 0.6, y + 0.6, 6.5, 7.6)
        b.color(HOUSING)
        for (l in 0 until Road.LANES) {
            val xm = c.x(Road.laneCenter(l.toDouble()))
            b.box(xm - 1.05, xm + 1.05, y - 0.25, y + 0.25, 4.7, 6.5)
        }
        val v0 = c.x(0.1)
        val v1 = c.x(Road.HARD_SHOULDER - 0.1)
        b.box(min(v0, v1), max(v0, v1), y - 0.25, y + 0.25, 5.0, 6.5)
    }

    /** Where the face of gantry signals sit, for drawing the live displays. */
    fun gantryFaces(cw: Carriageway, s: Double, out: (lane: Int, face: SignFace) -> Unit) {
        val y = cw.worldY(s) - cw.dir * 0.27
        for (l in 0 until Road.LANES) {
            val m = Road.laneCenter(l.toDouble())
            out(l, SignFace(cw.worldX(m - 0.95), y, cw.worldX(m + 0.95), y, 4.75, 6.45, ""))
        }
        out(-1, SignFace(cw.worldX(0.2), y, cw.worldX(Road.HARD_SHOULDER - 0.2), y, 5.05, 6.45, ""))
    }

    private fun centralReservation(b: MeshBuilder, y0: Double) {
        val h = Road.CR_HALF
        b.color(CONCRETE)
        b.flat(-h, 0.0, h, CHUNK, 0.01)
        // Concrete step barrier.
        b.color(BARRIER)
        b.quad(-0.3, 0.0, 0.0, -0.12, 0.0, 0.9, -0.12, CHUNK, 0.9, -0.3, CHUNK, 0.0)
        b.quad(0.3, CHUNK, 0.0, 0.12, CHUNK, 0.9, 0.12, 0.0, 0.9, 0.3, 0.0, 0.0)
        b.flat(-0.12, 0.0, 0.12, CHUNK, 0.9)
        // Lighting columns every 40 m, except under the roundabouts.
        var k = ceil(y0 / 40).toLong()
        while (k * 40 < y0 + CHUNK) {
            val ly = k * 40 - y0
            if (abs(Road.junctionOffset(k * 40.0)) > 80 && abs(k * 40.0 - Terrain.bridgeY(k * 40.0)) > 12) {
                b.color(STEEL)
                b.cylinder(0.0, ly, 0.9, 12.0, 0.13, 6)
                b.box(-2.6, 2.6, ly - 0.06, ly + 0.06, 11.85, 12.0)
                b.color(LAMP, 0.7f)
                b.box(-2.9, -2.1, ly - 0.2, ly + 0.2, 11.7, 11.85)
                b.box(2.1, 2.9, ly - 0.2, ly + 0.2, 11.7, 11.85)
            }
            k++
        }
    }

    /** Deterministic trees in the fields beside the motorway and out on the hills. */
    private fun roadside(b: MeshBuilder, y0: Double) {
        var cell = floor(y0 / 25).toLong()
        while (cell * 25 < y0 + CHUNK) {
            for (side in 0..3) {
                val h = hash(cell * 4 + side)
                val count = (h % 3).toInt()
                for (i in 0 until count) {
                    val hi = hash(h + i * 7919L)
                    val dist = if (side < 2) 32.0 + (hi % 900) / 10.0 else 130.0 + (hi % 3200) / 10.0
                    val x = if (side % 2 == 0) -dist else dist
                    val y = cell * 25 + ((hi shr 10) % 250) / 10.0
                    if (y < y0 || y >= y0 + CHUNK || nearJunction(x, y)) continue
                    if (abs(x) < 200 && abs(y - Terrain.bridgeY(y)) < 25) continue
                    val size = 0.8 + ((hi shr 20) % 60) / 100.0
                    val ly = y - y0
                    val gz = Terrain.height(x, y) - 0.1
                    b.color(TRUNK)
                    b.box(x - 0.2, x + 0.2, ly - 0.2, ly + 0.2, gz, gz + 2.6 * size)
                    b.color(
                        when ((hi shr 30) % 3) {
                            0L -> TREE_A
                            1L -> TREE_B
                            else -> TREE_C
                        },
                    )
                    b.crown(x, ly, gz + 1.6 * size, gz + 8.5 * size, 3.0 * size)
                }
            }
            cell++
        }
    }

    private fun nearJunction(x: Double, y: Double): Boolean {
        val j = Road.junctionY(Road.junctionNearest(y))
        val dy = abs(y - j)
        if (dy < 980 && abs(x) < 140) return true
        if (dy < 40 && abs(x) < Road.RING_RADIUS + Road.LOCAL_LENGTH + 60) return true
        return false
    }

    fun hash(n: Long): Long {
        var x = n * -0x61c8864680b583ebL
        x = x xor (x ushr 29)
        x *= -0x4b47d5b1a6b9ec33L
        x = x xor (x ushr 32)
        return x and Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ junctions

    fun junction(j: Junction): WorldPiece {
        val b = MeshBuilder(32768)
        val signs = ArrayList<SignFace>()
        val cy = j.centreY
        ring(b, j)
        for (link in j.links) {
            if (link !is PathLink) continue
            ribbon(b, j, link, cy)
        }
        for (port in j.exits) {
            // A direction sign on the outer edge of the roundabout before each exit.
            val p = Pose()
            j.ring.pose(port.ringS - 14, 0.0, p)
            val out = Road.RING_WIDTH / 2 + 1.2
            val ox = p.x + sin(p.heading) * out
            val oy = p.y - cos(p.heading) * out - cy
            post(b, ox, oy, Road.RING_HEIGHT + 2.4)
            // Facing approaching drivers: left edge further back along the ring.
            val hx = cos(p.heading)
            val hy = sin(p.heading)
            val rx = sin(p.heading)
            val ry = -cos(p.heading)
            signs += SignFace(
                ox - rx * 1.4 - hx * 0.1, oy - ry * 1.4 - hy * 0.1 + cy,
                ox + rx * 1.4 - hx * 0.1, oy + ry * 1.4 - hy * 0.1 + cy,
                Road.RING_HEIGHT + 1.2, Road.RING_HEIGHT + 2.5, "exit_${port.name}",
            )
        }
        return WorldPiece(b.build(), cy, signs)
    }

    private fun ring(b: MeshBuilder, j: Junction) {
        val r = Road.RING_RADIUS
        val ri = r - Road.RING_WIDTH / 2
        val ro = r + Road.RING_WIDTH / 2
        val z = Road.RING_HEIGHT
        val seg = 120
        val armAngles = (j.entries + j.exits).map { 2 * PI * (1 - it.ringS / j.ring.length) + Road.deg(Junction.RING_START) }
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            b.color(ASPHALT, 2f)
            b.quad(ri * c0, ri * s0, z, ro * c0, ro * s0, z, ro * c1, ro * s1, z, ri * c1, ri * s1, z)
            b.color(PAINT)
            val e0 = ri + 0.35; val e1 = ri + 0.5
            b.quad(e0 * c0, e0 * s0, z + 0.02, e1 * c0, e1 * s0, z + 0.02, e1 * c1, e1 * s1, z + 0.02, e0 * c1, e0 * s1, z + 0.02)
            val f0 = ro - 0.5; val f1 = ro - 0.35
            val nearArm = armAngles.any { abs(wrap(it - (a0 + a1) / 2)) < Road.deg(9.0) }
            if (!nearArm) {
                b.quad(f0 * c0, f0 * s0, z + 0.02, f1 * c0, f1 * s0, z + 0.02, f1 * c1, f1 * s1, z + 0.02, f0 * c1, f0 * s1, z + 0.02)
            }
            // Deck edges and underside.
            b.color(DECK)
            b.quad(ro * c0, ro * s0, z - 1.1, ro * c1, ro * s1, z - 1.1, ro * c1, ro * s1, z, ro * c0, ro * s0, z)
            b.quad(ri * c1, ri * s1, z - 1.1, ri * c0, ri * s0, z - 1.1, ri * c0, ri * s0, z, ri * c1, ri * s1, z)
            b.quad(ri * c1, ri * s1, z - 1.1, ro * c1, ro * s1, z - 1.1, ro * c0, ro * s0, z - 1.1, ri * c0, ri * s0, z - 1.1)
            // Parapets: always on the inside, outside except where roads join.
            b.color(BARRIER)
            parapet(b, ri, ri - 0.3, c0, s0, c1, s1, z)
            if (!nearArm) parapet(b, ro, ro + 0.3, c0, s0, c1, s1, z)
        }
        // Piers: outside the carriageways and in the central reservation.
        b.color(DECK)
        for (i in 0 until 24) {
            val a = 2 * PI * i / 24
            val x = r * cos(a)
            val y = r * sin(a)
            if (abs(x) in 1.0..(Road.CR_HALF + Road.WIDTH + 1.5)) continue
            b.box(x - 0.7, x + 0.7, y - 0.7, y + 0.7, 0.0, z - 1.1)
        }
    }

    private fun wrap(a: Double): Double {
        var r = a % (2 * PI)
        if (r > PI) r -= 2 * PI
        if (r < -PI) r += 2 * PI
        return r
    }

    /** A low concrete wall on the ring between radii [ra] (road side) and [rb], from angle 0 to 1. */
    private fun parapet(b: MeshBuilder, ra: Double, rb: Double, c0: Double, s0: Double, c1: Double, s1: Double, z: Double) {
        val h = 0.9
        // Each face is drawn with both windings so it is lit from either side; the wall's
        // thickness keeps the two faces apart, avoiding z-fighting.
        for (r in doubleArrayOf(ra, rb)) {
            b.quad(r * c0, r * s0, z, r * c1, r * s1, z, r * c1, r * s1, z + h, r * c0, r * s0, z + h)
            b.quad(r * c1, r * s1, z, r * c0, r * s0, z, r * c0, r * s0, z + h, r * c1, r * s1, z + h)
        }
        b.quad(ra * c0, ra * s0, z + h, rb * c0, rb * s0, z + h, rb * c1, rb * s1, z + h, ra * c1, ra * s1, z + h)
        b.quad(rb * c0, rb * s0, z + h, ra * c0, ra * s0, z + h, ra * c1, ra * s1, z + h, rb * c1, rb * s1, z + h)
    }

    private fun ribbon(b: MeshBuilder, j: Junction, link: PathLink, cy: Double) {
        val local = link.kind == LinkKind.LOCAL_IN || link.kind == LinkKind.LOCAL_OUT || link.kind == LinkKind.LOOP
        val w = if (local) 4.0 else Road.SLIP_WIDTH
        val len = link.length
        val n = max(2, ceil(len / 2.5).toInt())
        val p = Pose()
        val clip = Road.RING_RADIUS + Road.RING_WIDTH / 2 - 0.3
        var prev: DoubleArray? = null
        for (i in 0..n) {
            val s = len * i / n
            link.pose(s, 0.0, p)
            val inside = hypot(p.x, p.y - cy) < clip
            val rx = sin(p.heading)
            val ry = -cos(p.heading)
            val z = p.z + 0.03
            val cur = doubleArrayOf(
                p.x - rx * w / 2, p.y - ry * w / 2 - cy, // left
                p.x + rx * w / 2, p.y + ry * w / 2 - cy, // right
                z, s,
            )
            val pv = prev
            if (pv != null && !inside) {
                val (lx0, ly0, rx0, ry0, z0) = pv
                val (lx1, ly1, rx1, ry1, z1) = cur
                b.color(ASPHALT, 2f)
                b.quad(lx0, ly0, z0, rx0, ry0, z0, rx1, ry1, z1, lx1, ly1, z1)
                // Edge lines.
                b.color(PAINT)
                edgeLine(b, lx0, ly0, lx1, ly1, rx0, ry0, rx1, ry1, z0, z1, 0.15, 0.3)
                val centreDash = local && link.kind != LinkKind.LOOP
                if (!centreDash || ((s / 6).toInt() % 2 == 0 && link.kind == LinkKind.LOCAL_IN)) {
                    edgeLine(b, rx0, ry0, rx1, ry1, lx0, ly0, lx1, ly1, z0, z1, 0.15, 0.3)
                }
                // Retaining walls and parapets where the road is raised.
                if (z0 > 0.3 || z1 > 0.3) {
                    b.color(DECK)
                    b.quad(lx1, ly1, 0.0, lx0, ly0, 0.0, lx0, ly0, z0, lx1, ly1, z1)
                    b.quad(rx0, ry0, 0.0, rx1, ry1, 0.0, rx1, ry1, z1, rx0, ry0, z0)
                }
                if (z0 > 1.5 && z1 > 1.5) {
                    b.color(BARRIER)
                    // Local roads are two-way: only their outer (left) edges get a wall.
                    parapetAlong(b, lx0, ly0, lx1, ly1, z0, z1)
                    if (!local) parapetAlong(b, rx1, ry1, rx0, ry0, z1, z0)
                }
            }
            prev = if (inside) null else cur
        }
        // Give-way markings: double broken line across the end of an approach.
        if (link.kind == LinkKind.OFF_SLIP || link.kind == LinkKind.LOCAL_IN) {
            b.color(PAINT)
            for (back in doubleArrayOf(5.0, 5.45)) {
                link.pose(len - back, 0.0, p)
                val rx = sin(p.heading)
                val ry = -cos(p.heading)
                val hx = cos(p.heading) * 0.15
                val hy = sin(p.heading) * 0.15
                var o = -w / 2 + 0.3
                while (o < w / 2 - 0.4) {
                    val ax = p.x + rx * o; val ay = p.y + ry * o - cy
                    val bx = p.x + rx * (o + 0.6); val by = p.y + ry * (o + 0.6) - cy
                    b.quad(ax - hx, ay - hy, p.z + 0.06, bx - hx, by - hy, p.z + 0.06, bx + hx, by + hy, p.z + 0.06, ax + hx, ay + hy, p.z + 0.06)
                    o += 0.9
                }
            }
        }
    }

    /** A painted line of [width] along the edge from (ax0..ax1) inset towards (bx0..bx1). */
    private fun edgeLine(
        b: MeshBuilder, ax0: Double, ay0: Double, ax1: Double, ay1: Double,
        bx0: Double, by0: Double, bx1: Double, by1: Double, z0: Double, z1: Double, inset: Double, width: Double,
    ) {
        fun lerp(a: Double, c: Double, t: Double) = a + (c - a) * t
        val w0 = hypot(bx0 - ax0, by0 - ay0)
        val w1 = hypot(bx1 - ax1, by1 - ay1)
        val t0a = inset / w0; val t0b = (inset + width) / w0
        val t1a = inset / w1; val t1b = (inset + width) / w1
        val p0x = lerp(ax0, bx0, t0a); val p0y = lerp(ay0, by0, t0a)
        val q0x = lerp(ax0, bx0, t0b); val q0y = lerp(ay0, by0, t0b)
        val p1x = lerp(ax1, bx1, t1a); val p1y = lerp(ay1, by1, t1a)
        val q1x = lerp(ax1, bx1, t1b); val q1y = lerp(ay1, by1, t1b)
        // Pick the winding whose normal points up (towards the sky).
        val cross = (q0x - p0x) * (p1y - p0y) - (q0y - p0y) * (p1x - p0x)
        if (cross < 0) b.quad(p0x, p0y, z0 + 0.02, p1x, p1y, z1 + 0.02, q1x, q1y, z1 + 0.02, q0x, q0y, z0 + 0.02)
        else b.quad(p0x, p0y, z0 + 0.02, q0x, q0y, z0 + 0.02, q1x, q1y, z1 + 0.02, p1x, p1y, z1 + 0.02)
    }

    /** A wall along the road edge from (x0, y0) to (x1, y1); its outside is to the left of that direction. */
    private fun parapetAlong(b: MeshBuilder, x0: Double, y0: Double, x1: Double, y1: Double, z0: Double, z1: Double) {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 1e-6) return
        val ox = -(y1 - y0) / len * 0.3
        val oy = (x1 - x0) / len * 0.3
        val h = 0.9
        b.quad(x0, y0, z0, x1, y1, z1, x1, y1, z1 + h, x0, y0, z0 + h) // road side
        b.quad(x1 + ox, y1 + oy, z1, x0 + ox, y0 + oy, z0, x0 + ox, y0 + oy, z0 + h, x1 + ox, y1 + oy, z1 + h) // outside
        b.quad(x1, y1, z1 + h, x1 + ox, y1 + oy, z1 + h, x0 + ox, y0 + oy, z0 + h, x0, y0, z0 + h) // top
    }
}
