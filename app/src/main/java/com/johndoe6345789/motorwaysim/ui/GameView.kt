package com.johndoe6345789.motorwaysim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * The game screen: a GL surface showing the 3D motorway with the HUD drawn on top.
 * Touch, keyboard and game-controller input are turned into driving commands.
 */
class GameView(context: Context) : FrameLayout(context) {
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

    /** Pointer id → the button it is pressing. */
    private val pointers = HashMap<Int, Hud.Button>()
    private var keyThrottle = false
    private var keyBrake = false
    private var wasCrashed = false

    init {
        addView(glView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hudView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        contentDescription = "Four lane motorway driving simulator"
    }

    fun onResume() {
        glView.onResume()
    }

    fun onPause() {
        renderer.backgrounded = true
        pointers.clear()
        keyThrottle = false
        keyBrake = false
        applyPedals()
        glView.onPause()
    }

    private fun applyPedals() {
        val pressed = pressedButtons()
        renderer.throttle = Hud.Button.GAS in pressed
        renderer.brake = Hud.Button.BRAKE in pressed
    }

    private fun pressedButtons(): Set<Hud.Button> {
        val set = HashSet<Hud.Button>(pointers.values)
        if (keyThrottle) set.add(Hud.Button.GAS)
        if (keyBrake) set.add(Hud.Button.BRAKE)
        return set
    }

    private fun press(button: Hud.Button) {
        val c = when (button) {
            Hud.Button.PAUSE -> GameRenderer.Command.PAUSE
            Hud.Button.AUTOPILOT -> GameRenderer.Command.AUTOPILOT
            Hud.Button.TRAFFIC -> GameRenderer.Command.TRAFFIC
            Hud.Button.VIEW -> GameRenderer.Command.VIEW
            Hud.Button.INCIDENT -> GameRenderer.Command.INCIDENT
            Hud.Button.LEFT -> GameRenderer.Command.LEFT
            Hud.Button.RIGHT -> GameRenderer.Command.RIGHT
            Hud.Button.GAS, Hud.Button.BRAKE -> return
        }
        renderer.post(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val button = hud.buttonAt(event.getX(i), event.getY(i))
                if (button == null) {
                    if (hudView.state?.crashed == true) renderer.post(GameRenderer.Command.CONTINUE)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointers.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> pointers.clear()
        }
        applyPedals()
        hudView.pressed = pressedButtons()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return keyCode in GAME_KEYS || super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_BUTTON_R2 -> keyThrottle = true
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_BUTTON_L2 -> keyBrake = true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_BUTTON_L1 -> press(Hud.Button.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_BUTTON_R1 -> press(Hud.Button.RIGHT)
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_BUTTON_START -> press(Hud.Button.PAUSE)
            KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_BUTTON_Y -> press(Hud.Button.AUTOPILOT)
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_BUTTON_X -> press(Hud.Button.TRAFFIC)
            KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_BUTTON_SELECT -> press(Hud.Button.VIEW)
            KeyEvent.KEYCODE_I -> press(Hud.Button.INCIDENT)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> renderer.post(GameRenderer.Command.CONTINUE)
            else -> return super.onKeyDown(keyCode, event)
        }
        applyPedals()
        hudView.pressed = pressedButtons()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_BUTTON_R2 -> keyThrottle = false
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_BUTTON_L2 -> keyBrake = false
            else -> return keyCode in GAME_KEYS || super.onKeyUp(keyCode, event)
        }
        applyPedals()
        hudView.pressed = pressedButtons()
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
        private val GAME_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_ENTER,
        )
    }
}
