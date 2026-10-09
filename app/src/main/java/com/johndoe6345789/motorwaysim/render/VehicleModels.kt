package com.johndoe6345789.motorwaysim.render

import com.johndoe6345789.motorwaysim.sim.VehicleType

/** How a model part is coloured. */
enum class Tint { BODY, TRAILER, FIXED }

/** One mesh of a model, with how it is tinted and how glossy it is. */
class Part(val mesh: Mesh, val tint: Tint, val shine: Float)

/**
 * A low-poly vehicle model in local coordinates: x to the right, y forwards and z up,
 * with the origin at the centre of the footprint on the ground.
 */
class VehicleModel(
    val parts: List<Part>,
    val front: Double,
    val rear: Double,
    /** Lateral position of the lamp clusters. */
    val lampX: Double,
    val headZ: Double,
    val tailZ: Double,
    /** Roof beacons (x, y, z) for emergency and recovery vehicles. */
    val beacons: List<DoubleArray> = emptyList(),
    /** Height and centre of the flatbed of a recovery truck. */
    val bedZ: Double = 0.0,
    val bedY: Double = 0.0,
)

object VehicleModels {
    private val GLASS = 0xFF16202B.toInt()
    private val TRIM = 0xFF24262A.toInt()
    private val TYRE = 0xFF111111.toInt()
    private val RIM = 0xFFB9BEC4.toInt()
    private val HEADLAMP = 0xFFFFF4D6.toInt()
    private val TAIL = 0xFF8E1010.toInt()
    private val SHADOW = 0xFF1A1C1F.toInt()
    private val CHASSIS = 0xFF26282B.toInt()
    private val BED = 0xFF4A4E54.toInt()
    private val GRILLE = 0xFF0E0F11.toInt()
    private val POLICE_YELLOW = 0xFFF6D20F.toInt()
    private val POLICE_BLUE = 0xFF1C4FB8.toInt()

    val models: Map<VehicleType, VehicleModel> by lazy { VehicleType.entries.associateWith { build(it) } }

    private fun build(type: VehicleType): VehicleModel = when (type) {
        VehicleType.CAR -> car()
        VehicleType.SPORTS -> sports()
        VehicleType.VAN -> van()
        VehicleType.LORRY -> lorry()
        VehicleType.COACH -> coach()
        VehicleType.POLICE -> police()
        VehicleType.RECOVERY -> recovery()
    }

    private fun lamps(m: MeshBuilder, front: Double, rear: Double, x: Double, headZ: Double, tailZ: Double) {
        m.color(HEADLAMP, 0.85f)
        m.box(-x - 0.2, -x + 0.18, front - 0.04, front + 0.03, headZ - 0.07, headZ + 0.08)
        m.box(x - 0.18, x + 0.2, front - 0.04, front + 0.03, headZ - 0.07, headZ + 0.08)
        m.color(TAIL, 0.45f)
        m.box(-x - 0.18, -x + 0.18, rear - 0.03, rear + 0.04, tailZ - 0.08, tailZ + 0.09)
        m.box(x - 0.18, x + 0.18, rear - 0.03, rear + 0.04, tailZ - 0.08, tailZ + 0.09)
    }

    private fun shadow(m: MeshBuilder, hw: Double, front: Double, rear: Double) {
        m.color(SHADOW)
        m.flat(-hw - 0.1, rear - 0.1, hw + 0.1, front + 0.1, 0.03)
    }

    /** Tyres with alloy rims. */
    private fun wheels(m: MeshBuilder, ys: DoubleArray, x: Double, r: Double, hw: Double) {
        for (y in ys) for (side in doubleArrayOf(-1.0, 1.0)) {
            m.color(TYRE)
            m.wheel(side * x, y, r, r, hw)
            m.color(RIM)
            m.wheel(side * (x + hw + 0.012), y, r, r * 0.62, 0.012, 8)
        }
    }

    private fun parts(body: MeshBuilder, detail: MeshBuilder, extra: MeshBuilder? = null) = buildList {
        add(Part(body.build(), Tint.BODY, 0.9f))
        if (extra != null) add(Part(extra.build(), Tint.TRAILER, 0.25f))
        add(Part(detail.build(), Tint.FIXED, 0.5f))
    }

    private fun car(): VehicleModel {
        val body = MeshBuilder()
        // Lower body with rounded-off ends, then bonnet and boot, and the roof.
        body.frustum(-0.9, 0.9, -2.25, 2.25, 0.3, -0.88, 0.88, -2.15, 2.1, 0.72)
        body.frustum(-0.88, 0.88, -2.15, 2.1, 0.72, -0.84, 0.84, -2.05, 1.95, 0.84)
        body.frustum(-0.69, 0.69, -1.08, 0.5, 1.36, -0.66, 0.66, -1.0, 0.42, 1.43)
        body.box(-1.03, -0.88, 0.75, 0.92, 0.86, 0.98)
        body.box(0.88, 1.03, 0.75, 0.92, 0.86, 0.98)
        val d = MeshBuilder()
        shadow(d, 0.9, 2.25, -2.25)
        d.color(GLASS)
        // Raked windscreen and rear window.
        d.frustum(-0.82, 0.82, -1.6, 1.1, 0.84, -0.69, 0.69, -1.08, 0.5, 1.36)
        d.color(TRIM)
        d.box(-0.92, 0.92, 2.12, 2.3, 0.28, 0.5)
        d.box(-0.92, 0.92, -2.3, -2.12, 0.28, 0.5)
        d.color(GRILLE)
        d.box(-0.45, 0.45, 2.2, 2.27, 0.48, 0.66)
        lamps(d, 2.25, -2.25, 0.62, 0.62, 0.7)
        wheels(d, doubleArrayOf(-1.38, 1.38), 0.8, 0.32, 0.11)
        return VehicleModel(parts(body, d), 2.25, -2.25, 0.62, 0.62, 0.7)
    }

    private fun sports(): VehicleModel {
        val body = MeshBuilder()
        body.frustum(-0.95, 0.95, -2.2, 2.2, 0.22, -0.93, 0.93, -2.1, 2.05, 0.62)
        body.frustum(-0.93, 0.93, -2.1, 2.05, 0.62, -0.85, 0.85, -1.95, 1.6, 0.72)
        body.frustum(-0.66, 0.66, -0.95, 0.15, 1.16, -0.62, 0.62, -0.85, 0.05, 1.22)
        body.box(-0.8, 0.8, -2.15, -1.95, 0.95, 1.0) // rear spoiler
        val d = MeshBuilder()
        shadow(d, 0.95, 2.2, -2.2)
        d.color(GLASS)
        d.frustum(-0.84, 0.84, -1.75, 1.25, 0.72, -0.66, 0.66, -0.95, 0.15, 1.16)
        d.color(TRIM)
        d.box(-0.12, 0.12, -2.05, -1.95, 0.72, 0.95)
        d.box(-0.9, 0.9, 2.08, 2.25, 0.2, 0.4)
        d.color(GRILLE)
        d.box(-0.6, 0.6, 2.15, 2.22, 0.28, 0.42)
        lamps(d, 2.2, -2.2, 0.68, 0.52, 0.6)
        wheels(d, doubleArrayOf(-1.35, 1.35), 0.83, 0.33, 0.14)
        return VehicleModel(parts(body, d), 2.2, -2.2, 0.68, 0.52, 0.6)
    }

    private fun van(): VehicleModel {
        val body = MeshBuilder()
        body.box(-1.0, 1.0, -2.8, 1.75, 0.35, 2.35)
        body.frustum(-1.0, 1.0, 1.75, 2.8, 0.35, -0.98, 0.98, 1.75, 2.62, 1.2)
        body.frustum(-0.98, 0.98, 1.75, 2.62, 1.2, -0.97, 0.97, 1.75, 2.0, 2.33)
        val d = MeshBuilder()
        shadow(d, 1.0, 2.8, -2.8)
        d.color(GLASS)
        d.quad(-0.9, 2.6, 1.24, 0.9, 2.6, 1.24, 0.9, 2.01, 2.27, -0.9, 2.01, 2.27)
        d.box(-1.01, 1.01, 1.1, 1.95, 1.35, 2.05)
        d.color(TRIM)
        d.box(-1.02, 1.02, 2.7, 2.86, 0.32, 0.6)
        d.box(-1.02, 1.02, -2.86, -2.7, 0.32, 0.6)
        d.color(GRILLE)
        d.box(-0.6, 0.6, 2.78, 2.84, 0.62, 0.95)
        lamps(d, 2.8, -2.8, 0.72, 0.85, 0.95)
        wheels(d, doubleArrayOf(-1.9, 1.85), 0.86, 0.36, 0.13)
        return VehicleModel(parts(body, d), 2.8, -2.8, 0.72, 0.85, 0.95)
    }

    private fun lorry(): VehicleModel {
        val cab = MeshBuilder()
        cab.frustum(-1.25, 1.25, 5.9, 8.25, 0.75, -1.22, 1.22, 5.9, 8.1, 3.35)
        cab.frustum(-1.22, 1.22, 6.1, 8.0, 3.35, -1.15, 1.15, 6.3, 7.7, 3.75) // aerodynamic roof
        val trailer = MeshBuilder()
        trailer.box(-1.27, 1.27, -8.25, 5.65, 1.2, 3.95)
        val d = MeshBuilder()
        shadow(d, 1.27, 8.25, -8.25)
        d.color(GLASS)
        d.quad(-1.13, 8.2, 2.0, 1.13, 8.2, 2.0, 1.13, 8.13, 3.1, -1.13, 8.13, 3.1)
        d.box(-1.26, 1.26, 7.1, 8.0, 2.1, 2.95)
        d.color(CHASSIS)
        d.box(-0.95, 0.95, -8.0, 8.0, 0.55, 1.2)
        d.box(-1.24, -1.18, -4.5, 3.5, 0.55, 1.15) // side skirts
        d.box(1.18, 1.24, -4.5, 3.5, 0.55, 1.15)
        d.color(TRIM)
        d.box(-1.27, 1.27, 8.2, 8.35, 0.5, 0.95)
        d.color(GRILLE)
        d.box(-0.9, 0.9, 8.24, 8.3, 1.0, 1.9)
        lamps(d, 8.32, -8.27, 0.95, 0.85, 1.0)
        wheels(d, doubleArrayOf(7.3, 5.9, -5.4, -6.7, -7.4), 1.0, 0.5, 0.17)
        return VehicleModel(parts(cab, d, trailer), 8.25, -8.25, 0.95, 0.85, 1.0)
    }

    private fun coach(): VehicleModel {
        val body = MeshBuilder()
        body.frustum(-1.27, 1.27, -6.25, 6.25, 0.35, -1.25, 1.25, -6.2, 6.1, 3.4)
        body.frustum(-1.25, 1.25, -6.2, 6.1, 3.4, -1.18, 1.18, -6.0, 5.8, 3.5)
        val d = MeshBuilder()
        shadow(d, 1.27, 6.25, -6.25)
        d.color(GLASS)
        d.box(-1.28, 1.28, -5.6, 5.6, 1.55, 2.9)
        d.quad(-1.18, 6.27, 1.0, 1.18, 6.27, 1.0, 1.15, 6.12, 3.2, -1.15, 6.12, 3.2)
        d.color(TRIM)
        d.box(-1.28, 1.28, 6.2, 6.33, 0.35, 0.75)
        d.box(-0.7, 0.7, -2.0, 1.5, 3.5, 3.72)
        lamps(d, 6.3, -6.28, 0.95, 0.75, 1.0)
        wheels(d, doubleArrayOf(4.4, -3.4, -4.6), 1.02, 0.5, 0.17)
        return VehicleModel(parts(body, d), 6.25, -6.25, 0.95, 0.75, 1.0)
    }

    private fun police(): VehicleModel {
        val body = MeshBuilder()
        body.frustum(-0.92, 0.92, -2.45, 2.45, 0.3, -0.9, 0.9, -2.4, 2.3, 0.8)
        body.frustum(-0.9, 0.9, -2.4, 2.3, 0.8, -0.86, 0.86, -2.35, 2.15, 0.9)
        body.frustum(-0.76, 0.76, -2.2, 0.75, 1.44, -0.74, 0.74, -2.15, 0.65, 1.52)
        val d = MeshBuilder()
        shadow(d, 0.92, 2.45, -2.45)
        d.color(GLASS)
        d.frustum(-0.84, 0.84, -2.3, 1.35, 0.9, -0.76, 0.76, -2.2, 0.75, 1.44)
        // Battenburg livery: alternating yellow and blue blocks along both sides.
        for (i in 0 until 8) {
            d.color(if (i % 2 == 0) POLICE_YELLOW else POLICE_BLUE)
            val y0 = -2.4 + i * 0.6
            val y1 = y0 + 0.6
            d.box(-0.935, -0.91, y0, y1, 0.42, 0.76)
            d.box(0.91, 0.935, y0, y1, 0.42, 0.76)
        }
        d.color(POLICE_YELLOW)
        d.box(-0.8, 0.8, 1.6, 2.2, 0.86, 0.9)
        d.color(TRIM)
        d.box(-0.94, 0.94, 2.35, 2.5, 0.28, 0.5)
        d.box(-0.94, 0.94, -2.5, -2.35, 0.28, 0.5)
        d.box(-0.62, 0.62, -0.25, 0.15, 1.52, 1.64)
        lamps(d, 2.45, -2.45, 0.64, 0.64, 0.72)
        wheels(d, doubleArrayOf(-1.5, 1.5), 0.82, 0.33, 0.11)
        return VehicleModel(
            parts(body, d), 2.45, -2.45, 0.64, 0.64, 0.72,
            beacons = listOf(doubleArrayOf(-0.45, -0.05, 1.68), doubleArrayOf(0.45, -0.05, 1.68)),
        )
    }

    private fun recovery(): VehicleModel {
        val cab = MeshBuilder()
        cab.frustum(-1.25, 1.25, 2.3, 4.3, 0.6, -1.22, 1.22, 2.3, 4.05, 2.95)
        val d = MeshBuilder()
        shadow(d, 1.25, 4.3, -4.3)
        d.color(GLASS)
        d.quad(-1.13, 4.28, 1.75, 1.13, 4.28, 1.75, 1.13, 4.1, 2.75, -1.13, 4.1, 2.75)
        d.box(-1.26, 1.26, 3.2, 4.0, 1.8, 2.7)
        d.color(BED)
        d.box(-1.25, 1.25, -4.3, 2.15, 0.95, 1.15)
        d.box(-1.25, -1.15, -4.3, 2.15, 1.15, 1.35)
        d.box(1.15, 1.25, -4.3, 2.15, 1.15, 1.35)
        d.box(-1.2, 1.2, 1.9, 2.2, 1.15, 2.4)
        d.color(CHASSIS)
        d.box(-0.9, 0.9, -4.0, 4.0, 0.5, 0.95)
        d.color(TRIM)
        d.box(-0.85, 0.85, 2.95, 3.25, 2.95, 3.08)
        d.box(-1.26, 1.26, 4.25, 4.4, 0.45, 0.85)
        lamps(d, 4.35, -4.32, 0.9, 0.8, 0.9)
        wheels(d, doubleArrayOf(3.4, -2.4, -3.5), 1.0, 0.48, 0.16)
        return VehicleModel(
            parts(cab, d), 4.3, -4.3, 0.9, 0.8, 0.9,
            beacons = listOf(doubleArrayOf(-0.62, 3.1, 3.12), doubleArrayOf(0.62, 3.1, 3.12)),
            bedZ = 1.15, bedY = -1.1,
        )
    }

    /** A person standing, facing +y: for drivers waiting behind the barrier after a breakdown. */
    val person: Mesh by lazy {
        val m = MeshBuilder()
        m.color(0xFF2B2F3A.toInt())
        m.box(-0.18, -0.03, -0.1, 0.1, 0.0, 0.85)
        m.box(0.03, 0.18, -0.1, 0.1, 0.0, 0.85)
        m.color(0xFFF2D21B.toInt()) // hi-vis jacket
        m.box(-0.24, 0.24, -0.13, 0.13, 0.85, 1.48)
        m.color(0xFFD9A782.toInt())
        m.box(-0.1, 0.1, -0.1, 0.1, 1.5, 1.74)
        m.build()
    }

    private val TRAILER_COLORS = intArrayOf(0xFFEDEDED.toInt(), 0xFFDDE0E4.toInt(), 0xFFC9CED4.toInt())

    fun trailerColor(color: Int, variant: Int): Int =
        if (variant % 3 == 0) color else TRAILER_COLORS[Math.floorMod(variant, TRAILER_COLORS.size)]
}
