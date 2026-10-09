package com.johndoe6345789.motorwaysim.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.johndoe6345789.motorwaysim.sim.Road
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min

/** Heads-up display and on-screen controls, drawn in screen space from a [HudState]. */
class Hud(private val dp: Float) {

    enum class Button(val hold: Boolean) {
        PAUSE(false), AUTOPILOT(false), TRAFFIC(false), VIEW(false), INCIDENT(false),
        LEFT(false), RIGHT(false), BRAKE(true), GAS(true),
    }

    private val buttons = EnumMap<Button, RectF>(Button::class.java)
    private val panel = RectF()
    private val map = RectF()
    private var limitCx = 0f
    private var limitCy = 0f
    private var limitR = 0f
    private var width = 0f
    private var height = 0f
    private var guidanceY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
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

        panel.set(left, top, left + 150 * dp, top + 118 * dp)
        limitR = 27 * dp
        limitCx = panel.right + 10 * dp + limitR
        limitCy = top + limitR

        val menu = listOf(Button.PAUSE, Button.AUTOPILOT, Button.TRAFFIC, Button.VIEW, Button.INCIDENT)
        val bh = 40 * dp
        val gap = 6 * dp
        val rowSpace = right - (limitCx + 40 * dp + 10 * dp)
        val rowButton = min(112 * dp, (rowSpace - (menu.size - 1) * gap) / menu.size)
        val landscape = w > h && rowButton >= 80 * dp
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

        val pad = 76 * dp
        buttons[Button.LEFT] = RectF(left, bottom - pad, left + pad, bottom)
        buttons[Button.RIGHT] = RectF(left + pad + 12 * dp, bottom - pad, left + 2 * pad + 12 * dp, bottom)
        buttons[Button.GAS] = RectF(right - pad, bottom - 128 * dp, right, bottom)
        buttons[Button.BRAKE] = RectF(right - 2 * pad - 12 * dp, bottom - 100 * dp, right - pad - 12 * dp, bottom)

        val mapSize = min(130 * dp, max(0f, bottom - 150 * dp - (menuBottom + 12 * dp)))
        map.set(right - mapSize, menuBottom + 12 * dp, right, menuBottom + 12 * dp + mapSize)
        guidanceY = bottom - 150 * dp
    }

    fun buttonAt(x: Float, y: Float): Button? {
        val slop = 6 * dp
        for ((b, r) in buttons) {
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return b
        }
        return null
    }

    // ------------------------------------------------------------------ drawing

    fun draw(canvas: Canvas, s: HudState, pressed: Set<Button>) {
        if (s.flashAge in 0.0..0.3) {
            fill.color = Color.argb(((1 - s.flashAge / 0.3) * 200).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, width, height, fill)
        }
        drawSpeedPanel(canvas, s)
        drawLimitSign(canvas, s)
        if (s.gantryDistance >= 0) drawNextGantry(canvas, s)
        if (map.height() > 70 * dp) drawMap(canvas, s)
        for ((b, r) in buttons) drawButton(canvas, b, r, s, b in pressed)
        s.guidance?.let { if (!s.paused) guidance(canvas, it) }
        when {
            s.flashAge < 3.5 -> banner(canvas, "📸 " + s.flashText, WARN_BG)
            s.banner != null -> banner(canvas, s.banner, if (s.bannerWarning) INFO_BG else OK_BG)
        }
        when {
            s.paused -> drawPaused(canvas)
            s.crashed -> drawCrash(canvas, s)
            s.showHint -> drawCard(
                canvas,
                listOf(
                    "Hold GAS to speed up and BRAKE to slow down.",
                    "Let go of both and cruise control holds your speed.",
                    "◀ ▶ change lane. In lane 1 at a junction, ◀ takes the exit.",
                    "On a roundabout, ◀ takes the next exit.",
                ),
                height * 0.36f, 230,
            )
        }
    }

    private fun drawSpeedPanel(canvas: Canvas, s: HudState) {
        fill.color = PANEL
        canvas.drawRoundRect(panel, 14 * dp, 14 * dp, fill)
        val x = panel.left + 14 * dp
        text.textAlign = Paint.Align.LEFT
        text.color = if (s.speedMph > s.limitMph + 2) WARN else Color.WHITE
        text.textSize = 44 * dp
        canvas.drawText(s.speedMph.toString(), x, panel.top + 48 * dp, text)
        val numW = text.measureText(s.speedMph.toString())
        text.textSize = 13 * dp
        text.color = MUTED
        canvas.drawText("mph · ${s.mode}", x + numW + 6 * dp, panel.top + 48 * dp, text)
        text.textSize = 12.5f * dp
        text.color = Color.WHITE
        fitText(canvas, s.place, x, panel.top + 70 * dp, panel.width() - 24 * dp)
        text.color = MUTED
        canvas.drawText("${oneDecimal(s.miles)} mi · ${clock(s.time)}", x, panel.top + 88 * dp, text)
        val extra = buildString {
            if (s.flashes > 0) append(" · 📸${s.flashes}")
            if (s.crashes > 0) append(" · 💥${s.crashes}")
        }
        canvas.drawText("Traffic ${s.trafficMph} mph$extra", x, panel.top + 106 * dp, text)
    }

    private fun fitText(canvas: Canvas, str: String, x: Float, y: Float, maxW: Float) {
        val size = text.textSize
        val w = text.measureText(str)
        if (w > maxW) text.textSize = size * maxW / w
        canvas.drawText(str, x, y, text)
        text.textSize = size
    }

    private fun drawLimitSign(canvas: Canvas, s: HudState) {
        fill.color = Color.WHITE
        canvas.drawCircle(limitCx, limitCy, limitR, fill)
        if (s.variableLimit || s.limitMph != Road.NATIONAL_LIMIT_MPH) {
            stroke.color = SIGN_RED
            stroke.strokeWidth = limitR * 0.2f
            canvas.drawCircle(limitCx, limitCy, limitR * 0.9f, stroke)
            text.color = Color.BLACK
            text.textAlign = Paint.Align.CENTER
            text.textSize = limitR * 0.85f
            canvas.drawText(s.limitMph.toString(), limitCx, limitCy + limitR * 0.3f, text)
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

    /** A small repeater of the next gantry's signals and its distance. */
    private fun drawNextGantry(canvas: Canvas, s: HudState) {
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
                (s.gantryClosed shr lane) and 1 == 1 -> {
                    stroke.color = SIGN_RED
                    stroke.strokeWidth = 2 * dp
                    val d = cell * 0.3f
                    canvas.drawLine(tmp.centerX() - d, tmp.centerY() - d, tmp.centerX() + d, tmp.centerY() + d, stroke)
                    canvas.drawLine(tmp.centerX() - d, tmp.centerY() + d, tmp.centerX() + d, tmp.centerY() - d, stroke)
                }
                s.gantryLimit != null -> {
                    text.color = Color.WHITE
                    text.textSize = 8.5f * dp
                    canvas.drawText(s.gantryLimit.toString(), tmp.centerX(), tmp.centerY() + 3 * dp, text)
                }
            }
        }
        text.textAlign = Paint.Align.CENTER
        text.color = MUTED
        text.textSize = 10 * dp
        canvas.drawText("gantry ${s.gantryDistance} m", limitCx, top + cell + 13 * dp, text)
    }

    /** Heading-up mini-map of the roads and traffic around the player. */
    private fun drawMap(canvas: Canvas, s: HudState) {
        fill.color = PANEL
        canvas.drawRoundRect(map, 10 * dp, 10 * dp, fill)
        canvas.save()
        canvas.clipRect(map.left + 3 * dp, map.top + 3 * dp, map.right - 3 * dp, map.bottom - 3 * dp)
        val scale = (map.width() / 2) / HudState.MAP_RANGE.toFloat()
        val cx = map.centerX()
        val cy = map.centerY() + map.height() * 0.15f
        stroke.color = Color.argb(150, 200, 205, 215)
        stroke.strokeWidth = 3 * dp
        for (road in s.mapRoads) {
            path.rewind()
            for (i in 0 until road.size / 2) {
                val x = cx + road[2 * i] * scale
                val y = cy - road[2 * i + 1] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, stroke)
        }
        val cars = s.mapCars
        for (i in 0 until cars.size / 3) {
            val x = cx + cars[3 * i] * scale
            val y = cy - cars[3 * i + 1] * scale
            val kind = cars[3 * i + 2]
            fill.color = when (kind) {
                HudState.PLAYER -> PLAYER
                HudState.POLICE -> Color.rgb(80, 130, 255)
                HudState.RECOVERY -> Color.rgb(255, 170, 20)
                HudState.WRECK -> SIGN_RED
                HudState.HEAVY -> Color.rgb(170, 200, 255)
                else -> Color.rgb(235, 235, 235)
            }
            val r = if (kind == HudState.PLAYER) 3.6f * dp else 2.2f * dp
            canvas.drawCircle(x, y, r, fill)
        }
        canvas.restore()
    }

    private fun drawButton(canvas: Canvas, b: Button, r: RectF, s: HudState, pressed: Boolean) {
        val active = (b == Button.AUTOPILOT && s.autopilot) || (b == Button.PAUSE && s.paused)
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

        if (b == Button.LEFT || b == Button.RIGHT) {
            // Arrow pointing in the direction of travel.
            val dir = if (b == Button.LEFT) -1f else 1f
            val cx = r.centerX()
            val cy = r.centerY()
            val a = r.width() * 0.22f
            path.rewind()
            path.moveTo(cx - dir * a * 0.9f, cy - a)
            path.lineTo(cx + dir * a, cy)
            path.lineTo(cx - dir * a * 0.9f, cy + a)
            path.close()
            fill.color = Color.WHITE
            canvas.drawPath(path, fill)
            return
        }
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        val (small, label) = when (b) {
            Button.PAUSE -> null to if (s.paused) "Resume" else "Pause"
            Button.AUTOPILOT -> null to if (s.autopilot) "Autopilot ON" else "Autopilot"
            Button.TRAFFIC -> "TRAFFIC" to s.traffic
            Button.VIEW -> "VIEW" to s.view
            Button.INCIDENT -> null to "Incident"
            Button.GAS -> null to "GAS"
            Button.BRAKE -> null to "BRAKE"
            else -> null to ""
        }
        if (small != null) {
            text.textSize = 10 * dp
            text.color = MUTED
            canvas.drawText(small, r.centerX(), r.top + 15 * dp, text)
            text.textSize = 13.5f * dp
            text.color = Color.WHITE
            canvas.drawText(label, r.centerX(), r.bottom - 9 * dp, text)
        } else {
            text.textSize = if (b.hold) 15 * dp else 13.5f * dp
            canvas.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        }
    }

    private fun guidance(canvas: Canvas, message: String) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13.5f * dp
        text.color = Color.WHITE
        val maxW = width - 40 * dp
        var tw = text.measureText(message)
        if (tw > maxW) {
            text.textSize *= maxW / tw
            tw = maxW
        }
        tmp.set(width / 2 - tw / 2 - 12 * dp, guidanceY - 20 * dp, width / 2 + tw / 2 + 12 * dp, guidanceY + 9 * dp)
        fill.color = GUIDE_BG
        canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
        canvas.drawText(message, width / 2, guidanceY, text)
    }

    private fun banner(canvas: Canvas, message: String, color: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14.5f * dp
        text.color = Color.WHITE
        val maxW = width - 40 * dp
        var tw = text.measureText(message)
        if (tw > maxW) {
            text.textSize *= maxW / tw
            tw = maxW
        }
        val y = max(height * 0.2f, panel.bottom + 34 * dp)
        tmp.set(width / 2 - tw / 2 - 14 * dp, y - 22 * dp, width / 2 + tw / 2 + 14 * dp, y + 12 * dp)
        fill.color = color
        canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
        canvas.drawText(message, width / 2, y, text)
    }

    private fun drawPaused(canvas: Canvas) {
        fill.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, width, height, fill)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 34 * dp
        canvas.drawText("PAUSED", width / 2, height * 0.36f, text)
        drawCard(
            canvas,
            listOf(
                "Keyboard: ↑/W gas · ↓/S brake · ←→/A D lane or exit",
                "Space pause · O autopilot · T traffic · V view · I incident",
                "Gantries set the limit. Lorries may not use lane 4.",
            ),
            height * 0.45f, 255,
        )
    }

    private fun drawCrash(canvas: Canvas, s: HudState) {
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 34 * dp
        val y = max(height * 0.3f, panel.bottom + 70 * dp)
        canvas.drawText("CRASH!", width / 2, y, text)
        drawCard(
            canvas,
            listOf(
                "Police and a recovery truck are on their way.",
                "Watch them clear the scene, or",
                "tap anywhere to carry on in a new car.",
            ),
            y + 14 * dp, 255,
        )
    }

    private fun drawCard(canvas: Canvas, lines: List<String>, top: Float, alpha: Int) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14 * dp
        var maxW = 0f
        for (l in lines) maxW = max(maxW, text.measureText(l))
        val avail = width - 48 * dp
        if (maxW > avail) {
            text.textSize *= avail / maxW
            maxW = avail
        }
        val lineH = text.textSize * 1.6f
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
        private val OK_BG = Color.argb(220, 30, 110, 70)
        private val GUIDE_BG = Color.argb(190, 0, 70, 150)
        private val SIGN_RED = Color.rgb(214, 32, 32)
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
