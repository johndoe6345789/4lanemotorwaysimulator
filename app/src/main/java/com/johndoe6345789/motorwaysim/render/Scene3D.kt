package com.johndoe6345789.motorwaysim.render

import com.johndoe6345789.motorwaysim.sim.Carriageway
import com.johndoe6345789.motorwaysim.sim.Junction
import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Role
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.Vehicle
import com.johndoe6345789.motorwaysim.sim.Beacon
import com.johndoe6345789.motorwaysim.sim.wrapAngle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class ViewMode(val label: String) {
    CHASE("Chase"),
    HIGH("High"),
    BONNET("Bonnet"),
    HELICOPTER("Helicopter");

    fun next(): ViewMode = entries[(ordinal + 1) % entries.size]
}

/** A growable float array for per-frame vertex data. */
class FloatList(capacity: Int = 4096) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    fun clear() { size = 0 }

    fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(max(data.size * 2, size + extra))
    }

    fun put(v: Float) { data[size++] = v }
}

class DrawItem {
    var mesh: Mesh? = null
    val model = FloatArray(16)
    var tint = -1
    var ground = false
}

/** Everything the GL backend needs to draw one frame. */
class Frame {
    val viewProj = FloatArray(16)
    val view = FloatArray(16)
    val proj = FloatArray(16)
    var camX = 0f
    var camY = 0f
    var camZ = 0f
    var originX = 0.0
    var originY = 0.0
    val right = FloatArray(3)
    val up = FloatArray(3)
    var fogStart = 220f
    var fogEnd = 950f
    private val pool = ArrayList<DrawItem>()
    var count = 0
        private set

    /** Glow sprites: centre (3), corner x/y and size (3), colour (4) per vertex. */
    val glow = FloatList()

    /** Sign quads: position (3) and texture coordinates (2) per vertex. */
    val signs = FloatList()

    fun item(i: Int) = pool[i]

    fun begin() {
        count = 0
        glow.clear()
        signs.clear()
    }

    fun next(): DrawItem {
        if (count == pool.size) pool.add(DrawItem())
        return pool[count++].also { it.ground = false; it.tint = -1 }
    }
}

/**
 * Turns the simulation into a [Frame]: picks the camera, gathers visible scenery and
 * vehicles, and builds the light glows and sign faces. Pure Kotlin apart from [SignAtlas].
 */
class Scene3D(private val atlas: SignAtlas) {
    var view = ViewMode.CHASE
    private val chunks = HashMap<Int, WorldPiece>()
    private val junctions = HashMap<Int, WorldPiece>()

    /** Meshes no longer used; the GL backend frees their buffers. */
    val evicted = ArrayList<Mesh>()
    private var camHeading = Double.NaN
    private var orbit = 0.0

    private var eyeX = 0.0
    private var eyeY = 0.0
    private var eyeZ = 0.0
    private var fwdX = 0.0
    private var fwdY = 0.0
    private var fwdZ = 0.0

    fun build(sim: Simulation, f: Frame, width: Int, height: Int, dt: Double, time: Double) {
        f.begin()
        camera(sim, f, width, height, dt)
        val ox = f.originX
        val oy = f.originY

        // Ground.
        f.next().apply {
            mesh = WorldMeshes.ground
            ground = true
            Mat4.translation(model, round(eyeX / 100) * 100 - ox, round(eyeY / 100) * 100 - oy, 0.0)
        }

        // Motorway chunks.
        val reach = f.fogEnd + 120.0
        val c0 = floor((eyeY - reach) / WorldMeshes.CHUNK).toInt()
        val c1 = floor((eyeY + reach) / WorldMeshes.CHUNK).toInt()
        for (c in c0..c1) {
            val y = (c + 0.5) * WorldMeshes.CHUNK
            if (!visible(0.0, y, 0.0, 130.0, reach)) continue
            val piece = chunks.getOrPut(c) { WorldMeshes.chunk(c) }
            place(f, piece)
        }
        // Junctions.
        for (k in Road.junctionNearest(eyeY - reach - 700)..Road.junctionNearest(eyeY + reach + 700)) {
            val jy = Road.junctionY(k)
            if (!visible(0.0, jy, 0.0, 760.0, reach)) continue
            val piece = junctions.getOrPut(k) { WorldMeshes.junction(Junction(k)) }
            place(f, piece)
        }
        evict()

        // Vehicles and their lights.
        val blink = (time % 0.8) < 0.42
        for (v in sim.vehicles) {
            if (!visible(v.pose.x, v.pose.y, v.pose.z, 10.0, f.fogEnd + 30.0)) continue
            drawVehicle(sim, f, v, time, blink)
        }

        // Gantry signals and message signs.
        for (cw in Carriageway.entries) {
            val sA = cw.sOf(eyeY - reach)
            val sB = cw.sOf(eyeY + reach)
            for (g in Road.gantryIndexAt(min(sA, sB))..Road.gantryIndexAt(max(sA, sB)) + 1) {
                if (!Road.hasGantry(g)) continue
                val gs = g * Road.GANTRY_SPACING
                if (!visible(0.0, cw.worldY(gs), 5.0, 25.0, reach)) continue
                val signal = sim.signalAt(cw, g)
                WorldMeshes.gantryFaces(cw, gs) { lane, face ->
                    val key = if (lane < 0) SignAtlas.vmsKey(signal.message)
                    else SignAtlas.signalKey(signal.limitMph, signal.isClosed(lane))
                    sign(f, face, key)
                }
            }
        }
    }

    private fun place(f: Frame, piece: WorldPiece) {
        f.next().apply {
            mesh = piece.mesh
            Mat4.translation(model, -f.originX, piece.originY - f.originY, 0.0)
        }
        for (s in piece.signs) sign(f, s, s.key)
    }

    private fun evict() {
        val far = 2600.0
        val it = chunks.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (abs(e.value.originY + 50 - eyeY) > far) { evicted += e.value.mesh; it.remove() }
        }
        val jt = junctions.entries.iterator()
        while (jt.hasNext()) {
            val e = jt.next()
            if (abs(e.value.originY - eyeY) > far + 800) { evicted += e.value.mesh; jt.remove() }
        }
    }

    /** All cached meshes, so the GL backend can forget their buffers when the context is lost. */
    fun cachedMeshes(): List<Mesh> =
        chunks.values.map { it.mesh } + junctions.values.map { it.mesh } +
            VehicleModels.models.values.flatMap { m -> m.parts.map { it.first } } + WorldMeshes.ground

    /** Rough culling: within [reach] of the eye and not well behind it. */
    private fun visible(x: Double, y: Double, z: Double, radius: Double, reach: Double): Boolean {
        val dx = x - eyeX
        val dy = y - eyeY
        val dz = z - eyeZ
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 > (reach + radius) * (reach + radius)) return false
        return dx * fwdX + dy * fwdY + dz * fwdZ > -radius - 8
    }

    // ------------------------------------------------------------------ camera

    private fun camera(sim: Simulation, f: Frame, width: Int, height: Int, dt: Double) {
        val p = sim.player
        val px = p.pose.x
        val py = p.pose.y
        val pz = p.pose.z
        val target = p.pose.heading - p.wreckYaw
        camHeading = if (camHeading.isNaN()) target else camHeading + wrapAngle(target - camHeading) * min(1.0, dt * 3.5)
        val h = if (view == ViewMode.BONNET) target else camHeading
        val hx = cos(h)
        val hy = sin(h)
        var lx: Double
        var ly: Double
        var lz: Double
        if (sim.crashed) {
            // Circle slowly around the crash scene.
            orbit += dt * 0.22
            eyeX = px + cos(orbit) * 26; eyeY = py + sin(orbit) * 26; eyeZ = pz + 11
            lx = px; ly = py; lz = pz + 1.0
        } else {
            orbit = h + PI
            when (view) {
                ViewMode.CHASE -> {
                    // On tall screens look further down so the road, not the sky, fills the view.
                    val tall = (1 - width.toDouble() / max(1, height)).coerceIn(0.0, 0.6)
                    eyeX = px - hx * 11.5; eyeY = py - hy * 11.5; eyeZ = pz + 4.6 + tall * 3
                    lx = px + hx * 16; ly = py + hy * 16; lz = pz + 1.4 - tall * 5
                }
                ViewMode.HIGH -> {
                    eyeX = px - hx * 30; eyeY = py - hy * 30; eyeZ = pz + 15
                    lx = px + hx * 30; ly = py + hy * 30; lz = pz
                }
                ViewMode.BONNET -> {
                    val ahead = p.length / 2 - 1.2
                    eyeX = px + hx * ahead; eyeY = py + hy * ahead; eyeZ = pz + 1.4 + sin(p.pitch) * ahead
                    lx = eyeX + hx * 40; ly = eyeY + hy * 40; lz = eyeZ - 0.4 + sin(p.pitch) * 40
                }
                ViewMode.HELICOPTER -> {
                    eyeX = px - hx * 45; eyeY = py - hy * 45; eyeZ = pz + 130
                    lx = px + hx * 45; ly = py + hy * 45; lz = pz
                }
            }
        }
        eyeZ = max(eyeZ, 0.6)
        val fl = sqrt((lx - eyeX) * (lx - eyeX) + (ly - eyeY) * (ly - eyeY) + (lz - eyeZ) * (lz - eyeZ))
        fwdX = (lx - eyeX) / fl; fwdY = (ly - eyeY) / fl; fwdZ = (lz - eyeZ) / fl

        f.originX = 0.0
        f.originY = round(py / 1000) * 1000
        val aspect = width.toDouble() / max(1, height)
        val fovY = (2 * atan(tan(Math.toRadians(34.0)) / aspect)).coerceIn(Math.toRadians(50.0), Math.toRadians(78.0))
        val helicopter = view == ViewMode.HELICOPTER && !sim.crashed
        f.fogStart = if (helicopter) 420f else 230f
        f.fogEnd = if (helicopter) 1300f else 950f
        Mat4.perspective(f.proj, fovY, aspect, 0.5, f.fogEnd + 400.0)
        Mat4.lookAt(f.view, eyeX - f.originX, eyeY - f.originY, eyeZ, lx - f.originX, ly - f.originY, lz, 0.0, 0.0, 1.0)
        Mat4.multiply(f.viewProj, f.proj, f.view)
        f.camX = (eyeX - f.originX).toFloat()
        f.camY = (eyeY - f.originY).toFloat()
        f.camZ = eyeZ.toFloat()
        f.right[0] = f.view[0]; f.right[1] = f.view[4]; f.right[2] = f.view[8]
        f.up[0] = f.view[1]; f.up[1] = f.view[5]; f.up[2] = f.view[9]
    }

    // ------------------------------------------------------------------ vehicles

    private fun drawVehicle(sim: Simulation, f: Frame, v: Vehicle, time: Double, blink: Boolean) {
        val model = VehicleModels.models.getValue(v.type)
        var x = v.pose.x
        var y = v.pose.y
        var z = v.pose.z
        var heading = v.pose.heading
        var pitch = v.pitch
        val loader = v.loader
        if (loader != null) {
            // Being winched onto a recovery truck's flatbed.
            val t = v.loadProgress.let { it * it * (3 - 2 * it) }
            val lm = VehicleModels.models.getValue(loader.type)
            x += (loader.worldX(lm.bedY, 0.0) - x) * t
            y += (loader.worldY(lm.bedY, 0.0) - y) * t
            z += (loader.pose.z + lm.bedZ - z) * t + sin(PI * t) * 0.4
            heading += wrapAngle(loader.pose.heading - heading) * t
            pitch *= 1 - t
        }
        val wreck = v.role == Role.WRECK
        val color = if (wreck) darken(v.color) else v.color
        addParts(f, model, x, y, z, heading, pitch, color, v.id)

        v.carrying?.let { cargo ->
            val cm = VehicleModels.models.getValue(cargo.type)
            addParts(
                f, cm, v.worldX(model.bedY, 0.0), v.worldY(model.bedY, 0.0), v.pose.z + model.bedZ,
                v.pose.heading, v.pitch, darken(cargo.color), cargo.id,
            )
        }

        // Lights.
        val ch = cos(heading)
        val sh = sin(heading)
        fun at(forward: Double, right: Double, lz: Double, size: Float, r: Float, g: Float, b: Float, a: Float) {
            glow(f, x + ch * forward + sh * right, y + sh * forward - ch * right, z + lz, size, r, g, b, a)
        }
        val braking = if (v.isPlayer && !sim.autopilot && !sim.crashed) sim.brake else v.acc < -0.8 && v.v > 0.3
        if (braking) {
            at(model.rear - 0.05, -model.lampX, model.tailZ, 1.1f, 1f, 0.08f, 0.04f, 0.85f)
            at(model.rear - 0.05, model.lampX, model.tailZ, 1.1f, 1f, 0.08f, 0.04f, 0.85f)
        }
        if (blink) {
            val left = v.indicator < 0 || v.hazards
            val right = v.indicator > 0 || v.hazards
            for ((on, side) in listOf(left to -1.0, right to 1.0)) {
                if (!on) continue
                at(model.front + 0.05, side * (model.lampX + 0.15), model.headZ, 0.8f, 1f, 0.55f, 0f, 0.9f)
                at(model.rear - 0.05, side * (model.lampX + 0.15), model.tailZ, 0.8f, 1f, 0.55f, 0f, 0.9f)
            }
        }
        if (v.beacon != Beacon.NONE && model.beacons.isNotEmpty()) {
            val phase = (time * 2.4 + v.id * 0.37) % 1.0
            val strobe = ((time * 12).toInt() % 3) != 0
            for ((i, bpos) in model.beacons.withIndex()) {
                val on = (phase < 0.5) == (i == 0) && strobe
                if (!on) continue
                if (v.beacon == Beacon.BLUE) at(bpos[1], bpos[0], bpos[2], 2.6f, 0.2f, 0.4f, 1f, 1f)
                else at(bpos[1], bpos[0], bpos[2], 2.2f, 1f, 0.62f, 0.05f, 1f)
            }
        }
    }

    private fun addParts(f: Frame, model: VehicleModel, x: Double, y: Double, z: Double, heading: Double, pitch: Double, color: Int, variant: Int) {
        for ((mesh, tint) in model.parts) {
            f.next().apply {
                this.mesh = mesh
                this.tint = when (tint) {
                    Tint.BODY -> color
                    Tint.TRAILER -> VehicleModels.trailerColor(color, variant)
                    Tint.FIXED -> -1
                }
                Mat4.model(this.model, x - f.originX, y - f.originY, z, heading, pitch)
            }
        }
    }

    private fun darken(c: Int): Int {
        val r = ((c shr 16) and 0xFF) * 6 / 10
        val g = ((c shr 8) and 0xFF) * 6 / 10
        val b = (c and 0xFF) * 6 / 10
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------ batches

    private fun glow(f: Frame, x: Double, y: Double, z: Double, size: Float, r: Float, g: Float, b: Float, a: Float) {
        val cx = (x - f.originX).toFloat()
        val cy = (y - f.originY).toFloat()
        val cz = z.toFloat()
        val list = f.glow
        list.ensure(60)
        for (k in CORNERS.indices step 2) {
            list.put(cx); list.put(cy); list.put(cz)
            list.put(CORNERS[k]); list.put(CORNERS[k + 1]); list.put(size)
            list.put(r); list.put(g); list.put(b); list.put(a)
        }
    }

    private fun sign(f: Frame, face: SignFace, key: String) {
        val uv = atlas.uv(key)
        val x0 = (face.x0 - f.originX).toFloat()
        val y0 = (face.y0 - f.originY).toFloat()
        val x1 = (face.x1 - f.originX).toFloat()
        val y1 = (face.y1 - f.originY).toFloat()
        val zb = face.zBottom.toFloat()
        val zt = face.zTop.toFloat()
        val l = f.signs
        l.ensure(30)
        fun v(x: Float, y: Float, z: Float, u: Float, w: Float) { l.put(x); l.put(y); l.put(z); l.put(u); l.put(w) }
        v(x0, y0, zb, uv[0], uv[3]); v(x1, y1, zb, uv[2], uv[3]); v(x1, y1, zt, uv[2], uv[1])
        v(x0, y0, zb, uv[0], uv[3]); v(x1, y1, zt, uv[2], uv[1]); v(x0, y0, zt, uv[0], uv[1])
    }

    companion object {
        private val CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
    }
}
