package com.johndoe6345789.motorwaysim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs
import kotlin.math.sign

/**
 * The game screen: a GL surface showing the 3D motorway with the HUD drawn on top.
 * Touch, keyboard, game-controller and (optionally) tilt input are turned into driving commands.
 */
class GameView(context: Context) : FrameLayout(context), SensorEventListener {
    private val hud = Hud(resources.displayMetrics.density)
    private val hudView = HudView(context)
    private val renderer = GameRenderer { state ->
        hudView.state = state
        hudView.postInvalidateOnAnimation()
    }
    private val glView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Pointer id → the button it is pressing. */
    private val pointers = HashMap<Int, Hud.Button>()
    private val keys = HashSet<Hud.Button>()
    private var wasCrashed = false
    private var tilt = false
    private var tiltSteer = 0.0
    private var resumed = false

    init {
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hudView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        contentDescription = "Four lane motorway driving game"
    }

    fun onResume() {
        resumed = true
        glView.onResume()
        updateSensor()
    }

    fun onPause() {
        resumed = false
        renderer.backgrounded = true
        pointers.clear()
        keys.clear()
        applyControls()
        updateSensor()
        glView.onPause()
    }

    private fun updateSensor() {
        val sm = sensors ?: return
        sm.unregisterListener(this)
        tiltSteer = 0.0
        if (tilt && resumed && gravity != null) sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Gravity along the screen's horizontal axis: tilting the right-hand side down steers right.
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val screenX = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> -y
            Surface.ROTATION_180 -> -x
            Surface.ROTATION_270 -> y
            else -> x
        }
        // About 25 degrees of tilt is full lock, with a small dead zone.
        val raw = -screenX / (SensorManager.GRAVITY_EARTH * 0.42)
        tiltSteer = if (abs(raw) < 0.06) 0.0 else ((abs(raw) - 0.06) / 0.94 * sign(raw)).coerceIn(-1.0, 1.0)
        applyControls()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyControls() {
        val pressed = pressedButtons()
        renderer.throttle = Hud.Button.GAS in pressed
        renderer.brake = Hud.Button.BRAKE in pressed
        val left = Hud.Button.STEER_LEFT in pressed
        val right = Hud.Button.STEER_RIGHT in pressed
        renderer.steerTarget = when {
            left && !right -> -1.0
            right && !left -> 1.0
            tilt -> tiltSteer
            else -> 0.0
        }
        renderer.tilt = tilt
        hudView.pressed = pressed
    }

    private fun pressedButtons(): Set<Hud.Button> {
        val set = HashSet<Hud.Button>(pointers.values)
        set.addAll(keys)
        return set
    }

    private fun press(button: Hud.Button) {
        val c = when (button) {
            Hud.Button.PAUSE -> GameRenderer.Command.PAUSE
            Hud.Button.VIEW -> GameRenderer.Command.VIEW
            Hud.Button.AUTOPILOT -> GameRenderer.Command.AUTOPILOT
            Hud.Button.LIGHTS -> GameRenderer.Command.LIGHTS
            Hud.Button.IND_LEFT -> GameRenderer.Command.IND_LEFT
            Hud.Button.IND_RIGHT -> GameRenderer.Command.IND_RIGHT
            Hud.Button.RESUME -> GameRenderer.Command.RESUME
            Hud.Button.GARAGE -> GameRenderer.Command.GARAGE
            Hud.Button.TRAFFIC -> GameRenderer.Command.TRAFFIC
            Hud.Button.INCIDENT -> GameRenderer.Command.INCIDENT
            Hud.Button.PREV -> GameRenderer.Command.PREV
            Hud.Button.NEXT -> GameRenderer.Command.NEXT
            Hud.Button.DRIVE -> GameRenderer.Command.DRIVE
            Hud.Button.NEW_LICENCE -> GameRenderer.Command.NEW_LICENCE
            Hud.Button.TILT -> {
                tilt = !tilt
                updateSensor()
                applyControls()
                return
            }
            Hud.Button.STEER_LEFT, Hud.Button.STEER_RIGHT, Hud.Button.GAS, Hud.Button.BRAKE -> return
        }
        renderer.post(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = hudView.state ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val button = hud.buttonAt(event.getX(i), event.getY(i), state)
                if (button == null) {
                    if (state.crashed && state.screen == Screen.DRIVING) renderer.post(GameRenderer.Command.CONTINUE)
                } else {
                    pointers[event.getPointerId(i)] = button
                    if (!button.hold) press(button)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Let a finger slide between the two pedals, or between the steering buttons.
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val current = pointers[id] ?: continue
                    if (!current.hold) continue
                    val now = hud.buttonAt(event.getX(i), event.getY(i), state)
                    if (now != null && now.hold && now.group == current.group && now != current) pointers[id] = now
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointers.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> pointers.clear()
        }
        applyControls()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val screen = hudView.state?.screen
        if (screen == Screen.GARAGE && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.repeatCount == 0) press(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) Hud.Button.PREV else Hud.Button.NEXT)
            return true
        }
        val held = HELD_KEYS[keyCode]
        if (held != null) {
            keys += held
            applyControls()
            return true
        }
        if (event.repeatCount > 0) return keyCode in TAP_KEYS || super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> renderer.post(
                when (screen) {
                    Screen.GARAGE -> GameRenderer.Command.DRIVE
                    Screen.PAUSED -> GameRenderer.Command.RESUME
                    Screen.BANNED -> GameRenderer.Command.NEW_LICENCE
                    else -> GameRenderer.Command.CONTINUE
                },
            )
            else -> {
                val b = TAP_KEYS[keyCode] ?: return super.onKeyDown(keyCode, event)
                press(b)
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val held = HELD_KEYS[keyCode] ?: return keyCode in TAP_KEYS || super.onKeyUp(keyCode, event)
        keys -= held
        applyControls()
        return true
    }

    /** Draws the HUD; also feels the crash. */
    private inner class HudView(context: Context) : View(context) {
        @Volatile var state: HudState? = null
        var pressed: Set<Hud.Button> = emptySet()
        private val insetRect = RectF()

        override fun onDraw(canvas: Canvas) {
            val s = state ?: return
            val wi = rootWindowInsets
            if (wi == null) insetRect.set(0f, 0f, 0f, 0f) else {
                val i = wi.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetRect.set(i.left.toFloat(), i.top.toFloat(), i.right.toFloat(), i.bottom.toFloat())
            }
            hud.layout(width.toFloat(), height.toFloat(), insetRect)
            hud.draw(canvas, s, pressed)
            if (s.crashed && !wasCrashed) performHapticFeedback(HapticFeedbackConstants.REJECT)
            wasCrashed = s.crashed
        }
    }

    /** Prefers 4× multisampling for smooth edges, falling back to a plain config. */
    private class MultisampleChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val renderable = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            for (samples in intArrayOf(4, 0)) {
                val attribs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, renderable,
                    EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_NONE,
                )
                val count = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, attribs, configs, 1, count) && count[0] > 0) return configs[0]!!
            }
            throw IllegalStateException("No OpenGL ES 3 configuration available")
        }
    }

    companion object {
        /** Keys held down like the on-screen pedals and steering. */
        private val HELD_KEYS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to Hud.Button.GAS, KeyEvent.KEYCODE_W to Hud.Button.GAS, KeyEvent.KEYCODE_BUTTON_R2 to Hud.Button.GAS,
            KeyEvent.KEYCODE_DPAD_DOWN to Hud.Button.BRAKE, KeyEvent.KEYCODE_S to Hud.Button.BRAKE, KeyEvent.KEYCODE_BUTTON_L2 to Hud.Button.BRAKE,
            KeyEvent.KEYCODE_A to Hud.Button.STEER_LEFT, KeyEvent.KEYCODE_DPAD_LEFT to Hud.Button.STEER_LEFT,
            KeyEvent.KEYCODE_D to Hud.Button.STEER_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT to Hud.Button.STEER_RIGHT,
        )

        /** Keys that act once per press. The arrow keys steer while driving and browse the garage. */
        private val TAP_KEYS = mapOf(
            KeyEvent.KEYCODE_Q to Hud.Button.IND_LEFT, KeyEvent.KEYCODE_BUTTON_L1 to Hud.Button.IND_LEFT,
            KeyEvent.KEYCODE_E to Hud.Button.IND_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 to Hud.Button.IND_RIGHT,
            KeyEvent.KEYCODE_SPACE to Hud.Button.PAUSE, KeyEvent.KEYCODE_P to Hud.Button.PAUSE,
            KeyEvent.KEYCODE_BUTTON_START to Hud.Button.PAUSE, KeyEvent.KEYCODE_ESCAPE to Hud.Button.PAUSE,
            KeyEvent.KEYCODE_O to Hud.Button.AUTOPILOT, KeyEvent.KEYCODE_BUTTON_Y to Hud.Button.AUTOPILOT,
            KeyEvent.KEYCODE_T to Hud.Button.TRAFFIC, KeyEvent.KEYCODE_BUTTON_X to Hud.Button.TRAFFIC,
            KeyEvent.KEYCODE_V to Hud.Button.VIEW, KeyEvent.KEYCODE_BUTTON_SELECT to Hud.Button.VIEW,
            KeyEvent.KEYCODE_L to Hud.Button.LIGHTS, KeyEvent.KEYCODE_I to Hud.Button.INCIDENT,
            KeyEvent.KEYCODE_G to Hud.Button.GARAGE,
        )
    }
}
