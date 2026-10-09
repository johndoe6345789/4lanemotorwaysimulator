package com.johndoe6345789.motorwaysim.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * A texture atlas of sign faces drawn on demand with the Android canvas. Regions are
 * packed into shelves; [version] changes whenever the bitmap needs re-uploading.
 */
class SignAtlas(val size: Int = 2048) {
    val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    var version = 0
        private set
    private val regions = HashMap<String, FloatArray>()
    private var shelfX = 0
    private var shelfY = 0
    private var shelfH = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val rect = RectF()
    private val path = Path()

    /** Texture coordinates (u0, v0, u1, v1) of the sign [key], drawing it if needed. */
    fun uv(key: String): FloatArray = regions.getOrPut(key) { draw(key) }

    private fun allocate(w: Int, h: Int): IntArray {
        if (shelfX + w > size) {
            shelfX = 0
            shelfY += shelfH + 2
            shelfH = 0
        }
        if (shelfY + h > size) {
            // Full: start again (old regions get overwritten and redrawn when next used).
            bitmap.eraseColor(Color.TRANSPARENT)
            regions.clear()
            shelfX = 0; shelfY = 0; shelfH = 0
        }
        val r = intArrayOf(shelfX, shelfY, w, h)
        shelfX += w + 2
        shelfH = maxOf(shelfH, h)
        return r
    }

    private fun draw(key: String): FloatArray {
        val (w, h) = when {
            key.startsWith("lim") || key == "X" || key == "off" -> 128 to 112
            key.startsWith("vms") -> 256 to 112
            key.startsWith("adv") -> 512 to 232
            key.startsWith("cd") -> 48 to 192
            key.startsWith("dls") -> 96 to 96
            key.startsWith("exit") -> 256 to 112
            else -> 64 to 64
        }
        val (x, y) = allocate(w, h)
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        canvas.clipRect(0, 0, w, h)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val fw = w.toFloat()
        val fh = h.toFloat()
        when {
            key.startsWith("lim") -> signal(fw, fh) { speed(fw, fh, key.removePrefix("lim")) }
            key == "X" -> signal(fw, fh) { redX(fw, fh) }
            key == "off" -> signal(fw, fh) {
                ring(fw / 2, fh / 2, fh * 0.36f, Color.rgb(40, 40, 44), fh * 0.05f)
            }
            key.startsWith("vms") -> vms(fw, fh, key.removePrefix("vms_"))
            key.startsWith("adv") -> key.split("_").let { advance(fw, fh, it[1], it[2]) }
            key.startsWith("cd") -> countdown(fw, fh, key.removePrefix("cd").toInt())
            key.startsWith("dls") -> driverLocation(fw, fh, key.removePrefix("dls_"))
            key.startsWith("exit") -> exitSign(fw, fh, key.removePrefix("exit_"))
        }
        canvas.restore()
        version++
        // Inset half a texel to avoid bleeding.
        return floatArrayOf((x + 0.5f) / size, (y + 0.5f) / size, (x + w - 0.5f) / size, (y + h - 0.5f) / size)
    }

    private inline fun signal(w: Float, h: Float, content: () -> Unit) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 12, 14)
        canvas.drawRect(0f, 0f, w, h, paint)
        content()
    }

    private fun ring(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, maxWidth: Float = 0f) {
        paint.style = Paint.Style.FILL
        paint.typeface = bold
        paint.textAlign = align
        paint.color = color
        paint.textSize = size
        if (maxWidth > 0) {
            val m = paint.measureText(s)
            if (m > maxWidth) paint.textSize = size * maxWidth / m
        }
        canvas.drawText(s, x, y + paint.textSize * 0.36f, paint)
    }

    /** Smart motorway mandatory speed limit: red ring, white figures, black background. */
    private fun speed(w: Float, h: Float, mph: String) {
        ring(w / 2, h / 2, h * 0.4f, Color.rgb(235, 30, 30), h * 0.09f)
        text(mph, w / 2, h / 2, h * 0.42f, Color.WHITE)
    }

    private fun redX(w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h * 0.13f
        paint.color = Color.rgb(240, 30, 30)
        val d = h * 0.3f
        canvas.drawLine(w / 2 - d, h / 2 - d, w / 2 + d, h / 2 + d, paint)
        canvas.drawLine(w / 2 - d, h / 2 + d, w / 2 + d, h / 2 - d, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 176, 0)
        val r = h * 0.07f
        canvas.drawCircle(r * 1.8f, r * 1.8f, r, paint)
        canvas.drawCircle(w - r * 1.8f, h - r * 1.8f, r, paint)
    }

    /** Variable message sign: amber text on black. */
    private fun vms(w: Float, h: Float, message: String) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 12, 14)
        canvas.drawRect(0f, 0f, w, h, paint)
        if (message.isEmpty()) return
        val words = message.split(" ")
        if (words.size > 1) {
            text(words[0], w / 2, h * 0.32f, h * 0.32f, Color.rgb(255, 176, 0), maxWidth = w * 0.9f)
            text(words.drop(1).joinToString(" "), w / 2, h * 0.7f, h * 0.32f, Color.rgb(255, 176, 0), maxWidth = w * 0.9f)
        } else {
            text(message, w / 2, h / 2, h * 0.36f, Color.rgb(255, 176, 0), maxWidth = w * 0.9f)
        }
    }

    private fun blueSign(w: Float, h: Float, color: Int = MOTORWAY_BLUE) {
        paint.style = Paint.Style.FILL
        paint.color = color
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h * 0.06f, h * 0.06f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.025f
        paint.color = Color.WHITE
        rect.inset(h * 0.035f, h * 0.035f)
        canvas.drawRoundRect(rect, h * 0.05f, h * 0.05f, paint)
        paint.style = Paint.Style.FILL
    }

    /** Advance direction sign: junction number, the A-road on a green panel, distance and exit arrow. */
    private fun advance(w: Float, h: Float, number: String, carriageway: String) {
        // Leaving here lets you turn back onto the other carriageway.
        val other = if (carriageway == "NORTH") "M17 South" else "M17 North"
        blueSign(w, h)
        // Junction number: white on a black box.
        paint.color = Color.BLACK
        rect.set(w * 0.05f, h * 0.1f, w * 0.22f, h * 0.36f)
        canvas.drawRect(rect, paint)
        text(number, rect.centerX(), rect.centerY(), h * 0.2f, Color.WHITE)
        // Primary route panel.
        paint.color = PRIMARY_GREEN
        rect.set(w * 0.3f, h * 0.12f, w * 0.62f, h * 0.42f)
        canvas.drawRect(rect, paint)
        text("A71", rect.centerX(), rect.centerY(), h * 0.22f, Color.rgb(255, 210, 0))
        text(other, w * 0.46f, h * 0.6f, h * 0.15f, Color.WHITE)
        text("Local traffic", w * 0.46f, h * 0.8f, h * 0.13f, Color.WHITE)
        text("½ m", w * 0.84f, h * 0.78f, h * 0.17f, Color.WHITE)
        // Arrow up and to the left (exit on the left).
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.06f
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = Color.WHITE
        canvas.drawLine(w * 0.84f, h * 0.6f, w * 0.84f, h * 0.32f, paint)
        canvas.drawLine(w * 0.84f, h * 0.32f, w * 0.76f, h * 0.2f, paint)
        paint.style = Paint.Style.FILL
        path.rewind()
        path.moveTo(w * 0.72f, h * 0.12f)
        path.lineTo(w * 0.81f, h * 0.15f)
        path.lineTo(w * 0.74f, h * 0.24f)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** 300/200/100 yard countdown marker: white diagonal bars on blue. */
    private fun countdown(w: Float, h: Float, bars: Int) {
        paint.style = Paint.Style.FILL
        paint.color = MOTORWAY_BLUE
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.WHITE
        for (i in 0 until bars) {
            val y = h * (0.2f + i * 0.25f)
            path.rewind()
            path.moveTo(w * 0.1f, y + h * 0.1f)
            path.lineTo(w * 0.9f, y)
            path.lineTo(w * 0.9f, y + h * 0.08f)
            path.lineTo(w * 0.1f, y + h * 0.18f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun driverLocation(w: Float, h: Float, letter: String) {
        blueSign(w, h)
        text("M17", w / 2, h * 0.3f, h * 0.24f, Color.WHITE)
        text(letter, w / 2, h * 0.66f, h * 0.3f, Color.WHITE)
    }

    private fun exitSign(w: Float, h: Float, name: String) {
        val motorway = name.startsWith("M")
        blueSign(w, h, if (motorway) MOTORWAY_BLUE else PRIMARY_GREEN)
        val parts = name.split(" ")
        val road = parts[0]
        val dir = parts.drop(1).joinToString(" ")
        text(road, w * 0.3f, h * 0.5f, h * 0.42f, if (motorway) Color.WHITE else Color.rgb(255, 210, 0), maxWidth = w * 0.4f)
        text(dir, w * 0.72f, h * 0.5f, h * 0.3f, Color.WHITE, maxWidth = w * 0.4f)
    }

    companion object {
        val MOTORWAY_BLUE = Color.rgb(0, 82, 160)
        val PRIMARY_GREEN = Color.rgb(0, 112, 60)

        fun vmsKey(message: String?) = "vms_" + (message ?: "")
        fun signalKey(limit: Int?, closed: Boolean) = when {
            closed -> "X"
            limit != null -> "lim$limit"
            else -> "off"
        }
    }
}
