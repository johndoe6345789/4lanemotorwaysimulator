package com.johndoe6345789.motorwaysim.render

import com.johndoe6345789.motorwaysim.sim.Beacon
import com.johndoe6345789.motorwaysim.sim.Carriageway
import com.johndoe6345789.motorwaysim.sim.Junction
import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Role
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.Vehicle
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
    var shine = 0f
    var fogMax = 1f
    var castsShadow = false
}

/** Everything the GL backend needs to draw one frame. */
class Frame {
    val viewProj = FloatArray(16)
    val invViewProj = FloatArray(16)
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val lightViewProj = FloatArray(16)
    val shadowMatrix = FloatArray(16)
    var camX = 0f
    var camY = 0f
    var camZ = 0f
    var originX = 0.0
    var originY = 0.0
    val right = FloatArray(3)
    val up = FloatArray(3)
    var fogStart = 260f
    var fogEnd = 1000f
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
        return pool[count++].also {
            it.ground = false; it.tint = -1; it.shine = 0f; it.fogMax = 1f; it.castsShadow = false
        }
    }
}

/**
 * Turns the simulation into a [Frame]: picks the camera, gathers visible scenery and
 * vehicles, sets up the sun's shadow map, and builds light glows and sign faces.
 * Pure Kotlin apart from [SignAtlas].
 */
class Scene3D(private val atlas: SignAtlas) {
    var view = ViewMode.CHASE

    /** Orbit the player's vehicle slowly, for the vehicle selection screen. */
    var showroom = false
    private val chunks = HashMap<Int, WorldPiece>()
    private val junctions = HashMap<Int, WorldPiece>()

    /** Meshes no longer used; the GL backend frees their buffers. */
    val evicted = ArrayList<Mesh>()
    private var camHeading = Double.NaN
    private var orbit = 0.0
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)

    private var eyeX = 0.0
    private var eyeY = 0.0
    private var eyeZ = 0.0
    private var fwdX = 0.0
    private var fwdY = 0.0
    private var fwdZ = 0.0
    private var shadowX = 0.0
    private var shadowY = 0.0

    fun build(sim: Simulation, f: Frame, width: Int, height: Int, dt: Double, time: Double) {
        f.begin()
        camera(sim, f, width, height, dt)
        shadowSetup(sim, f)
        val ox = f.originX
        val oy = f.originY

        // Ground and distant hills.
        f.next().apply {
            mesh = WorldMeshes.ground
            ground = true
            Mat4.translation(model, round(eyeX / 100) * 100 - ox, round(eyeY / 100) * 100 - oy, 0.0)
        }
        f.next().apply {
            mesh = hills
            fogMax = 0.6f
            Mat4.translation(model, eyeX - ox, eyeY - oy, 0.0)
        }

        // Motorway chunks.
        val reach = f.fogEnd + 120.0
        val c0 = floor((eyeY - reach) / WorldMeshes.CHUNK).toInt()
        val c1 = floor((eyeY + reach) / WorldMeshes.CHUNK).toInt()
        for (c in c0..c1) {
            val y = (c + 0.5) * WorldMeshes.CHUNK
            if (!visible(0.0, y, 0.0, 650.0, reach)) continue
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

        // Vehicles, their lights, and people waiting behind the barrier.
        val blink = (time % 0.8) < 0.42
        for (v in sim.vehicles) {
            if (!visible(v.pose.x, v.pose.y, v.pose.z, 10.0, f.fogEnd + 30.0)) continue
            drawVehicle(sim, f, v, time, blink)
        }
        for (inc in sim.incidents) {
            if (!inc.hardShoulder) continue
            val cw = inc.cw ?: continue
            for (w in inc.wrecks) {
                for ((k, back) in doubleArrayOf(12.0, 13.4).withIndex()) {
                    val s = w.rear - back
                    val x = cw.worldX(-4.2 - k * 0.7)
                    val y = cw.worldY(s)
                    if (!visible(x, y, 0.0, 3.0, f.fogEnd.toDouble())) continue
                    f.next().apply {
                        mesh = VehicleModels.person
                        castsShadow = true
                        Mat4.model(model, x - ox, y - oy, 0.0, cw.heading + PI)
                    }
                }
            }
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

        // Only things near the shadow map's area cast shadows.
        for (i in 0 until f.count) {
            val it = f.item(i)
            if (!it.castsShadow) continue
            val dy = it.model[13] + oy - shadowY
            if (abs(dy) > SHADOW_RANGE + 140) it.castsShadow = false
        }
    }

    private fun place(f: Frame, piece: WorldPiece) {
        f.next().apply {
            mesh = piece.mesh
            castsShadow = true
            Mat4.translation(model, -f.originX, piece.originY - f.originY, 0.0)
        }
        piece.terrain?.let { t ->
            f.next().apply {
                mesh = t
                ground = true
                Mat4.translation(model, -f.originX, piece.originY - f.originY, 0.0)
            }
        }
        for (s in piece.signs) sign(f, s, s.key)
    }

    private fun evict() {
        val far = 2600.0
        val it = chunks.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (abs(e.value.originY + 50 - eyeY) > far) {
                evicted += e.value.mesh
                e.value.terrain?.let { evicted += it }
                it.remove()
            }
        }
        val jt = junctions.entries.iterator()
        while (jt.hasNext()) {
            val e = jt.next()
            if (abs(e.value.originY - eyeY) > far + 800) { evicted += e.value.mesh; jt.remove() }
        }
    }

    /** All cached meshes, so the GL backend can forget their buffers when the context is lost. */
    fun cachedMeshes(): List<Mesh> =
        chunks.values.flatMap { listOfNotNull(it.mesh, it.terrain) } + junctions.values.map { it.mesh } +
            VehicleModels.models.values.flatMap { m -> m.parts.map { it.mesh } } +
            listOf(WorldMeshes.ground, hills, VehicleModels.person)

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
        val size = p.length
        val tall = p.type.height
        val lx: Double
        val ly: Double
        val lz: Double
        if (sim.crashed || showroom) {
            // Circle slowly around the crash scene, or the vehicle in the showroom.
            orbit += dt * if (showroom) 0.35 else 0.22
            val r = if (showroom) 3.5 + size * 0.75 else 26.0
            val up = if (showroom) 1.2 + tall * 0.5 else 11.0
            eyeX = px + cos(orbit) * r; eyeY = py + sin(orbit) * r; eyeZ = pz + up
            lx = px; ly = py; lz = pz + if (showroom) tall * 0.45 else 1.0
        } else {
            orbit = h + PI
            when (view) {
                ViewMode.CHASE -> {
                    // On tall screens look further down so the road, not the sky, fills the view.
                    val portrait = (1 - width.toDouble() / max(1, height)).coerceIn(0.0, 0.6)
                    val back = 6.0 + size * 0.6
                    eyeX = px - hx * back; eyeY = py - hy * back; eyeZ = pz + 1.6 + tall * 0.85 + portrait * 2.5
                    lx = px + hx * 14; ly = py + hy * 14; lz = pz + 1.0 + tall * 0.25 - portrait * 3.5
                }
                ViewMode.HIGH -> {
                    eyeX = px - hx * 30; eyeY = py - hy * 30; eyeZ = pz + 15
                    lx = px + hx * 30; ly = py + hy * 30; lz = pz
                }
                ViewMode.BONNET -> {
                    val ahead = p.length / 2 - if (p.type.isHeavy) 0.6 else 1.2
                    val seat = if (p.type.isHeavy) tall * 0.75 else 1.25
                    eyeX = px + hx * ahead; eyeY = py + hy * ahead; eyeZ = pz + seat + sin(p.pitch) * ahead
                    lx = eyeX + hx * 40; ly = eyeY + hy * 40; lz = eyeZ - 0.5 + sin(p.pitch) * 40
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
        // A touch wider at speed for a sense of pace.
        val speedFov = if (view == ViewMode.CHASE || view == ViewMode.BONNET) min(1.0, p.v / 35.0) * 6.0 else 0.0
        val fovY = (2 * atan(tan(Math.toRadians(34.0)) / aspect)).coerceIn(Math.toRadians(50.0), Math.toRadians(78.0)) +
            Math.toRadians(speedFov)
        val helicopter = view == ViewMode.HELICOPTER && !sim.crashed && !showroom
        f.fogStart = if (helicopter) 420f else 260f
        f.fogEnd = if (helicopter) 1300f else 1000f
        Mat4.perspective(f.proj, fovY, aspect, 0.4, 2300.0)
        Mat4.lookAt(f.view, eyeX - f.originX, eyeY - f.originY, eyeZ, lx - f.originX, ly - f.originY, lz, 0.0, 0.0, 1.0)
        Mat4.multiply(f.viewProj, f.proj, f.view)
        Mat4.invert(f.invViewProj, f.viewProj)
        f.camX = (eyeX - f.originX).toFloat()
        f.camY = (eyeY - f.originY).toFloat()
        f.camZ = eyeZ.toFloat()
        f.right[0] = f.view[0]; f.right[1] = f.view[4]; f.right[2] = f.view[8]
        f.up[0] = f.view[1]; f.up[1] = f.view[5]; f.up[2] = f.view[9]
    }

    /** An orthographic view from the sun covering the area just ahead of the player. */
    private fun shadowSetup(sim: Simulation, f: Frame) {
        val p = sim.player
        val ahead = if (view == ViewMode.HELICOPTER) 30.0 else SHADOW_RANGE * 0.55
        val texel = 2 * SHADOW_RANGE / SHADOW_MAP_SIZE
        val hl = sqrt(fwdX * fwdX + fwdY * fwdY).coerceAtLeast(1e-6)
        // Snap to whole texels so shadow edges don't shimmer as the camera moves.
        shadowX = round((p.pose.x + fwdX / hl * ahead) / texel) * texel
        shadowY = round((p.pose.y + fwdY / hl * ahead) / texel) * texel
        val cx = shadowX - f.originX
        val cy = shadowY - f.originY
        val sun = GlRenderer.SUN
        Mat4.lookAt(tmpA, cx + sun[0] * 500, cy + sun[1] * 500, sun[2] * 500.0, cx, cy, 0.0, 0.0, 0.0, 1.0)
        Mat4.ortho(tmpB, -SHADOW_RANGE, SHADOW_RANGE, -SHADOW_RANGE, SHADOW_RANGE, 1.0, 1000.0)
        Mat4.multiply(f.lightViewProj, tmpB, tmpA)
        Mat4.multiply(f.shadowMatrix, BIAS, f.lightViewProj)
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
        val braking = if (v.isPlayer && v.free && !sim.crashed) sim.brake else v.acc < -0.8 && v.v > 0.3
        if (braking) {
            at(model.rear - 0.05, -model.lampX, model.tailZ, 1.1f, 1f, 0.08f, 0.04f, 0.85f)
            at(model.rear - 0.05, model.lampX, model.tailZ, 1.1f, 1f, 0.08f, 0.04f, 0.85f)
        }
        if (blink) {
            val hazards = v.hazardsOn(sim.time)
            val left = v.indicator < 0 || hazards
            val right = v.indicator > 0 || hazards
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
        for (part in model.parts) {
            f.next().apply {
                this.mesh = part.mesh
                this.tint = when (part.tint) {
                    Tint.BODY -> color
                    Tint.TRAILER -> VehicleModels.trailerColor(color, variant)
                    Tint.FIXED -> -1
                }
                shine = part.shine
                castsShadow = true
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
        const val SHADOW_RANGE = 95.0
        const val SHADOW_MAP_SIZE = 2048

        /** Maps clip space (-1..1) to texture space (0..1). */
        private val BIAS = floatArrayOf(
            0.5f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f, 1f,
        )

        /** Hazy hills on the horizon, drawn around the camera. */
        val hills: Mesh by lazy {
            val b = MeshBuilder(2048).color(0xFF5F7E5C.toInt(), 0.15f)
            val n = 96
            val r0 = 1500.0
            val r1 = 2050.0
            fun height(a: Double) = 70 + 45 * sin(3 * a) + 28 * sin(7 * a + 1) + 16 * sin(13 * a + 2) + 9 * sin(29 * a)
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                // A slope facing the camera from the plain up to the ridge.
                b.quad(
                    r0 * cos(a1), r0 * sin(a1), -2.0, r0 * cos(a0), r0 * sin(a0), -2.0,
                    r1 * cos(a0), r1 * sin(a0), height(a0), r1 * cos(a1), r1 * sin(a1), height(a1),
                )
            }
            b.build()
        }
    }
}
