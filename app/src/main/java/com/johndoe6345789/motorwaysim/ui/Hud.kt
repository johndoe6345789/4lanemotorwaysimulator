package com.johndoe6345789.motorwaysim.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.johndoe6345789.motorwaysim.sim.HighwayCode
import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Units
import com.johndoe6345789.motorwaysim.sim.VehicleType
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Heads-up display, menus and on-screen controls, drawn in screen space from a [HudState]. */
class Hud(private val dp: Float) {

    /** [group] lets a held finger slide between buttons of the same group (the pedals, or the steering). */
    enum class Button(val hold: Boolean = false, val group: Int = 0) {
        // Driving.
        PAUSE, VIEW, AUTOPILOT, LIGHTS, IND_LEFT, IND_RIGHT,
        STEER_LEFT(true, 1), STEER_RIGHT(true, 1), BRAKE(true, 2), GAS(true, 2),

        // Menus.
        RESUME, GARAGE, TRAFFIC, INCIDENT, TILT, PREV, NEXT, DRIVE, NEW_LICENCE,
    }

    private val layouts = EnumMap<Screen, LinkedHashMap<Button, RectF>>(Screen::class.java)
    private val panel = RectF()
    private val licence = RectF()
    private val map = RectF()
    private val recordCard = RectF()
    private val garageCard = RectF()
    private var limitCx = 0f
    private var limitCy = 0f
    private var limitR = 0f
    private var width = 0f
    private var height = 0f
    private var guidanceY = 0f
    private var guidanceW = 0f
    private var bannerY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val tmp = RectF()
    private val path = Path()

    // ------------------------------------------------------------------ layout

    fun layout(w: Float, h: Float, insets: RectF) {
        width = w
        height = h
        for (s in Screen.entries) layouts[s] = LinkedHashMap()
        val m = 10 * dp
        val left = insets.left + m
        val top = insets.top + m
        val right = w - insets.right - m
        val bottom = h - insets.bottom - m
        val landscape = w > h

        // ---- driving
        val drive = layouts.getValue(Screen.DRIVING)
        panel.set(left, top, left + 150 * dp, top + 118 * dp)
        licence.set(left, panel.bottom + 6 * dp, panel.right, panel.bottom + 40 * dp)
        limitR = 27 * dp
        limitCx = panel.right + 10 * dp + limitR
        limitCy = top + limitR

        val menu = listOf(Button.PAUSE, Button.VIEW, Button.AUTOPILOT, Button.LIGHTS)
        val bh = 40 * dp
        val gap = 6 * dp
        val rowSpace = right - (limitCx + 40 * dp + 10 * dp)
        val rowButton = min(104 * dp, (rowSpace - (menu.size - 1) * gap) / menu.size)
        val row = landscape && rowButton >= 76 * dp
        val bw = if (row) rowButton else 100 * dp
        var menuBottom = top
        menu.forEachIndexed { i, b ->
            val r = if (row) {
                val x = right - (menu.size - i) * (bw + gap) + gap
                RectF(x, top, x + bw, top + bh)
            } else {
                RectF(right - bw, top + i * (bh + gap), right, top + i * (bh + gap) + bh)
            }
            drive[b] = r
            menuBottom = max(menuBottom, r.bottom)
        }

        val pad = 78 * dp
        drive[Button.STEER_LEFT] = RectF(left, bottom - pad, left + pad, bottom)
        drive[Button.STEER_RIGHT] = RectF(left + pad + 12 * dp, bottom - pad, left + 2 * pad + 12 * dp, bottom)
        val indTop = bottom - pad - 10 * dp - 38 * dp
        drive[Button.IND_LEFT] = RectF(left, indTop, left + pad, indTop + 38 * dp)
        drive[Button.IND_RIGHT] = RectF(left + pad + 12 * dp, indTop, left + 2 * pad + 12 * dp, indTop + 38 * dp)
        drive[Button.GAS] = RectF(right - pad, bottom - 130 * dp, right, bottom)
        drive[Button.BRAKE] = RectF(right - 2 * pad - 12 * dp, bottom - 100 * dp, right - pad - 12 * dp, bottom)

        val mapSize = min(130 * dp, max(0f, bottom - 150 * dp - (menuBottom + 12 * dp)))
        map.set(right - mapSize, menuBottom + 12 * dp, right, menuBottom + 12 * dp + mapSize)
        // Landscape: guidance sits at the bottom between the steering and the pedals; portrait: above the controls.
        val between = (right - 2 * pad - 12 * dp - 12 * dp) - (left + 2 * pad + 12 * dp + 12 * dp)
        if (landscape && between > 240 * dp) {
            guidanceY = bottom
            guidanceW = between - 24 * dp
        } else {
            guidanceY = indTop - 34 * dp
            guidanceW = min(w - 40 * dp, 560 * dp)
        }
        bannerY = max(height * 0.2f, licence.bottom + 30 * dp)

        // ---- pause menu: a column of buttons, with the Highway Code record beside or below it
        val paused = layouts.getValue(Screen.PAUSED)
        val twoColumn = right - left > 560 * dp
        val colW = min(250 * dp, right - left)
        val pauseItems = listOf(Button.RESUME, Button.GARAGE, Button.VIEW, Button.TRAFFIC, Button.TILT, Button.INCIDENT)
        val itemH = 42 * dp
        val colX = if (twoColumn) w / 2 - colW - 12 * dp else w / 2 - colW / 2
        val colTop = if (twoColumn) max(top + 56 * dp, h / 2 - (pauseItems.size * (itemH + gap)) / 2) else top + 56 * dp
        pauseItems.forEachIndexed { i, b ->
            paused[b] = RectF(colX, colTop + i * (itemH + gap), colX + colW, colTop + i * (itemH + gap) + itemH)
        }
        val colBottom = colTop + pauseItems.size * (itemH + gap)
        if (twoColumn) recordCard.set(w / 2 + 12 * dp, colTop, min(right, w / 2 + 12 * dp + 340 * dp), colBottom - gap)
        else recordCard.set(left, colBottom + 6 * dp, right, bottom)

        // ---- garage: vehicle name between arrows, specs card, and DRIVE
        val garage = layouts.getValue(Screen.GARAGE)
        val cardW = min(420 * dp, right - left)
        val driveH = 52 * dp
        val driveR = RectF(w / 2 - min(220 * dp, cardW) / 2, bottom - driveH, w / 2 + min(220 * dp, cardW) / 2, bottom)
        garage[Button.DRIVE] = driveR
        garageCard.set(w / 2 - cardW / 2, driveR.top - 10 * dp - 112 * dp, w / 2 + cardW / 2, driveR.top - 10 * dp)
        val arrow = 54 * dp
        val arrowY = garageCard.top - 10 * dp - arrow
        garage[Button.PREV] = RectF(garageCard.left, arrowY, garageCard.left + arrow, arrowY + arrow)
        garage[Button.NEXT] = RectF(garageCard.right - arrow, arrowY, garageCard.right, arrowY + arrow)
        garage[Button.TRAFFIC] = RectF(right - 120 * dp, top, right, top + bh)

        // ---- disqualified
        val banned = layouts.getValue(Screen.BANNED)
        val bw2 = min(200 * dp, (right - left - gap) / 2)
        banned[Button.NEW_LICENCE] = RectF(w / 2 - bw2 - gap / 2, bottom - driveH, w / 2 - gap / 2, bottom)
        banned[Button.GARAGE] = RectF(w / 2 + gap / 2, bottom - driveH, w / 2 + gap / 2 + bw2, bottom)
    }

    private fun visible(b: Button, s: HudState, screen: Screen = s.screen): Boolean = when (screen) {
        Screen.DRIVING -> when (b) {
            Button.LIGHTS -> s.vehicle == VehicleType.POLICE || s.vehicle == VehicleType.RECOVERY
            Button.PAUSE, Button.VIEW -> true
            Button.AUTOPILOT -> !s.crashed
            else -> !s.crashed && !s.autopilot || b == Button.GAS || b == Button.BRAKE
        }
        else -> true
    }

    fun buttonAt(x: Float, y: Float, s: HudState): Button? {
        val slop = 6 * dp
        for ((b, r) in layouts[s.screen] ?: return null) {
            if (!visible(b, s)) continue
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return b
        }
        return null
    }

    // ------------------------------------------------------------------ drawing

    fun draw(canvas: Canvas, s: HudState, pressed: Set<Button>) {
        when (s.screen) {
            Screen.GARAGE -> drawGarage(canvas, s)
            Screen.DRIVING, Screen.PAUSED -> drawDriving(canvas, s, pressed)
            Screen.BANNED -> drawBanned(canvas, s)
        }
        if (s.screen == Screen.DRIVING) return
        for ((b, r) in layouts[s.screen] ?: return) if (visible(b, s)) drawButton(canvas, b, r, s, b in pressed)
    }

    private fun drawDriving(canvas: Canvas, s: HudState, pressed: Set<Button>) {
        if (s.flashAge in 0.0..0.3) {
            fill.color = Color.argb(((1 - s.flashAge / 0.3) * 200).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, width, height, fill)
        }
        drawSpeedPanel(canvas, s)
        drawLicence(canvas, s)
        drawLimitSign(canvas, s)
        if (s.gantryDistance >= 0) drawNextGantry(canvas, s)
        if (map.height() > 70 * dp) drawMap(canvas, s)
        if (s.screen == Screen.PAUSED) {
            // The driving controls stay on screen behind the menu.
            for ((b, r) in layouts.getValue(Screen.DRIVING)) if (visible(b, s, Screen.DRIVING)) drawButton(canvas, b, r, s, false, dim = true)
            drawPaused(canvas, s)
            return
        }
        for ((b, r) in layouts.getValue(Screen.DRIVING)) if (visible(b, s)) drawButton(canvas, b, r, s, b in pressed)
        var y = bannerY
        when {
            s.flashAge < 3.5 -> y = card(canvas, s.flashText, y, WARN_BG, 14.5f)
            s.banner != null -> y = card(canvas, s.banner, y, if (s.bannerWarning) INFO_BG else OK_BG, 14.5f)
        }
        // Each booking also posts a banner; show the rule on its own once that has gone.
        if (s.banner == null && s.lastBreach != null && s.lastBreachAge < 5) card(canvas, s.lastBreach, y, BREACH_BG, 13.5f)
        if (!s.crashed) {
            var gy = guidanceY
            s.guidance?.let { gy = cardAbove(canvas, it, gy, GUIDE_BG) - 6 * dp }
            s.job?.let { cardAbove(canvas, it, gy, JOB_BG) }
        }
        when {
            s.crashed -> drawCrash(canvas)
            s.showHint -> drawLines(
                canvas,
                listOf(
                    "Hold GAS to speed up and BRAKE to slow down. Let go of both to cruise.",
                    "◀ ▶ steer. Indicate first: mirrors, signal, manoeuvre (Rule 133).",
                    "To leave, signal left and steer into the exit lane at the junction.",
                    "Break the Highway Code and you collect points. 12 and you're banned.",
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
        fitText(canvas, "mph · ${s.mode}", x + numW + 6 * dp, panel.top + 48 * dp, panel.right - 10 * dp - (x + numW + 6 * dp))
        // Dashboard indicator repeaters, flashing at about 1.5 Hz.
        if (s.indicator != 0 && (s.time * 3).toLong() % 2 == 0L) {
            val ay = panel.top + 18 * dp
            val ax = if (s.indicator < 0) panel.right - 40 * dp else panel.right - 16 * dp
            arrow(canvas, ax, ay, 8 * dp, s.indicator.toFloat(), INDICATOR)
        }
        text.textSize = 12.5f * dp
        text.color = Color.WHITE
        fitText(canvas, s.place, x, panel.top + 70 * dp, panel.width() - 24 * dp)
        text.color = MUTED
        fitText(canvas, "${s.vehicle.label} · ${oneDecimal(s.miles)} mi · ${clock(s.time)}", x, panel.top + 88 * dp, panel.width() - 24 * dp)
        val extra = buildString {
            if (s.flashes > 0) append(" · 📸${s.flashes}")
            if (s.crashes > 0) append(" · 💥${s.crashes}")
            if (s.jobs > 0) append(" · ✔${s.jobs}")
        }
        fitText(canvas, "Traffic ${s.trafficMph} mph$extra", x, panel.top + 106 * dp, panel.width() - 24 * dp)
    }

    /** Penalty points on the licence: 12 pips, filled in red as points are added. */
    private fun drawLicence(canvas: Canvas, s: HudState) {
        fill.color = PANEL
        canvas.drawRoundRect(licence, 10 * dp, 10 * dp, fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11 * dp
        text.color = MUTED
        canvas.drawText("Licence", licence.left + 10 * dp, licence.top + 14 * dp, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = if (s.points == 0) OK else WARN
        canvas.drawText(if (s.points == 0) "clean" else "${s.points} pts", licence.right - 10 * dp, licence.top + 14 * dp, text)
        val n = HighwayCode.BAN_POINTS
        val span = licence.width() - 20 * dp
        val r = min(span / n / 2 - 1.2f * dp, 5 * dp)
        for (i in 0 until n) {
            val cx = licence.left + 10 * dp + span * (i + 0.5f) / n
            fill.color = if (i < s.points) SIGN_RED else Color.argb(90, 255, 255, 255)
            canvas.drawCircle(cx, licence.bottom - 10 * dp, r, fill)
        }
    }

    private fun fitText(canvas: Canvas, str: String, x: Float, y: Float, maxW: Float, paint: Paint = text) {
        val size = paint.textSize
        val w = paint.measureText(str)
        if (w > maxW) paint.textSize = size * maxW / w
        canvas.drawText(str, x, y, paint)
        paint.textSize = size
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

    private fun drawButton(canvas: Canvas, b: Button, r: RectF, s: HudState, pressed: Boolean, dim: Boolean = false) {
        val blink = (s.time * 3).toLong() % 2 == 0L
        val active = when (b) {
            Button.AUTOPILOT -> s.autopilot
            Button.LIGHTS -> s.beaconOn
            Button.TILT -> s.tilt
            Button.IND_LEFT -> s.indicator < 0
            Button.IND_RIGHT -> s.indicator > 0
            else -> false
        }
        fill.color = when {
            b == Button.GAS -> if (pressed) GAS_ON else GAS_OFF
            b == Button.BRAKE -> if (pressed) BRAKE_ON else BRAKE_OFF
            b == Button.DRIVE || b == Button.NEW_LICENCE || b == Button.RESUME -> if (pressed) GAS_ON else GO
            pressed -> BUTTON_PRESSED
            active && b == Button.LIGHTS -> if (s.vehicle == VehicleType.POLICE) BLUE_ON else AMBER_ON
            active -> BUTTON_ACTIVE
            else -> BUTTON
        }
        if (dim) fill.color = Color.argb(Color.alpha(fill.color) / 3, Color.red(fill.color), Color.green(fill.color), Color.blue(fill.color))
        val radius = 12 * dp
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = Color.argb(if (dim) 30 else 90, 255, 255, 255)
        stroke.strokeWidth = dp
        canvas.drawRoundRect(r, radius, radius, stroke)
        val fg = if (dim) Color.argb(80, 255, 255, 255) else Color.WHITE

        when (b) {
            Button.STEER_LEFT, Button.STEER_RIGHT, Button.PREV, Button.NEXT -> {
                val dir = if (b == Button.STEER_LEFT || b == Button.PREV) -1f else 1f
                arrow(canvas, r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.24f, dir, fg)
                return
            }
            Button.IND_LEFT, Button.IND_RIGHT -> {
                // Green indicator arrows, lit while flashing.
                val dir = if (b == Button.IND_LEFT) -1f else 1f
                val lit = active && blink && !dim
                arrow(canvas, r.centerX() + dir * 18 * dp, r.centerY(), 9 * dp, dir, if (lit) INDICATOR else Color.argb(if (dim) 60 else 170, 120, 200, 130))
                text.textAlign = Paint.Align.CENTER
                text.textSize = 11 * dp
                text.color = fg
                canvas.drawText("SIGNAL", r.centerX() - dir * 12 * dp, r.centerY() + 4 * dp, text)
                return
            }
            else -> Unit
        }
        text.color = fg
        text.textAlign = Paint.Align.CENTER
        val (small, label) = when (b) {
            Button.PAUSE -> null to "Menu"
            Button.AUTOPILOT -> null to if (s.autopilot) "Autopilot ON" else "Autopilot"
            Button.LIGHTS -> null to when {
                s.vehicle == VehicleType.POLICE -> if (s.beaconOn) "Blues ON" else "Blue lights"
                else -> if (s.beaconOn) "Beacons ON" else "Beacons"
            }
            Button.TRAFFIC -> "TRAFFIC" to s.traffic
            Button.VIEW -> "VIEW" to s.view
            Button.RESUME -> null to "Resume driving"
            Button.GARAGE -> null to "Change vehicle"
            Button.TILT -> null to if (s.tilt) "Tilt steering: on" else "Tilt steering: off"
            Button.INCIDENT -> null to "Report a breakdown ahead"
            Button.DRIVE -> null to "DRIVE"
            Button.NEW_LICENCE -> null to "New licence"
            Button.GAS -> null to "GAS"
            Button.BRAKE -> null to "BRAKE"
            else -> null to ""
        }
        if (small != null) {
            text.textSize = 10 * dp
            text.color = if (dim) fg else MUTED
            canvas.drawText(small, r.centerX(), r.top + 15 * dp, text)
            text.textSize = 13.5f * dp
            text.color = fg
            fitCentered(canvas, label, r.centerX(), r.bottom - 9 * dp, r.width() - 8 * dp)
        } else {
            text.textSize = if (b.hold || b == Button.DRIVE) 16 * dp else 13.5f * dp
            fitCentered(canvas, label, r.centerX(), r.centerY() + text.textSize * 0.36f, r.width() - 10 * dp)
        }
    }

    private fun fitCentered(canvas: Canvas, str: String, x: Float, y: Float, maxW: Float) {
        text.textAlign = Paint.Align.CENTER
        fitText(canvas, str, x, y, maxW)
    }

    /** A filled triangle pointing left (dir = -1) or right (dir = 1). */
    private fun arrow(canvas: Canvas, cx: Float, cy: Float, a: Float, dir: Float, color: Int) {
        path.rewind()
        path.moveTo(cx - dir * a * 0.9f, cy - a)
        path.lineTo(cx + dir * a, cy)
        path.lineTo(cx - dir * a * 0.9f, cy + a)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    /** Splits [message] into lines no wider than [maxW]. */
    private fun wrap(message: String, maxW: Float, paint: Paint): List<String> {
        val words = message.split(' ')
        val lines = ArrayList<String>()
        var line = ""
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) <= maxW || line.isEmpty()) line = candidate else {
                lines += line
                line = w
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** A wrapped, centred message card whose first baseline is near [y]; returns its bottom. */
    private fun card(canvas: Canvas, message: String, y: Float, color: Int, size: Float): Float {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size * dp
        text.color = Color.WHITE
        val maxW = min(width - 40 * dp, 560 * dp)
        val lines = wrap(message, maxW, text)
        val lineH = text.textSize * 1.3f
        var tw = 0f
        for (l in lines) tw = max(tw, text.measureText(l))
        tmp.set(width / 2 - tw / 2 - 14 * dp, y - 22 * dp, width / 2 + tw / 2 + 14 * dp, y + (lines.size - 1) * lineH + 12 * dp)
        fill.color = color
        canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
        lines.forEachIndexed { i, l -> canvas.drawText(l, width / 2, y + i * lineH, text) }
        return tmp.bottom + 22 * dp
    }

    /** A wrapped message card that ends at [bottom]; returns its top. */
    private fun cardAbove(canvas: Canvas, message: String, bottom: Float, color: Int): Float {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13.5f * dp
        text.color = Color.WHITE
        val maxW = guidanceW
        val lines = wrap(message, maxW, text)
        val lineH = text.textSize * 1.3f
        var tw = 0f
        for (l in lines) tw = max(tw, text.measureText(l))
        val top = bottom - lines.size * lineH - 12 * dp
        tmp.set(width / 2 - tw / 2 - 12 * dp, top, width / 2 + tw / 2 + 12 * dp, bottom)
        fill.color = color
        canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
        lines.forEachIndexed { i, l -> canvas.drawText(l, width / 2, top + 6 * dp + (i + 0.78f) * lineH, text) }
        return top
    }

    private fun drawPaused(canvas: Canvas, s: HudState) {
        fill.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, width, height, fill)
        val first = layouts.getValue(Screen.PAUSED).values.first()
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 28 * dp
        canvas.drawText("PAUSED", first.centerX(), first.top - 18 * dp, text)
        drawRecord(canvas, s, recordCard, "Highway Code record")
    }

    /** The player's licence record: points and each breach with its rule. */
    private fun drawRecord(canvas: Canvas, s: HudState, r: RectF, title: String) {
        if (r.height() < 60 * dp) return
        fill.color = Color.argb(215, 10, 12, 16)
        canvas.drawRoundRect(r, 12 * dp, 12 * dp, fill)
        val x = r.left + 14 * dp
        var y = r.top + 24 * dp
        text.textAlign = Paint.Align.LEFT
        text.textSize = 15 * dp
        text.color = Color.WHITE
        canvas.drawText(title, x, y, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = if (s.points == 0) OK else WARN
        canvas.drawText("${s.points} / ${HighwayCode.BAN_POINTS} points", r.right - 14 * dp, y, text)
        y += 22 * dp
        plain.textAlign = Paint.Align.LEFT
        plain.textSize = 12.5f * dp
        plain.color = MUTED
        val lineH = plain.textSize * 1.45f
        val maxW = r.width() - 28 * dp
        val entries = if (s.breaches.isEmpty()) listOf("No offences. Keep left, keep two seconds back, and signal before you move.")
        else s.breaches.asReversed()
        for (e in entries) {
            for (line in wrap(e, maxW, plain)) {
                if (y > r.bottom - 10 * dp) return
                canvas.drawText(line, x, y, plain)
                y += lineH
            }
            y += 3 * dp
        }
    }

    private fun drawCrash(canvas: Canvas) {
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 34 * dp
        val y = max(height * 0.3f, licence.bottom + 70 * dp)
        canvas.drawText("CRASH!", width / 2, y, text)
        drawLines(
            canvas,
            listOf(
                "Police and a recovery truck are on their way.",
                "Watch them clear the scene, or",
                "tap anywhere to carry on in a new vehicle.",
            ),
            y + 14 * dp, 255,
        )
    }

    private fun drawGarage(canvas: Canvas, s: HudState) {
        val v = s.vehicle
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 24 * dp
        // The title sits left of the traffic button, or under it if the screen is narrow.
        val traffic = layouts.getValue(Screen.GARAGE).getValue(Button.TRAFFIC)
        val titleW = min(300 * dp, width - 32 * dp)
        val beside = width / 2 + titleW / 2 + 8 * dp < traffic.left
        val titleY = if (beside) traffic.bottom - 12 * dp else traffic.bottom + 42 * dp
        tmp.set(width / 2 - titleW / 2, titleY - 30 * dp, width / 2 + titleW / 2, titleY + 12 * dp)
        fill.color = PANEL
        canvas.drawRoundRect(tmp, 12 * dp, 12 * dp, fill)
        fitCentered(canvas, "M17 Motorway Simulator", width / 2, titleY, titleW - 20 * dp)

        val prev = layouts.getValue(Screen.GARAGE).getValue(Button.PREV)
        val nameBox = RectF(prev.right + 8 * dp, prev.top, garageCard.right - prev.width() - 8 * dp, prev.bottom)
        fill.color = PANEL
        canvas.drawRoundRect(nameBox, 12 * dp, 12 * dp, fill)
        text.textSize = 20 * dp
        text.color = Color.WHITE
        fitCentered(canvas, v.label, nameBox.centerX(), nameBox.centerY() - 1 * dp, nameBox.width() - 16 * dp)
        text.textSize = 10.5f * dp
        text.color = MUTED
        canvas.drawText("${v.ordinal + 1} of ${VehicleType.entries.size} · choose your vehicle", nameBox.centerX(), nameBox.bottom - 7 * dp, text)

        fill.color = Color.argb(215, 10, 12, 16)
        canvas.drawRoundRect(garageCard, 12 * dp, 12 * dp, fill)
        val x = garageCard.left + 14 * dp
        val maxW = garageCard.width() - 28 * dp
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12.5f * dp
        text.color = PLAYER
        val top = Units.msToMph(v.maxSpeed).roundToInt()
        fitText(
            canvas,
            "${oneDecimal(v.length)} m long · top speed $top mph · motorway limit ${v.motorwayLimitMph} mph" +
                if (v.rightLaneBanned) " · no lane 4" else "",
            x, garageCard.top + 22 * dp, maxW,
        )
        plain.textAlign = Paint.Align.LEFT
        plain.textSize = 12.5f * dp
        plain.color = Color.WHITE
        var y = garageCard.top + 44 * dp
        for (line in wrap(describe(v), maxW, plain)) {
            if (y > garageCard.bottom - 8 * dp) break
            canvas.drawText(line, x, y, plain)
            y += plain.textSize * 1.4f
        }
    }

    private fun describe(v: VehicleType): String = when (v) {
        VehicleType.CAR -> "An everyday hatchback. 70 mph on the motorway and free to use all four lanes, but keep left unless overtaking (Rule 264)."
        VehicleType.SPORTS -> "Quick off the mark and sharp to steer. The limit is still 70 mph, and the gantry cameras are watching (Rule 261)."
        VehicleType.VAN -> "A light van: 70 mph on the motorway, but slow to pick up speed and taller in the wind. Leave a two-second gap (Rule 126)."
        VehicleType.COACH -> "Over 12 metres long: limited to 60 mph on the motorway (Rule 124) and banned from the right-hand lane (Rule 265)."
        VehicleType.LORRY -> "An articulated HGV over 7.5 tonnes: 60 mph (Rule 124), no lane 4 (Rule 265), and it needs a long way to stop."
        VehicleType.POLICE -> "Answer calls to breakdowns: switch on the blue lights and stop behind the vehicle to protect it. Others must let you through (Rule 219)."
        VehicleType.RECOVERY -> "Clear breakdowns and crashes: pull in ahead of the vehicle, then reverse up and winch it aboard. Amber beacons, 60 mph, no lane 4."
    }

    private fun drawBanned(canvas: Canvas, s: HudState) {
        fill.color = Color.argb(200, 30, 0, 0)
        canvas.drawRect(0f, 0f, width, height, fill)
        val buttonTop = layouts.getValue(Screen.BANNED).getValue(Button.NEW_LICENCE).top
        val m = 16 * dp
        val r = RectF(max(m, width / 2 - 260 * dp), height * 0.08f + 80 * dp, min(width - m, width / 2 + 260 * dp), buttonTop - 12 * dp)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = 32 * dp
        canvas.drawText("DISQUALIFIED", width / 2, height * 0.08f + 34 * dp, text)
        text.textSize = 13.5f * dp
        text.color = MUTED
        fitCentered(canvas, "${s.points} penalty points: you've been banned from driving.", width / 2, height * 0.08f + 60 * dp, width - 2 * m)
        drawRecord(canvas, s, r, "Your offences")
    }

    private fun drawLines(canvas: Canvas, lines: List<String>, top: Float, alpha: Int) {
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
        val tenths = (x * 10).roundToInt()
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
        private val OK = Color.rgb(120, 220, 140)
        private val WARN_BG = Color.argb(220, 170, 30, 30)
        private val BREACH_BG = Color.argb(225, 140, 20, 40)
        private val INFO_BG = Color.argb(220, 180, 110, 0)
        private val OK_BG = Color.argb(220, 30, 110, 70)
        private val GUIDE_BG = Color.argb(190, 0, 70, 150)
        private val JOB_BG = Color.argb(200, 60, 40, 140)
        private val SIGN_RED = Color.rgb(214, 32, 32)
        private val PLAYER = Color.rgb(255, 194, 26)
        private val INDICATOR = Color.rgb(60, 230, 90)
        private val BUTTON = Color.argb(150, 20, 22, 28)
        private val BUTTON_PRESSED = Color.argb(200, 80, 86, 96)
        private val BUTTON_ACTIVE = Color.argb(210, 30, 120, 70)
        private val BLUE_ON = Color.argb(230, 30, 70, 220)
        private val AMBER_ON = Color.argb(230, 220, 130, 0)
        private val GO = Color.argb(220, 30, 130, 70)
        private val GAS_OFF = Color.argb(150, 20, 90, 50)
        private val GAS_ON = Color.argb(230, 40, 170, 90)
        private val BRAKE_OFF = Color.argb(150, 110, 25, 25)
        private val BRAKE_ON = Color.argb(230, 210, 50, 40)
    }
}
