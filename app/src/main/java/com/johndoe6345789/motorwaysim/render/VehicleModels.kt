package com.johndoe6345789.motorwaysim.render

import com.johndoe6345789.motorwaysim.sim.VehicleType

/** How a model part is coloured. */
enum class Tint { BODY, TRAILER, FIXED }

/**
 * A low-poly vehicle model in local coordinates: x to the right, y forwards and z up,
 * with the origin at the centre of the footprint on the ground.
 */
class VehicleModel(
    val parts: List<Pair<Mesh, Tint>>,
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
    private val GLASS = 0xFF18222E.toInt()
    private val TRIM = 0xFF2A2C30.toInt()
    private val TYRE = 0xFF131313.toInt()
    private val HEADLAMP = 0xFFFFF4D6.toInt()
    private val TAIL = 0xFF8E1010.toInt()
    private val SHADOW = 0xFF1E2023.toInt()
    private val CHASSIS = 0xFF26282B.toInt()
    private val BED = 0xFF4A4E54.toInt()
    private val POLICE_YELLOW = 0xFFF6D20F.toInt()
    private val POLICE_BLUE = 0xFF1C4FB8.toInt()

    val models: Map<VehicleType, VehicleModel> by lazy { VehicleType.entries.associateWith { build(it) } }

    private fun build(type: VehicleType): VehicleModel = when (type) {
        VehicleType.CAR -> car()
        VehicleType.VAN -> van()
        VehicleType.LORRY -> lorry()
        VehicleType.COACH -> coach()
        VehicleType.POLICE -> police()
        VehicleType.RECOVERY -> recovery()
    }

    private fun lamps(m: MeshBuilder, front: Double, rear: Double, x: Double, headZ: Double, tailZ: Double) {
        m.color(HEADLAMP, 0.85f)
        m.box(-x - 0.18, -x + 0.18, front - 0.03, front + 0.03, headZ - 0.07, headZ + 0.07)
        m.box(x - 0.18, x + 0.18, front - 0.03, front + 0.03, headZ - 0.07, headZ + 0.07)
        m.color(TAIL, 0.4f)
        m.box(-x - 0.16, -x + 0.16, rear - 0.03, rear + 0.03, tailZ - 0.08, tailZ + 0.08)
        m.box(x - 0.16, x + 0.16, rear - 0.03, rear + 0.03, tailZ - 0.08, tailZ + 0.08)
    }

    private fun shadow(m: MeshBuilder, hw: Double, front: Double, rear: Double) {
        m.color(SHADOW)
        m.flat(-hw - 0.15, rear - 0.2, hw + 0.15, front + 0.2, 0.02)
    }

    private fun car(): VehicleModel {
        val body = MeshBuilder()
        body.box(-0.9, 0.9, -2.25, 2.25, 0.3, 0.78)
        body.box(-0.86, 0.86, -2.2, 1.0, 0.78, 0.86)
        body.box(-0.72, 0.72, -1.2, 0.8, 1.36, 1.45)
        body.box(-1.04, -0.9, 0.95, 1.12, 0.85, 0.98)
        body.box(0.9, 1.04, 0.95, 1.12, 0.85, 0.98)
        val detail = MeshBuilder()
        shadow(detail, 0.9, 2.25, -2.25)
        detail.color(GLASS)
        detail.box(-0.78, 0.78, -1.3, 0.95, 0.86, 1.36)
        detail.color(TRIM)
        detail.box(-0.92, 0.92, 2.12, 2.3, 0.28, 0.5)
        detail.box(-0.92, 0.92, -2.3, -2.12, 0.28, 0.5)
        lamps(detail, 2.25, -2.25, 0.62, 0.62, 0.7)
        detail.color(TYRE)
        for (y in doubleArrayOf(-1.4, 1.4)) for (x in doubleArrayOf(-0.78, 0.78)) detail.wheel(x, y, 0.32, 0.32, 0.11)
        return VehicleModel(listOf(body.build() to Tint.BODY, detail.build() to Tint.FIXED), 2.25, -2.25, 0.62, 0.62, 0.7)
    }

    private fun van(): VehicleModel {
        val body = MeshBuilder()
        body.box(-1.0, 1.0, -2.8, 2.15, 0.35, 2.35)
        body.box(-1.0, 1.0, 2.15, 2.8, 0.35, 1.25)
        body.box(-0.98, 0.98, 2.15, 2.4, 1.25, 1.35)
        val detail = MeshBuilder()
        shadow(detail, 1.0, 2.8, -2.8)
        detail.color(GLASS)
        detail.quad(-0.92, 2.42, 1.3, 0.92, 2.42, 1.3, 0.92, 2.17, 2.2, -0.92, 2.17, 2.2)
        detail.box(-1.01, 1.01, 1.4, 2.12, 1.35, 2.05)
        detail.color(TRIM)
        detail.box(-1.02, 1.02, 2.7, 2.85, 0.32, 0.6)
        detail.box(-1.02, 1.02, -2.85, -2.7, 0.32, 0.6)
        lamps(detail, 2.8, -2.8, 0.72, 0.8, 0.9)
        detail.color(TYRE)
        for (y in doubleArrayOf(-1.9, 1.85)) for (x in doubleArrayOf(-0.86, 0.86)) detail.wheel(x, y, 0.36, 0.36, 0.13)
        return VehicleModel(listOf(body.build() to Tint.BODY, detail.build() to Tint.FIXED), 2.8, -2.8, 0.72, 0.8, 0.9)
    }

    private fun lorry(): VehicleModel {
        val cab = MeshBuilder()
        cab.box(-1.25, 1.25, 5.9, 8.25, 0.75, 3.35)
        cab.box(-1.2, 1.2, 6.2, 8.0, 3.35, 3.6)
        val trailer = MeshBuilder()
        trailer.box(-1.27, 1.27, -8.25, 5.65, 1.2, 3.95)
        val detail = MeshBuilder()
        shadow(detail, 1.27, 8.25, -8.25)
        detail.color(GLASS)
        detail.box(-1.15, 1.15, 8.24, 8.3, 2.0, 3.05)
        detail.box(-1.26, 1.26, 7.2, 8.1, 2.1, 2.95)
        detail.color(CHASSIS)
        detail.box(-0.95, 0.95, -8.0, 8.0, 0.55, 1.2)
        detail.color(TRIM)
        detail.box(-1.27, 1.27, 8.2, 8.35, 0.5, 0.95)
        lamps(detail, 8.32, -8.27, 0.95, 0.9, 1.0)
        detail.color(TYRE)
        for (y in doubleArrayOf(7.3, 5.9, -5.4, -6.7, -8.0 + 0.6)) for (x in doubleArrayOf(-1.0, 1.0)) {
            detail.wheel(x, y, 0.5, 0.5, 0.17)
        }
        return VehicleModel(
            listOf(cab.build() to Tint.BODY, trailer.build() to Tint.TRAILER, detail.build() to Tint.FIXED),
            8.25, -8.25, 0.95, 0.9, 1.0,
        )
    }

    private fun coach(): VehicleModel {
        val body = MeshBuilder()
        body.box(-1.27, 1.27, -6.25, 6.25, 0.35, 3.45)
        val detail = MeshBuilder()
        shadow(detail, 1.27, 6.25, -6.25)
        detail.color(GLASS)
        detail.box(-1.29, 1.29, -5.6, 5.7, 1.55, 2.9)
        detail.box(-1.2, 1.2, 6.2, 6.3, 0.95, 3.1)
        detail.color(TRIM)
        detail.box(-1.28, 1.28, 6.2, 6.33, 0.35, 0.75)
        detail.box(-0.7, 0.7, -2.0, 1.5, 3.45, 3.7)
        lamps(detail, 6.3, -6.28, 0.95, 0.75, 1.0)
        detail.color(TYRE)
        for (y in doubleArrayOf(4.4, -3.4)) for (x in doubleArrayOf(-1.02, 1.02)) detail.wheel(x, y, 0.5, 0.5, 0.17)
        return VehicleModel(listOf(body.build() to Tint.BODY, detail.build() to Tint.FIXED), 6.25, -6.25, 0.95, 0.75, 1.0)
    }

    private fun police(): VehicleModel {
        val body = MeshBuilder()
        body.box(-0.92, 0.92, -2.45, 2.45, 0.3, 0.82)
        body.box(-0.88, 0.88, -2.4, 1.1, 0.82, 0.9)
        body.box(-0.76, 0.76, -2.15, 0.85, 1.44, 1.52)
        val detail = MeshBuilder()
        shadow(detail, 0.92, 2.45, -2.45)
        detail.color(GLASS)
        detail.box(-0.8, 0.8, -2.2, 0.95, 0.9, 1.44)
        // Battenburg livery: alternating yellow and blue blocks along both sides.
        for (i in 0 until 8) {
            detail.color(if (i % 2 == 0) POLICE_YELLOW else POLICE_BLUE)
            val y0 = -2.4 + i * 0.6
            val y1 = y0 + 0.6
            detail.box(-0.94, -0.92, y0, y1, 0.4, 0.78)
            detail.box(0.92, 0.94, y0, y1, 0.4, 0.78)
        }
        detail.color(POLICE_YELLOW)
        detail.box(-0.86, 0.86, 1.6, 2.4, 0.83, 0.86)
        detail.color(TRIM)
        detail.box(-0.94, 0.94, 2.35, 2.5, 0.28, 0.5)
        detail.box(-0.94, 0.94, -2.5, -2.35, 0.28, 0.5)
        detail.box(-0.62, 0.62, -0.25, 0.15, 1.52, 1.64)
        lamps(detail, 2.45, -2.45, 0.64, 0.64, 0.72)
        detail.color(TYRE)
        for (y in doubleArrayOf(-1.5, 1.5)) for (x in doubleArrayOf(-0.8, 0.8)) detail.wheel(x, y, 0.33, 0.33, 0.11)
        return VehicleModel(
            listOf(body.build() to Tint.BODY, detail.build() to Tint.FIXED), 2.45, -2.45, 0.64, 0.64, 0.72,
            beacons = listOf(doubleArrayOf(-0.45, -0.05, 1.68), doubleArrayOf(0.45, -0.05, 1.68)),
        )
    }

    private fun recovery(): VehicleModel {
        val cab = MeshBuilder()
        cab.box(-1.25, 1.25, 2.3, 4.3, 0.6, 2.95)
        val detail = MeshBuilder()
        shadow(detail, 1.25, 4.3, -4.3)
        detail.color(GLASS)
        detail.box(-1.15, 1.15, 4.26, 4.34, 1.75, 2.75)
        detail.box(-1.26, 1.26, 3.3, 4.2, 1.8, 2.7)
        detail.color(BED)
        detail.box(-1.25, 1.25, -4.3, 2.15, 0.95, 1.15)
        detail.box(-1.25, -1.15, -4.3, 2.15, 1.15, 1.35)
        detail.box(1.15, 1.25, -4.3, 2.15, 1.15, 1.35)
        detail.box(-1.2, 1.2, 1.9, 2.2, 1.15, 2.4)
        detail.color(CHASSIS)
        detail.box(-0.9, 0.9, -4.0, 4.0, 0.5, 0.95)
        detail.color(TRIM)
        detail.box(-0.85, 0.85, 2.95, 3.25, 2.95, 3.08)
        detail.box(-1.26, 1.26, 4.25, 4.4, 0.45, 0.85)
        lamps(detail, 4.35, -4.32, 0.9, 0.8, 0.9)
        detail.color(TYRE)
        for (y in doubleArrayOf(3.4, -2.4, -3.5)) for (x in doubleArrayOf(-1.0, 1.0)) detail.wheel(x, y, 0.48, 0.48, 0.16)
        return VehicleModel(
            listOf(cab.build() to Tint.BODY, detail.build() to Tint.FIXED), 4.3, -4.3, 0.9, 0.8, 0.9,
            beacons = listOf(doubleArrayOf(-0.62, 3.1, 3.12), doubleArrayOf(0.62, 3.1, 3.12)),
            bedZ = 1.15, bedY = -1.1,
        )
    }

    private val TRAILER_COLORS = intArrayOf(0xFFEDEDED.toInt(), 0xFFDDE0E4.toInt(), 0xFFC9CED4.toInt())

    fun trailerColor(color: Int, variant: Int): Int =
        if (variant % 3 == 0) color else TRAILER_COLORS[Math.floorMod(variant, TRAILER_COLORS.size)]
}
