package com.johndoe6345789.motorwaysim.ui

import android.opengl.GLSurfaceView
import com.johndoe6345789.motorwaysim.render.Frame
import com.johndoe6345789.motorwaysim.render.GlRenderer
import com.johndoe6345789.motorwaysim.render.Scene3D
import com.johndoe6345789.motorwaysim.render.SignAtlas
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.VehicleType
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Runs on the GL thread: steps the simulation once per frame, renders it, and hands a
 * [HudState] snapshot to the UI thread. Input arrives through [post] and the volatile controls.
 *
 * Screens: the garage (pick a vehicle; it turns slowly on the hard shoulder while traffic passes),
 * driving, the pause menu, and disqualification after 12 penalty points.
 */
class GameRenderer(private val onHud: (HudState) -> Unit) : GLSurfaceView.Renderer {
    enum class Command {
        PAUSE, RESUME, GARAGE, PREV, NEXT, DRIVE, NEW_LICENCE, AUTOPILOT, TRAFFIC, VIEW, INCIDENT,
        CONTINUE, IND_LEFT, IND_RIGHT, LIGHTS,
    }

    private val sim = Simulation()
    private val atlas = SignAtlas()
    private val scene = Scene3D(atlas)
    private val gl = GlRenderer(atlas)
    private val frame = Frame()
    private val commands = ConcurrentLinkedQueue<Command>()
    private var width = 1
    private var height = 1
    private var lastNanos = 0L
    private var realTime = 0.0
    private var driveTime = 0.0
    private var wasPedal = false
    private var screen = Screen.GARAGE
    private var garageIndex = 0

    @Volatile var throttle = false
    @Volatile var brake = false

    /** Where the player wants the wheel, -1 (left) to 1 (right); the wheel turns towards it. */
    @Volatile var steerTarget = 0.0

    /** Shown in the HUD; the sensor itself is handled by the view. */
    @Volatile var tilt = false

    /** Set by the UI when the app goes to the background. */
    @Volatile var backgrounded = false

    init {
        enterGarage()
    }

    fun post(c: Command) {
        commands.add(c)
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        gl.init(scene.cachedMeshes())
        lastNanos = 0L
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else min((now - lastNanos) / 1e9, 0.1)
        lastNanos = now
        if (backgrounded && screen == Screen.DRIVING) screen = Screen.PAUSED
        while (true) handle(commands.poll() ?: break)

        val driving = screen == Screen.DRIVING
        val pedal = throttle || brake
        if (driving && pedal && !wasPedal && sim.autopilot && !sim.crashed) sim.autopilot = false
        wasPedal = pedal
        sim.throttle = throttle && driving
        sim.brake = brake && driving
        sim.steering = if (driving) turnWheel(sim.steering, steerTarget, dt) else 0.0
        val running = screen == Screen.DRIVING || screen == Screen.GARAGE
        if (running) {
            realTime += dt
            if (driving) driveTime += dt
            // The crash scene plays out a little faster.
            sim.update(if (sim.crashed) dt * 2 else dt)
        }
        if (driving && sim.conduct.banned) {
            screen = Screen.BANNED
            sim.autopilot = true
        }
        scene.build(sim, frame, width, height, if (running) dt else 0.0, realTime)
        gl.draw(frame, width, height, scene.evicted)
        onHud(HudState.from(sim, screen, scene.view, tilt, driveTime))
    }

    /** The wheel turns at a finite rate, and centres faster than it winds on. */
    private fun turnWheel(current: Double, target: Double, dt: Double): Double {
        val rate = if (abs(target) < abs(current) || sign(target) != sign(current)) 4.0 else 2.2
        val d = target - current
        return if (abs(d) <= rate * dt) target else current + sign(d) * rate * dt
    }

    private fun enterGarage() {
        screen = Screen.GARAGE
        sim.autopilot = true
        sim.reset(VehicleType.entries[garageIndex])
        scene.showroom = true
    }

    private fun startDriving(fresh: Boolean) {
        if (fresh) {
            sim.autopilot = false
            sim.reset(VehicleType.entries[garageIndex])
        }
        sim.autopilot = false
        scene.showroom = false
        screen = Screen.DRIVING
        driveTime = 0.0
    }

    private fun handle(c: Command) {
        when (screen) {
            Screen.GARAGE -> when (c) {
                Command.PREV, Command.NEXT -> {
                    val n = VehicleType.entries.size
                    garageIndex = (garageIndex + if (c == Command.NEXT) 1 else n - 1) % n
                    enterGarage()
                }
                Command.DRIVE, Command.RESUME -> startDriving(fresh = true)
                Command.TRAFFIC -> sim.trafficLevel = sim.trafficLevel.next()
                else -> Unit
            }
            Screen.PAUSED -> when (c) {
                Command.PAUSE, Command.RESUME -> {
                    screen = Screen.DRIVING
                    backgrounded = false
                }
                Command.GARAGE -> enterGarage()
                Command.TRAFFIC -> sim.trafficLevel = sim.trafficLevel.next()
                Command.VIEW -> scene.view = scene.view.next()
                Command.INCIDENT -> {
                    sim.triggerIncident()
                    screen = Screen.DRIVING
                }
                else -> Unit
            }
            Screen.BANNED -> when (c) {
                Command.NEW_LICENCE, Command.DRIVE, Command.RESUME -> startDriving(fresh = true)
                Command.GARAGE -> enterGarage()
                else -> Unit
            }
            Screen.DRIVING -> when (c) {
                Command.PAUSE -> screen = Screen.PAUSED
                Command.GARAGE -> enterGarage()
                Command.AUTOPILOT -> if (!sim.crashed) sim.autopilot = !sim.autopilot
                Command.TRAFFIC -> sim.trafficLevel = sim.trafficLevel.next()
                Command.VIEW -> scene.view = scene.view.next()
                Command.INCIDENT -> sim.triggerIncident()
                Command.CONTINUE -> if (sim.crashed) sim.continueAfterCrash()
                Command.IND_LEFT -> if (!sim.autopilot) sim.toggleIndicator(-1)
                Command.IND_RIGHT -> if (!sim.autopilot) sim.toggleIndicator(1)
                Command.LIGHTS -> sim.toggleBeacon()
                else -> Unit
            }
        }
    }
}
