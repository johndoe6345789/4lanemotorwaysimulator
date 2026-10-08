package com.johndoe6345789.motorwaysim.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.johndoe6345789.motorwaysim.sim.GantrySignal
import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.Vehicle
import com.johndoe6345789.motorwaysim.sim.VehicleType
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Draws the road, scenery and vehicles in world space. */
class SceneRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    fun draw(canvas: Canvas, sim: Simulation, oncoming: OncomingTraffic, cam: Camera, realTime: Double) {
        val blinkOn = (realTime % 0.7) < 0.38
        drawGround(canvas, cam)
        drawCarriageway(canvas, cam, mirrored = false)
        drawCarriageway(canvas, cam, mirrored = true)
        drawCentralReservation(canvas, cam)
        drawRoadside(canvas, cam)
        drawTrees(canvas, cam)

        for (c in oncoming.cars) {
            if (c.s > cam.sTop + 20 || c.rearS < cam.sBottom - 20) continue
            val centreS = c.s + c.type.length / 2
            drawVehicle(
                canvas, cam, c.type, c.color, c.variant, Scene.oppositeLaneCenter(c.lane), centreS,
                180f, braking = false, indicator = 0, blinkOn = false, isPlayer = false,
            )
        }
        for (v in sim.vehicles) {
            if (v.rear > cam.sTop + 20 || v.s < cam.sBottom - 20) continue
            drawSimVehicle(canvas, cam, sim, v, blinkOn)
        }
        drawGantries(canvas, sim, cam, blinkOn)
    }

    // ------------------------------------------------------------------ scenery

    private fun drawGround(canvas: Canvas, cam: Camera) {
        canvas.drawColor(GRASS)
        // Mowing stripes.
        fill.color = GRASS_DARK
        val first = floor(cam.sBottom / STRIPE).toLong()
        val last = ceil(cam.sTop / STRIPE).toLong()
        for (k in first..last) {
            if (k % 2L != 0L) continue
            canvas.drawRect(0f, cam.sy((k + 1) * STRIPE), cam.width, cam.sy(k * STRIPE), fill)
        }
        // Verge fences.
        stroke.color = FENCE
        stroke.strokeWidth = max(1f, 0.12f * cam.ppm)
        for (x in doubleArrayOf(-5.5, Scene.OPP_RIGHT + 5.5)) {
            val sx = cam.sx(x)
            canvas.drawLine(sx, 0f, sx, cam.height, stroke)
        }
    }

    /**
     * Draws one carriageway. Lateral positions are measured from the outer edge of its
     * hard shoulder; the opposite carriageway is mirrored on the far side of the central reservation.
     */
    private fun drawCarriageway(canvas: Canvas, cam: Camera, mirrored: Boolean) {
        fun x(m: Double) = if (mirrored) cam.sx(Scene.OPP_RIGHT - m) else cam.sx(m)
        val top = 0f
        val bottom = cam.height
        val hs = Road.HARD_SHOULDER
        val lw = Road.LANE_WIDTH

        fill.color = ASPHALT
        hRect(canvas, x(0.0), x(Road.WIDTH), top, bottom)
        fill.color = HARD_SHOULDER
        hRect(canvas, x(0.0), x(hs), top, bottom)

        val ppm = cam.ppm
        // Solid edge lines: nearside (next to the hard shoulder) and offside (next to the central reservation).
        fill.color = PAINT_WHITE
        val edge = 0.2
        hRect(canvas, x(hs - edge / 2), x(hs + edge / 2), top, bottom)
        val offside = hs + Road.LANES * lw
        hRect(canvas, x(offside - edge / 2), x(offside + edge / 2), top, bottom)

        // Rumble ribs on the nearside edge line.
        if (ppm > 12f) {
            val first = floor(cam.sBottom / 0.5).toLong()
            val last = ceil(cam.sTop / 0.5).toLong()
            fill.color = PAINT_WHITE
            for (k in first..last) {
                val s = k * 0.5
                if (k % 2L == 0L) hRect(canvas, x(hs - 0.25), x(hs - edge / 2), cam.sy(s + 0.15), cam.sy(s))
            }
        }

        // Dashed lane lines: 2 m marks with 7 m gaps.
        val firstDash = floor(cam.sBottom / DASH_PERIOD).toLong() - 1
        val lastDash = ceil(cam.sTop / DASH_PERIOD).toLong()
        val lineW = 0.15
        for (i in 1 until Road.LANES) {
            val m = hs + i * lw
            for (k in firstDash..lastDash) {
                val s0 = k * DASH_PERIOD
                hRect(canvas, x(m - lineW / 2), x(m + lineW / 2), cam.sy(s0 + DASH_LENGTH), cam.sy(s0))
            }
        }

        // Reflective road studs ("cat's eyes") every 18 m: red at the nearside edge,
        // white between lanes, amber next to the central reservation.
        if (ppm > 6f) {
            val firstStud = floor(cam.sBottom / STUD_SPACING).toLong() - 1
            val lastStud = ceil(cam.sTop / STUD_SPACING).toLong()
            for (k in firstStud..lastStud) {
                val s = k * STUD_SPACING + 5.5
                stud(canvas, cam, x(hs - 0.32), s, STUD_RED)
                for (i in 1 until Road.LANES) stud(canvas, cam, x(hs + i * lw), s, STUD_WHITE)
                stud(canvas, cam, x(offside + 0.32), s, STUD_AMBER)
            }
        }
    }

    private fun stud(canvas: Canvas, cam: Camera, sx: Float, s: Double, color: Int) {
        fill.color = color
        val hw = max(1f, 0.09f * cam.ppm)
        val hl = max(1.2f, 0.13f * cam.ppm)
        val sy = cam.sy(s)
        canvas.drawRect(sx - hw, sy - hl, sx + hw, sy + hl, fill)
    }

    private fun hRect(canvas: Canvas, xa: Float, xb: Float, ya: Float, yb: Float) {
        canvas.drawRect(min(xa, xb), min(ya, yb), max(xa, xb), max(ya, yb), fill)
    }

    private fun drawCentralReservation(canvas: Canvas, cam: Camera) {
        fill.color = CONCRETE
        hRect(canvas, cam.sx(Road.WIDTH), cam.sx(Scene.OPP_LEFT), 0f, cam.height)
        // Concrete step barrier.
        fill.color = BARRIER_SHADOW
        hRect(canvas, cam.sx(Scene.CR_CENTRE - 0.42), cam.sx(Scene.CR_CENTRE + 0.42), 0f, cam.height)
        fill.color = BARRIER
        hRect(canvas, cam.sx(Scene.CR_CENTRE - 0.3), cam.sx(Scene.CR_CENTRE + 0.3), 0f, cam.height)
        fill.color = BARRIER_TOP
        hRect(canvas, cam.sx(Scene.CR_CENTRE - 0.1), cam.sx(Scene.CR_CENTRE + 0.1), 0f, cam.height)
    }

    private fun drawRoadside(canvas: Canvas, cam: Camera) {
        val ppm = cam.ppm
        // Marker posts every 100 m.
        val first = floor(cam.sBottom / 100).toLong()
        val last = ceil(cam.sTop / 100).toLong()
        for (k in first..last) {
            val s = k * 100.0
            val sx = cam.sx(-0.9)
            val sy = cam.sy(s)
            val r = max(2f, 0.16f * ppm)
            fill.color = Color.WHITE
            canvas.drawRect(sx - r, sy - r, sx + r, sy + r, fill)
            fill.color = Color.BLACK
            canvas.drawRect(sx - r, sy - r * 0.3f, sx + r, sy + r * 0.3f, fill)

            if (k % 5L == 0L) {
                // Driver location sign: road number, carriageway letter and distance in km.
                val left = cam.sx(-4.9)
                val right = cam.sx(-1.6)
                val signTop = cam.sy(s + 1.0)
                val signBottom = cam.sy(s - 1.0)
                rect.set(left, signTop, right, signBottom)
                fill.color = SIGN_BLUE
                canvas.drawRoundRect(rect, 0.15f * ppm, 0.15f * ppm, fill)
                stroke.color = Color.WHITE
                stroke.strokeWidth = max(1f, 0.06f * ppm)
                rect.inset(0.12f * ppm, 0.12f * ppm)
                canvas.drawRoundRect(rect, 0.1f * ppm, 0.1f * ppm, stroke)
                if (ppm >= 10f) {
                    text.color = Color.WHITE
                    text.textSize = 0.5f * ppm
                    val cx = (left + right) / 2
                    canvas.drawText("M17", cx, signTop + 0.62f * ppm, text)
                    canvas.drawText("A", cx, signTop + 1.2f * ppm, text)
                    canvas.drawText(formatKm(s), cx, signTop + 1.78f * ppm, text)
                }
            }
        }
    }

    private fun formatKm(s: Double): String {
        val tenths = Math.floorDiv(s.toLong(), 100L)
        return "${tenths / 10}.${Math.floorMod(tenths, 10L)}"
    }

    private fun drawTrees(canvas: Canvas, cam: Camera) {
        val first = floor((cam.sBottom - 10) / TREE_SEGMENT).toLong()
        val last = ceil((cam.sTop + 10) / TREE_SEGMENT).toLong()
        for (k in first..last) {
            for (side in 0..1) {
                val h = hash(k * 2 + side)
                val count = (h % 3).toInt()
                for (i in 0 until count) {
                    val hi = hash(h + i * 7919L)
                    val offset = 7.0 + (hi % 220) / 10.0
                    val x = if (side == 0) -offset else Scene.OPP_RIGHT + offset
                    if (x < cam.xLeft - 4 || x > cam.xRight + 4) continue
                    val s = k * TREE_SEGMENT + ((hi shr 8) % 160) / 10.0
                    val r = (1.8 + ((hi shr 16) % 18) / 10.0).toFloat() * cam.ppm
                    val sx = cam.sx(x)
                    val sy = cam.sy(s)
                    fill.color = SHADOW
                    canvas.drawCircle(sx + r * 0.25f, sy + r * 0.3f, r, fill)
                    fill.color = if ((hi shr 24) % 3 == 0L) TREE_LIGHT else TREE
                    canvas.drawCircle(sx, sy, r, fill)
                    fill.color = TREE_HIGHLIGHT
                    canvas.drawCircle(sx - r * 0.28f, sy - r * 0.3f, r * 0.45f, fill)
                }
            }
        }
    }

    private fun hash(n: Long): Long {
        var x = n * -0x61c8864680b583ebL
        x = x xor (x ushr 29)
        x *= -0x4b47d5b1a6b9ec33L
        x = x xor (x ushr 32)
        return x and Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ gantries

    private fun drawGantries(canvas: Canvas, sim: Simulation, cam: Camera, blinkOn: Boolean) {
        val first = floor((cam.sBottom - 5) / Road.GANTRY_SPACING).toLong()
        val last = ceil((cam.sTop + 5) / Road.GANTRY_SPACING).toLong()
        val ppm = cam.ppm
        for (g in first..last) {
            val s = g * Road.GANTRY_SPACING
            val y = cam.sy(s)
            if (y < -3 * ppm || y > cam.height + 3 * ppm) continue
            val signal = sim.signalAt(g)

            // Shadow, beam and posts.
            fill.color = SHADOW
            canvas.drawRect(cam.sx(-1.2), y - 0.2f * ppm, cam.sx(Road.WIDTH + 1.6), y + 1.1f * ppm, fill)
            fill.color = GANTRY
            canvas.drawRect(cam.sx(-1.8), y - 0.45f * ppm, cam.sx(Road.WIDTH + 1.2), y + 0.45f * ppm, fill)
            fill.color = GANTRY_LIGHT
            canvas.drawRect(cam.sx(-1.8), y - 0.45f * ppm, cam.sx(Road.WIDTH + 1.2), y - 0.3f * ppm, fill)
            fill.color = GANTRY_POST
            for (px in doubleArrayOf(-1.5, Road.WIDTH + 0.9)) {
                canvas.drawRect(cam.sx(px - 0.4), y - 0.4f * ppm, cam.sx(px + 0.4), y + 0.4f * ppm, fill)
            }

            for (lane in 0 until Road.LANES) drawLaneSignal(canvas, cam, signal, lane, y, blinkOn)
            drawMessageSign(canvas, cam, signal, y)
        }
    }

    private fun drawLaneSignal(canvas: Canvas, cam: Camera, signal: GantrySignal, lane: Int, y: Float, blinkOn: Boolean) {
        val ppm = cam.ppm
        val cx = cam.sx(Road.laneCenter(lane.toDouble()))
        val half = 1.0f * ppm
        rect.set(cx - half, y - 0.85f * ppm, cx + half, y + 0.85f * ppm)
        fill.color = SIGNAL_BLACK
        canvas.drawRoundRect(rect, 0.12f * ppm, 0.12f * ppm, fill)
        when {
            signal.closedLane == lane -> {
                stroke.color = SIGNAL_RED
                stroke.strokeWidth = 0.2f * ppm
                val d = 0.55f * ppm
                canvas.drawLine(cx - d, y - d, cx + d, y + d, stroke)
                canvas.drawLine(cx - d, y + d, cx + d, y - d, stroke)
                // Flashing amber lanterns in alternate corners.
                fill.color = SIGNAL_AMBER
                val r = 0.1f * ppm
                val inset = 0.2f * ppm
                val (ya, yb) = if (blinkOn) rect.top to rect.bottom else rect.bottom to rect.top
                canvas.drawCircle(rect.left + inset, ya + if (blinkOn) inset else -inset, r, fill)
                canvas.drawCircle(rect.right - inset, yb + if (blinkOn) -inset else inset, r, fill)
            }
            signal.limitMph != null -> {
                stroke.color = SIGNAL_RED
                stroke.strokeWidth = 0.13f * ppm
                canvas.drawCircle(cx, y, 0.66f * ppm, stroke)
                text.color = Color.WHITE
                text.textSize = 0.62f * ppm
                canvas.drawText(signal.limitMph.toString(), cx, y + 0.22f * ppm, text)
            }
            else -> {
                // Signals off: show the unlit matrix as a faint ring.
                stroke.color = SIGNAL_UNLIT
                stroke.strokeWidth = 0.05f * ppm
                canvas.drawCircle(cx, y, 0.66f * ppm, stroke)
            }
        }
    }

    private fun drawMessageSign(canvas: Canvas, cam: Camera, signal: GantrySignal, y: Float) {
        val ppm = cam.ppm
        val left = cam.sx(-11.0)
        val right = cam.sx(-2.0)
        rect.set(left, y - 0.8f * ppm, right, y + 0.8f * ppm)
        fill.color = GANTRY_POST
        canvas.drawRect(right - 0.1f * ppm, y - 0.15f * ppm, cam.sx(-1.5), y + 0.15f * ppm, fill)
        fill.color = SIGNAL_BLACK
        canvas.drawRoundRect(rect, 0.12f * ppm, 0.12f * ppm, fill)
        val msg = signal.message ?: return
        text.color = SIGNAL_AMBER
        text.textSize = 0.75f * ppm
        val avail = rect.width() * 0.9f
        val measured = text.measureText(msg)
        if (measured > avail) text.textSize *= avail / measured
        canvas.drawText(msg, rect.centerX(), y + text.textSize * 0.36f, text)
    }

    // ------------------------------------------------------------------ vehicles

    private fun drawSimVehicle(canvas: Canvas, cam: Camera, sim: Simulation, v: Vehicle, blinkOn: Boolean) {
        val maxHeading = if (v.type.isHeavy) 4f else 10f
        val heading = Math.toDegrees(atan2(v.lateralVelocity, max(v.v, 2.0))).toFloat()
            .coerceIn(-maxHeading, maxHeading)
        val braking = if (v.isPlayer && !sim.autopilot) sim.brake else v.acc < -1.0
        drawVehicle(
            canvas, cam, v.type, v.color, v.id, v.x, v.s - v.length / 2, heading,
            braking, v.indicator, blinkOn, v.isPlayer,
        )
    }

    /**
     * Draws a vehicle centred on lateral position [x] and road position [centreS].
     * [headingDeg] is 0 for a vehicle travelling up the screen and 180 for oncoming traffic.
     */
    private fun drawVehicle(
        canvas: Canvas, cam: Camera, type: VehicleType, color: Int, variant: Int,
        x: Double, centreS: Double, headingDeg: Float,
        braking: Boolean, indicator: Int, blinkOn: Boolean, isPlayer: Boolean,
    ) {
        val l = type.length.toFloat()
        val w = type.width.toFloat()
        val ppm = cam.ppm
        canvas.save()
        canvas.translate(cam.sx(x), cam.sy(centreS))
        canvas.rotate(headingDeg)
        canvas.scale(ppm, ppm)
        // From here on, units are metres; the front of the vehicle is at y = -l/2.
        val front = -l / 2
        val back = l / 2

        // Drop shadow.
        fill.color = SHADOW
        rect.set(-w / 2 + 0.2f, front + 0.3f, w / 2 + 0.2f, back + 0.3f)
        canvas.drawRoundRect(rect, 0.4f, 0.4f, fill)

        if (braking) {
            fill.color = BRAKE_GLOW
            canvas.drawCircle(-w / 2 + 0.3f, back, 0.9f, fill)
            canvas.drawCircle(w / 2 - 0.3f, back, 0.9f, fill)
        }

        when (type) {
            VehicleType.LORRY -> drawLorry(canvas, l, w, color, variant)
            VehicleType.COACH -> drawCoach(canvas, l, w, color)
            VehicleType.VAN -> drawVan(canvas, l, w, color)
            else -> drawCar(canvas, l, w, color)
        }

        // Lights.
        fill.color = HEADLIGHT
        canvas.drawRect(-w / 2 + 0.12f, front + 0.03f, -w / 2 + 0.5f, front + 0.17f, fill)
        canvas.drawRect(w / 2 - 0.5f, front + 0.03f, w / 2 - 0.12f, front + 0.17f, fill)
        fill.color = if (braking) BRAKE_ON else BRAKE_OFF
        canvas.drawRect(-w / 2 + 0.1f, back - 0.18f, -w / 2 + 0.5f, back - 0.03f, fill)
        canvas.drawRect(w / 2 - 0.5f, back - 0.18f, w / 2 - 0.1f, back - 0.03f, fill)

        if (indicator != 0 && blinkOn) {
            fill.color = INDICATOR
            val left = indicator < 0 || indicator == 2
            val right = indicator > 0
            if (left) {
                canvas.drawCircle(-w / 2 + 0.05f, front + 0.2f, 0.2f, fill)
                canvas.drawCircle(-w / 2 + 0.05f, back - 0.2f, 0.2f, fill)
            }
            if (right) {
                canvas.drawCircle(w / 2 - 0.05f, front + 0.2f, 0.2f, fill)
                canvas.drawCircle(w / 2 - 0.05f, back - 0.2f, 0.2f, fill)
            }
        }

        if (isPlayer) {
            stroke.color = PLAYER_OUTLINE
            stroke.strokeWidth = 0.12f
            rect.set(-w / 2 - 0.15f, front - 0.15f, w / 2 + 0.15f, back + 0.15f)
            canvas.drawRoundRect(rect, 0.55f, 0.6f, stroke)
        }
        canvas.restore()
    }

    private fun drawCar(canvas: Canvas, l: Float, w: Float, color: Int) {
        val front = -l / 2
        val back = l / 2
        // Mirrors.
        fill.color = shade(color, 0.8f)
        canvas.drawRect(-w / 2 - 0.14f, front + 1.25f, -w / 2 + 0.05f, front + 1.42f, fill)
        canvas.drawRect(w / 2 - 0.05f, front + 1.25f, w / 2 + 0.14f, front + 1.42f, fill)
        // Body.
        fill.color = color
        rect.set(-w / 2, front, w / 2, back)
        canvas.drawRoundRect(rect, 0.45f, 0.6f, fill)
        // Bonnet crease highlight.
        fill.color = shade(color, 1.12f)
        rect.set(-w / 2 + 0.35f, front + 0.2f, w / 2 - 0.35f, front + 1.0f)
        canvas.drawRoundRect(rect, 0.3f, 0.3f, fill)
        // Windscreen.
        fill.color = GLASS
        trapezoid(canvas, front + 1.05f, w / 2 - 0.3f, front + 1.7f, w / 2 - 0.18f)
        // Roof.
        fill.color = shade(color, 0.92f)
        rect.set(-w / 2 + 0.2f, front + 1.7f, w / 2 - 0.2f, back - 0.95f)
        canvas.drawRoundRect(rect, 0.2f, 0.2f, fill)
        // Rear window.
        fill.color = GLASS
        trapezoid(canvas, back - 0.95f, w / 2 - 0.2f, back - 0.45f, w / 2 - 0.32f)
    }

    private fun drawVan(canvas: Canvas, l: Float, w: Float, color: Int) {
        val front = -l / 2
        val back = l / 2
        fill.color = shade(color, 0.8f)
        canvas.drawRect(-w / 2 - 0.16f, front + 0.85f, -w / 2 + 0.05f, front + 1.05f, fill)
        canvas.drawRect(w / 2 - 0.05f, front + 0.85f, w / 2 + 0.16f, front + 1.05f, fill)
        fill.color = color
        rect.set(-w / 2, front, w / 2, back)
        canvas.drawRoundRect(rect, 0.35f, 0.45f, fill)
        fill.color = GLASS
        trapezoid(canvas, front + 0.55f, w / 2 - 0.3f, front + 1.15f, w / 2 - 0.15f)
        fill.color = shade(color, 0.93f)
        rect.set(-w / 2 + 0.15f, front + 1.25f, w / 2 - 0.15f, back - 0.12f)
        canvas.drawRect(rect, fill)
        stroke.color = shade(color, 0.82f)
        stroke.strokeWidth = 0.05f
        var y = front + 1.8f
        while (y < back - 0.4f) {
            canvas.drawLine(-w / 2 + 0.25f, y, w / 2 - 0.25f, y, stroke)
            y += 0.8f
        }
    }

    private fun drawLorry(canvas: Canvas, l: Float, w: Float, color: Int, variant: Int) {
        val front = -l / 2
        val back = l / 2
        val cabEnd = front + 2.4f
        // Trailer.
        val trailer = if (variant % 3 == 0) color else TRAILER_COLORS[Math.floorMod(variant, TRAILER_COLORS.size)]
        fill.color = trailer
        canvas.drawRect(-w / 2, cabEnd + 0.35f, w / 2, back, fill)
        stroke.color = shade(trailer, 0.85f)
        stroke.strokeWidth = 0.05f
        var y = cabEnd + 1.2f
        while (y < back - 0.3f) {
            canvas.drawLine(-w / 2 + 0.1f, y, w / 2 - 0.1f, y, stroke)
            y += 1.2f
        }
        // Cab.
        fill.color = shade(color, 0.8f)
        canvas.drawRect(-w / 2 - 0.2f, front + 0.5f, -w / 2 + 0.05f, front + 0.75f, fill)
        canvas.drawRect(w / 2 - 0.05f, front + 0.5f, w / 2 + 0.2f, front + 0.75f, fill)
        fill.color = color
        rect.set(-w / 2, front, w / 2, cabEnd)
        canvas.drawRoundRect(rect, 0.3f, 0.3f, fill)
        fill.color = GLASS
        canvas.drawRect(-w / 2 + 0.15f, front + 0.12f, w / 2 - 0.15f, front + 0.5f, fill)
        fill.color = shade(color, 1.1f)
        rect.set(-w / 2 + 0.25f, front + 0.7f, w / 2 - 0.25f, cabEnd - 0.15f)
        canvas.drawRoundRect(rect, 0.2f, 0.2f, fill)
    }

    private fun drawCoach(canvas: Canvas, l: Float, w: Float, color: Int) {
        val front = -l / 2
        val back = l / 2
        fill.color = shade(color, 0.8f)
        canvas.drawRect(-w / 2 - 0.25f, front + 0.3f, -w / 2 + 0.05f, front + 0.5f, fill)
        canvas.drawRect(w / 2 - 0.05f, front + 0.3f, w / 2 + 0.25f, front + 0.5f, fill)
        fill.color = color
        rect.set(-w / 2, front, w / 2, back)
        canvas.drawRoundRect(rect, 0.4f, 0.4f, fill)
        fill.color = GLASS
        canvas.drawRect(-w / 2 + 0.12f, front + 0.12f, w / 2 - 0.12f, front + 0.8f, fill)
        fill.color = shade(color, 1.1f)
        canvas.drawRect(-w / 2 + 0.2f, front + 0.9f, w / 2 - 0.2f, back - 0.3f, fill)
        fill.color = shade(color, 0.75f)
        canvas.drawRect(-0.6f, -1.2f, 0.6f, 1.2f, fill) // air-conditioning unit
        canvas.drawRect(-0.4f, front + 2.2f, 0.4f, front + 2.9f, fill) // roof hatch
        canvas.drawRect(-0.4f, back - 2.9f, 0.4f, back - 2.2f, fill)
    }

    /** A symmetric trapezoid spanning [y1]..[y2] with half-widths [hw1] and [hw2]. */
    private fun trapezoid(canvas: Canvas, y1: Float, hw1: Float, y2: Float, hw2: Float) {
        path.rewind()
        path.moveTo(-hw1, y1)
        path.lineTo(hw1, y1)
        path.lineTo(hw2, y2)
        path.lineTo(-hw2, y2)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun shade(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        // Brighten very dark colours a little so highlights remain visible.
        return if (factor > 1f && r + g + b < 90) Color.rgb(r + 30, g + 30, b + 30) else Color.rgb(r, g, b)
    }

    companion object {
        private const val STRIPE = 12.0
        private const val DASH_PERIOD = 9.0
        private const val DASH_LENGTH = 2.0
        private const val STUD_SPACING = 18.0
        private const val TREE_SEGMENT = 16.0

        private val GRASS = Color.rgb(78, 122, 58)
        private val GRASS_DARK = Color.rgb(72, 114, 53)
        private val FENCE = Color.rgb(110, 92, 70)
        private val TREE = Color.rgb(46, 86, 40)
        private val TREE_LIGHT = Color.rgb(64, 104, 46)
        private val TREE_HIGHLIGHT = Color.argb(60, 200, 230, 150)
        private val ASPHALT = Color.rgb(58, 61, 66)
        private val HARD_SHOULDER = Color.rgb(70, 73, 77)
        private val PAINT_WHITE = Color.rgb(235, 235, 230)
        private val STUD_RED = Color.rgb(230, 40, 40)
        private val STUD_WHITE = Color.rgb(250, 250, 250)
        private val STUD_AMBER = Color.rgb(255, 170, 20)
        private val CONCRETE = Color.rgb(150, 152, 150)
        private val BARRIER = Color.rgb(196, 198, 196)
        private val BARRIER_TOP = Color.rgb(222, 224, 222)
        private val BARRIER_SHADOW = Color.rgb(110, 112, 110)
        private val SIGN_BLUE = Color.rgb(0, 82, 170)
        private val GANTRY = Color.rgb(96, 102, 110)
        private val GANTRY_LIGHT = Color.rgb(130, 136, 144)
        private val GANTRY_POST = Color.rgb(80, 85, 92)
        private val SIGNAL_BLACK = Color.rgb(16, 16, 18)
        private val SIGNAL_RED = Color.rgb(240, 40, 40)
        private val SIGNAL_AMBER = Color.rgb(255, 176, 0)
        private val SIGNAL_UNLIT = Color.rgb(48, 48, 52)
        private val SHADOW = Color.argb(70, 0, 0, 0)
        private val GLASS = Color.rgb(28, 38, 52)
        private val HEADLIGHT = Color.rgb(255, 246, 214)
        private val BRAKE_OFF = Color.rgb(150, 20, 20)
        private val BRAKE_ON = Color.rgb(255, 50, 40)
        private val BRAKE_GLOW = Color.argb(90, 255, 30, 20)
        private val INDICATOR = Color.rgb(255, 170, 0)
        private val PLAYER_OUTLINE = Color.argb(220, 255, 255, 255)
        private val TRAILER_COLORS = intArrayOf(
            Color.rgb(236, 236, 236), Color.rgb(220, 222, 226), Color.rgb(200, 204, 208),
        )
    }
}
