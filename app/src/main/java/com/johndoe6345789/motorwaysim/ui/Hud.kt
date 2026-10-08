package com.johndoe6345789.motorwaysim.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.Units
import com.johndoe6345789.motorwaysim.sim.VehicleType
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Heads-up display and on-screen controls, drawn in screen space. */
class Hud(private val dp: Float) {

    enum class Button(val hold: Boolean) {
        PAUSE(false), AUTOPILOT(false), TRAFFIC(false), ZOOM(false), INCIDENT(false),
        LEFT(false), RIGHT(false), BRAKE(true), GAS(true),
    }

    private val buttons = EnumMap<Button, RectF>(Button::class.java)
    private val panel = RectF()
    private val radar = RectF()
    private var limitCx = 0f
    private var limitCy = 0f
    private var limitR = 0f
    private var width = 0f
    private var height = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val tmp = RectF()
    private val path = Path()

    // ------------------------------------------------------------------ layout

    fun layout(w: Float, h: Float, insets: RectF) {
        width = w
        height = h
        val m = 10 * dp
        val left = insets.left + m
        val top = insets.top + m
        val right = w - insets.right - m
        val bottom = h - insets.bottom - m

        panel.set(left, top, left + 138 * dp, top + 118 * dp)
        limitR = 27 * dp
        limitCx = panel.right + 10 * dp + limitR
        limitCy = top + limitR

        // Menu buttons: a column in portrait, a row in landscape.
        val menu = listOf(Button.PAUSE, Button.AUTOPILOT, Button.TRAFFIC, Button.ZOOM, Button.INCIDENT)
        val bh = 40 * dp
        val gap = 6 * dp
        // In landscape the menu is a row; shrink it to fit beside the speed panel, or fall back to a column.
        val rowSpace = right - (limitCx + 38 * dp + 10 * dp)
        val rowButton = min(112 * dp, (rowSpace - (menu.size - 1) * gap) / menu.size)
        val landscape = w > h && rowButton >= 84 * dp
        val bw = if (landscape) rowButton else 112 * dp
        var menuBottom = top
        menu.forEachIndexed { i, b ->
            val r = if (landscape) {
                val x = right - (menu.size - i) * (bw + gap) + gap
                RectF(x, top, x + bw, top + bh)
            } else {
                RectF(right - bw, top + i * (bh + gap), right, top + i * (bh + gap) + bh)
            }
            buttons[b] = r
            menuBottom = max(menuBottom, r.bottom)
        }

        // Driving controls.
        val pad = 76 * dp
        buttons[Button.LEFT] = RectF(left, bottom - pad, left + pad, bottom)
        buttons[Button.RIGHT] = RectF(left + pad + 12 * dp, bottom - pad, left + 2 * pad + 12 * dp, bottom)
        buttons[Button.GAS] = RectF(right - pad, bottom - 128 * dp, right, bottom)
        buttons[Button.BRAKE] = RectF(right - 2 * pad - 12 * dp, bottom - 100 * dp, right - pad - 12 * dp, bottom)

        // Traffic radar: between the menu and the pedals on the right-hand side.
        val radarTop = menuBottom + 12 * dp
        val radarBottom = min(radarTop + h * 0.36f, bottom - 140 * dp)
        radar.set(right - 46 * dp, radarTop, right, radarBottom)
    }

    fun buttonAt(x: Float, y: Float): Button? {
        val slop = 6 * dp
        for ((b, r) in buttons) {
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return b
        }
        return null
    }

    // ------------------------------------------------------------------ drawing

    fun draw(
        canvas: Canvas, sim: Simulation, cam: Camera, paused: Boolean, zoomIndex: Int,
        realTime: Double, pressed: Set<Button>,
    ) {
        drawFlash(canvas, sim)
        drawSpeedPanel(canvas, sim)
        drawLimitSign(canvas, sim)
        drawNextGantry(canvas, sim)
        if (radar.height() > 90 * dp) drawRadar(canvas, sim, realTime)

        for ((b, r) in buttons) drawButton(canvas, b, r, sim, paused, zoomIndex, b in pressed)
        drawBanners(canvas, sim)

        when {
            sim.crashed -> drawCrash(canvas, sim)
            paused -> drawPaused(canvas)
            sim.time < 12 && !sim.autopilot -> drawHint(canvas, sim)
        }
    }

    private fun drawSpeedPanel(canvas: Canvas, sim: Simulation) {
        fill.color = PANEL
        canvas.drawRoundRect(panel, 14 * dp, 14 * dp, fill)
        val x = panel.left + 14 * dp
        val mph = Units.msToMph(sim.player.v).roundToInt()
        val limit = sim.limitMphAt(sim.player.s)
        text.textAlign = Paint.Align.LEFT
        text.color = if (mph > limit + 2) WARN else Color.WHITE
        text.textSize = 44 * dp
        canvas.drawText(mph.toString(), x, panel.top + 48 * dp, text)
        val numW = text.measureText(mph.toString())
        text.textSize = 13 * dp
        text.color = MUTED
        canvas.drawText("mph", x + numW + 6 * dp, panel.top + 48 * dp, text)

        text.textSize = 12.5f * dp
        text.color = Color.WHITE
        val mode = when {
            sim.autopilot -> "Autopilot"
            sim.brake -> "Braking"
            sim.throttle -> "Accelerating"
            else -> "Cruise"
        }
        canvas.drawText("Lane ${sim.player.lane + 1} · $mode", x, panel.top + 70 * dp, text)
        text.color = MUTED
        val miles = sim.distanceTravelled / Units.METRES_PER_MILE
        canvas.drawText("${oneDecimal(miles)} mi · ${clock(sim.time)}", x, panel.top + 88 * dp, text)
        val avg = Units.msToMph(sim.averageSpeed()).roundToInt()
        val flashes = if (sim.cameraFlashes > 0) " · ⚠${sim.cameraFlashes}" else ""
        canvas.drawText("Traffic $avg mph$flashes", x, panel.top + 106 * dp, text)
    }

    private fun drawLimitSign(canvas: Canvas, sim: Simulation) {
        val variable = sim.isVariableLimitAt(sim.player.s)
        fill.color = Color.WHITE
        canvas.drawCircle(limitCx, limitCy, limitR, fill)
        if (variable) {
            stroke.color = SIGN_RED
            stroke.strokeWidth = limitR * 0.2f
            canvas.drawCircle(limitCx, limitCy, limitR * 0.9f, stroke)
            text.color = Color.BLACK
            text.textAlign = Paint.Align.CENTER
            text.textSize = limitR * 0.85f
            canvas.drawText(sim.limitMphAt(sim.player.s).toString(), limitCx, limitCy + limitR * 0.3f, text)
        } else {
            // National speed limit: white disc with a black diagonal band.
            stroke.color = Color.BLACK
            stroke.strokeWidth = limitR * 0.08f
            canvas.drawCircle(limitCx, limitCy, limitR * 0.96f, stroke)
            canvas.save()
            canvas.rotate(-45f, limitCx, limitCy)
            fill.color = Color.BLACK
            canvas.drawRect(limitCx - limitR * 0.9f, limitCy - limitR * 0.16f, limitCx + limitR * 0.9f, limitCy + limitR * 0.16f, fill)
            canvas.restore()
        }
    }

    /** A small repeater of the next gantry's signals and distance, below the limit sign. */
    private fun drawNextGantry(canvas: Canvas, sim: Simulation) {
        val g = Road.gantryIndexAt(sim.player.s) + 1
        val dist = g * Road.GANTRY_SPACING - sim.player.s
        val signal = sim.signalAt(g)
        val cell = 15 * dp
        val left = limitCx - (Road.LANES * cell + (Road.LANES - 1) * dp) / 2
        val top = limitCy + limitR + 8 * dp
        tmp.set(limitCx - 38 * dp, top - 5 * dp, limitCx + 38 * dp, top + cell + 20 * dp)
        fill.color = PANEL
        canvas.drawRoundRect(tmp, 6 * dp, 6 * dp, fill)
        for (lane in 0 until Road.LANES) {
            val x = left + lane * (cell + dp)
            tmp.set(x, top, x + cell, top + cell)
            fill.color = Color.BLACK
            canvas.drawRect(tmp, fill)
            text.textAlign = Paint.Align.CENTER
            when {
                signal.closedLane == lane -> {
                    stroke.color = SIGN_RED
                    stroke.strokeWidth = 2 * dp
                    val d = cell * 0.3f
                    canvas.drawLine(tmp.centerX() - d, tmp.centerY() - d, tmp.centerX() + d, tmp.centerY() + d, stroke)
                    canvas.drawLine(tmp.centerX() - d, tmp.centerY() + d, tmp.centerX() + d, tmp.centerY() - d, stroke)
                }
                signal.limitMph != null -> {
                    text.color = Color.WHITE
                    text.textSize = 8.5f * dp
                    canvas.drawText(signal.limitMph.toString(), tmp.centerX(), tmp.centerY() + 3 * dp, text)
                }
            }
        }
        text.textAlign = Paint.Align.CENTER
        text.color = MUTED
        text.textSize = 10 * dp
        canvas.drawText("gantry ${dist.roundToInt()} m", limitCx, top + cell + 13 * dp, text)
    }

    private fun drawRadar(canvas: Canvas, sim: Simulation, realTime: Double) {
        fill.color = PANEL
        canvas.drawRoundRect(radar, 8 * dp, 8 * dp, fill)
        val inner = tmp.apply { set(radar); inset(5 * dp, 6 * dp) }
        val laneW = inner.width() / Road.LANES
        val span = Simulation.AHEAD + Simulation.BEHIND
        fun y(s: Double) = (inner.bottom - (s - sim.player.s + Simulation.BEHIND) / span * inner.height()).toFloat()

        stroke.color = Color.argb(70, 255, 255, 255)
        stroke.strokeWidth = dp
        for (i in 1 until Road.LANES) {
            val x = inner.left + i * laneW
            canvas.drawLine(x, inner.top, x, inner.bottom, stroke)
        }
        // Gantries.
        val first = Road.gantryIndexAt(sim.player.s - Simulation.BEHIND) + 1
        val last = Road.gantryIndexAt(sim.player.s + Simulation.AHEAD)
        for (g in first..last) {
            val gy = y(g * Road.GANTRY_SPACING)
            val sig = sim.signalAt(g)
            stroke.color = if (sig.limitMph != null || sig.closedLane >= 0) SIGNAL_AMBER else Color.argb(120, 255, 255, 255)
            canvas.drawLine(inner.left, gy, inner.right, gy, stroke)
        }
        val blink = (realTime % 0.6) < 0.3
        for (v in sim.vehicles) {
            val cx = inner.left + (v.lateralLane.toFloat() + 0.5f) * laneW
            val top = y(v.s)
            val bottom = max(top + 2 * dp, y(v.rear))
            if (bottom < inner.top || top > inner.bottom) continue
            fill.color = when {
                v.isPlayer -> PLAYER
                v.type == VehicleType.BREAKDOWN -> if (blink) SIGN_RED else Color.TRANSPARENT
                v.type.isHeavy -> Color.argb(230, 170, 200, 255)
                else -> Color.argb(210, 230, 230, 230)
            }
            val hw = if (v.isPlayer) laneW * 0.38f else laneW * 0.26f
            canvas.drawRect(cx - hw, max(top, inner.top), cx + hw, min(bottom, inner.bottom), fill)
        }
    }

    private fun drawButton(
        canvas: Canvas, b: Button, r: RectF, sim: Simulation, paused: Boolean, zoomIndex: Int, pressed: Boolean,
    ) {
        val active = (b == Button.AUTOPILOT && sim.autopilot) || (b == Button.PAUSE && paused)
        fill.color = when {
            b == Button.GAS -> if (pressed) GAS_ON else GAS_OFF
            b == Button.BRAKE -> if (pressed) BRAKE_ON else BRAKE_OFF
            pressed -> BUTTON_PRESSED
            active -> BUTTON_ACTIVE
            else -> BUTTON
        }
        val radius = 12 * dp
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = Color.argb(90, 255, 255, 255)
        stroke.strokeWidth = dp
        canvas.drawRoundRect(r, radius, radius, stroke)

        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        when (b) {
            Button.LEFT, Button.RIGHT -> {
                val dir = if (b == Button.LEFT) -1f else 1f
                val cx = r.centerX()
                val cy = r.centerY()
                val s = r.width() * 0.22f
                path.rewind()
                // Tip points in the direction of travel.
                path.moveTo(cx - dir * s * 0.9f, cy - s)
                path.lineTo(cx + dir * s, cy)
                path.lineTo(cx - dir * s * 0.9f, cy + s)
                path.close()
                fill.color = Color.WHITE
                canvas.drawPath(path, fill)
                return
            }
            else -> Unit
        }
        val label = when (b) {
            Button.PAUSE -> if (paused) "Resume" else "Pause"
            Button.AUTOPILOT -> if (sim.autopilot) "Autopilot ON" else "Autopilot"
            Button.TRAFFIC -> sim.trafficLevel.label
            Button.ZOOM -> "Zoom: ${Camera.ZOOM_NAMES[zoomIndex]}"
            Button.INCIDENT -> "Incident"
            Button.GAS -> "GAS"
            Button.BRAKE -> "BRAKE"
            else -> ""
        }
        text.textSize = if (b.hold) 15 * dp else 13.5f * dp
        if (b == Button.TRAFFIC) {
            text.textSize = 10 * dp
            text.color = MUTED
            canvas.drawText("TRAFFIC", r.centerX(), r.top + 15 * dp, text)
            text.textSize = 13.5f * dp
            text.color = Color.WHITE
            canvas.drawText(label, r.centerX(), r.bottom - 9 * dp, text)
        } else {
            canvas.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        }
    }

    private fun drawFlash(canvas: Canvas, sim: Simulation) {
        val age = sim.time - sim.lastFlashTime
        if (age in 0.0..0.3) {
            fill.color = Color.argb(((1 - age / 0.3) * 200).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, width, height, fill)
        }
    }

    private fun drawBanners(canvas: Canvas, sim: Simulation) {
        val flashAge = sim.time - sim.lastFlashTime
        val incidentAge = sim.time - sim.lastIncidentTime
        val message = when {
            flashAge < 3.5 -> "📸 Speed camera: ${sim.lastFlashSpeedMph} mph in a ${sim.lastFlashLimitMph}"
            incidentAge < 5 && sim.incidents.isNotEmpty() ->
                "⚠ Breakdown ahead in lane ${sim.incidents.last().lane + 1}"
            else -> return
        }
        banner(canvas, message, height * 0.22f, if (flashAge < 3.5) WARN_BG else INFO_BG)
    }

    private fun banner(canvas: Canvas, message: String, y: Float, color: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 15 * dp
        text.color = Color.WHITE
        val tw = min(text.measureText(message), width - 40 * dp)
        tmp.set(width / 2 - tw / 2 - 14 * dp, y - 22 * dp, width / 2 + tw / 2 + 14 * dp, y + 12 * dp)
        fill.color = color
        canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
        canvas.drawText(message, width / 2, y, text)
    }

    private fun drawHint(canvas: Canvas, sim: Simulation) {
        val alpha = ((12 - sim.time) / 2).coerceIn(0.0, 1.0)
        val lines = listOf(
            "Hold GAS to speed up, BRAKE to slow down.",
            "Let go of both and cruise control holds your speed.",
            "Arrows change lane. Keep left unless overtaking!",
        )
        drawCard(canvas, lines, height * 0.42f, (alpha * 255).toInt())
    }

    private fun drawPaused(canvas: Canvas) {
        fill.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, width, height, fill)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 34 * dp
        canvas.drawText("PAUSED", width / 2, height * 0.36f, text)
        val lines = listOf(
            "Keyboard: ↑/W gas · ↓/S brake · ←→/A D change lane",
            "Space pause · O autopilot · T traffic · Z zoom · I incident",
            "Lorries may not use lane 4. Overhead gantries set the limit.",
        )
        drawCard(canvas, lines, height * 0.45f, 255)
    }

    private fun drawCrash(canvas: Canvas, sim: Simulation) {
        fill.color = Color.argb(160, 60, 0, 0)
        canvas.drawRect(0f, 0f, width, height, fill)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 40 * dp
        canvas.drawText("CRASH!", width / 2, height * 0.36f, text)
        val miles = sim.distanceTravelled / Units.METRES_PER_MILE
        val lines = listOf(
            "You drove ${oneDecimal(miles)} miles in ${clock(sim.time)}.",
            "Speed camera flashes: ${sim.cameraFlashes}",
            "Tap anywhere to restart",
        )
        drawCard(canvas, lines, height * 0.45f, 255)
    }

    private fun drawCard(canvas: Canvas, lines: List<String>, top: Float, alpha: Int) {
        if (alpha <= 0) return
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14 * dp
        var maxW = 0f
        for (l in lines) maxW = max(maxW, text.measureText(l))
        maxW = min(maxW, width - 48 * dp)
        val lineH = 22 * dp
        tmp.set(width / 2 - maxW / 2 - 16 * dp, top, width / 2 + maxW / 2 + 16 * dp, top + lines.size * lineH + 18 * dp)
        fill.color = Color.argb(alpha * 200 / 255, 10, 12, 16)
        canvas.drawRoundRect(tmp, 12 * dp, 12 * dp, fill)
        text.color = Color.argb(alpha, 255, 255, 255)
        lines.forEachIndexed { i, l -> canvas.drawText(l, width / 2, top + 9 * dp + (i + 0.75f) * lineH, text) }
    }

    private fun oneDecimal(x: Double): String {
        val tenths = (x * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun clock(seconds: Double): String {
        val s = seconds.toLong()
        val m = s / 60
        val r = s % 60
        return "$m:${if (r < 10) "0" else ""}$r"
    }

    companion object {
        private val PANEL = Color.argb(170, 12, 14, 18)
        private val MUTED = Color.rgb(190, 196, 204)
        private val WARN = Color.rgb(255, 120, 90)
        private val WARN_BG = Color.argb(220, 170, 30, 30)
        private val INFO_BG = Color.argb(220, 180, 110, 0)
        private val SIGN_RED = Color.rgb(214, 32, 32)
        private val SIGNAL_AMBER = Color.rgb(255, 176, 0)
        private val PLAYER = Color.rgb(255, 194, 26)
        private val BUTTON = Color.argb(150, 20, 22, 28)
        private val BUTTON_PRESSED = Color.argb(200, 80, 86, 96)
        private val BUTTON_ACTIVE = Color.argb(210, 30, 120, 70)
        private val GAS_OFF = Color.argb(150, 20, 90, 50)
        private val GAS_ON = Color.argb(230, 40, 170, 90)
        private val BRAKE_OFF = Color.argb(150, 110, 25, 25)
        private val BRAKE_ON = Color.argb(230, 210, 50, 40)
    }
}
