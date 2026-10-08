package com.johndoe6345789.motorwaysim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.johndoe6345789.motorwaysim.sim.Simulation
import kotlin.math.min

/**
 * Hosts the simulation: runs it once per display frame, draws the scene and HUD,
 * and turns touch, keyboard and game-controller input into driver controls.
 */
class MotorwayView(context: Context) : View(context), Choreographer.FrameCallback {

    val sim = Simulation()
    private val scene = SceneRenderer()
    private val hud = Hud(resources.displayMetrics.density)
    private val oncoming = OncomingTraffic()

    private var running = false
    private var lastFrameNanos = 0L
    var paused = false
        private set
    private var zoomIndex = 1
    private var wasCrashed = false
    private var realTime = 0.0

    /** Pointer id → the button it is pressing. */
    private val pointers = HashMap<Int, Hud.Button>()
    private var keyThrottle = false
    private var keyBrake = false

    private val camera = Camera()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        contentDescription = "Four lane motorway driving simulator"
    }

    // ------------------------------------------------------------------ loop

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        pointers.clear()
        applyControls()
        if (!sim.crashed) paused = true
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 0.0 else (frameTimeNanos - lastFrameNanos) / 1e9
        lastFrameNanos = frameTimeNanos
        advance(dt)
        if (sim.crashed && !wasCrashed) performHapticFeedback(HapticFeedbackConstants.REJECT)
        wasCrashed = sim.crashed
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Advances the simulation and scenery by [dt] seconds of real time. */
    internal fun advance(dt: Double) {
        realTime += dt
        if (!paused) {
            sim.update(dt)
            oncoming.update(min(dt, 0.25), sim, camera)
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val insets = safeInsets()
        camera.layout(w, h, zoomIndex, sim.player.s)
        scene.draw(canvas, sim, oncoming, camera, realTime)
        hud.layout(w, h, insets)
        hud.draw(canvas, sim, camera, paused, zoomIndex, realTime, pressedButtons())
    }

    private val insetRect = RectF()

    private fun safeInsets(): RectF {
        val wi = rootWindowInsets
        if (wi == null) {
            insetRect.set(0f, 0f, 0f, 0f)
        } else {
            val i = wi.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetRect.set(i.left.toFloat(), i.top.toFloat(), i.right.toFloat(), i.bottom.toFloat())
        }
        return insetRect
    }

    private fun pressedButtons(): Set<Hud.Button> {
        val set = HashSet<Hud.Button>(pointers.values)
        if (keyThrottle) set.add(Hud.Button.GAS)
        if (keyBrake) set.add(Hud.Button.BRAKE)
        return set
    }

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val button = hud.buttonAt(event.getX(i), event.getY(i))
                if (button == null) {
                    if (sim.crashed) restart()
                } else {
                    pointers[event.getPointerId(i)] = button
                    if (!button.hold) press(button)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Let a finger slide between the two pedals.
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val current = pointers[id] ?: continue
                    if (!current.hold) continue
                    val now = hud.buttonAt(event.getX(i), event.getY(i))
                    if (now != null && now.hold && now != current) pointers[id] = now
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointers.remove(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> pointers.clear()
        }
        applyControls()
        return true
    }

    private fun applyControls() {
        val buttons = pressedButtons()
        val gas = Hud.Button.GAS in buttons
        val brake = Hud.Button.BRAKE in buttons
        if ((gas || brake) && sim.autopilot) sim.autopilot = false
        sim.throttle = gas && !paused
        sim.brake = brake && !paused
    }

    private fun press(button: Hud.Button) {
        if (paused && button != Hud.Button.PAUSE && button != Hud.Button.ZOOM) return
        when (button) {
            Hud.Button.PAUSE -> paused = !paused
            Hud.Button.AUTOPILOT -> if (!sim.crashed) sim.autopilot = !sim.autopilot
            Hud.Button.TRAFFIC -> sim.trafficLevel = sim.trafficLevel.next()
            Hud.Button.ZOOM -> zoomIndex = (zoomIndex + 1) % Camera.ZOOMS.size
            Hud.Button.INCIDENT -> sim.triggerIncident()
            Hud.Button.LEFT -> sim.steer(-1)
            Hud.Button.RIGHT -> sim.steer(1)
            Hud.Button.GAS, Hud.Button.BRAKE -> Unit
        }
    }

    private fun restart() {
        sim.reset()
        oncoming.clear()
        paused = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return isGameKey(keyCode) || super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_BUTTON_R2 -> keyThrottle = true
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_BUTTON_L2 -> keyBrake = true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_BUTTON_L1 -> press(Hud.Button.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_BUTTON_R1 -> press(Hud.Button.RIGHT)
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_BUTTON_START ->
                if (sim.crashed) restart() else press(Hud.Button.PAUSE)
            KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_BUTTON_Y -> press(Hud.Button.AUTOPILOT)
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_BUTTON_X -> press(Hud.Button.TRAFFIC)
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_BUTTON_SELECT -> press(Hud.Button.ZOOM)
            KeyEvent.KEYCODE_I -> press(Hud.Button.INCIDENT)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (sim.crashed) restart()
            else -> return super.onKeyDown(keyCode, event)
        }
        applyControls()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_BUTTON_R2 -> keyThrottle = false
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_BUTTON_L2 -> keyBrake = false
            else -> return isGameKey(keyCode) || super.onKeyUp(keyCode, event)
        }
        applyControls()
        return true
    }

    private fun isGameKey(keyCode: Int) = keyCode in GAME_KEYS

    companion object {
        private val GAME_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_ENTER,
        )
    }
}
