package com.johndoe6345789.motorwaysim.ui

import android.opengl.GLSurfaceView
import com.johndoe6345789.motorwaysim.render.Frame
import com.johndoe6345789.motorwaysim.render.GlRenderer
import com.johndoe6345789.motorwaysim.render.Scene3D
import com.johndoe6345789.motorwaysim.render.SignAtlas
import com.johndoe6345789.motorwaysim.sim.Simulation
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/**
 * Runs on the GL thread: steps the simulation once per frame, renders it, and hands a
 * [HudState] snapshot to the UI thread. Input arrives through [post] and the pedal flags.
 */
class GameRenderer(private val onHud: (HudState) -> Unit) : GLSurfaceView.Renderer {
    enum class Command { LEFT, RIGHT, PAUSE, AUTOPILOT, TRAFFIC, VIEW, INCIDENT, CONTINUE }

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
    private var wasPedal = false
    private var paused = false

    @Volatile var throttle = false
    @Volatile var brake = false

    /** Set by the UI when the app goes to the background. */
    @Volatile var backgrounded = false

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
        if (backgrounded) paused = true
        while (true) handle(commands.poll() ?: break)

        val pedal = throttle || brake
        if (pedal && !wasPedal && sim.autopilot) sim.autopilot = false
        wasPedal = pedal
        sim.throttle = throttle && !paused
        sim.brake = brake && !paused
        if (!paused) {
            realTime += dt
            // The crash scene plays out a little faster.
            sim.update(if (sim.crashed) dt * 2 else dt)
        }
        scene.build(sim, frame, width, height, if (paused) 0.0 else dt, realTime)
        gl.draw(frame, width, height, scene.evicted)
        onHud(HudState.from(sim, paused, scene.view))
    }

    private fun handle(c: Command) {
        if (paused && c != Command.PAUSE && c != Command.VIEW) return
        when (c) {
            Command.LEFT -> sim.steer(-1)
            Command.RIGHT -> sim.steer(1)
            Command.PAUSE -> {
                paused = !paused
                backgrounded = false
            }
            Command.AUTOPILOT -> if (!sim.crashed) sim.autopilot = !sim.autopilot
            Command.TRAFFIC -> sim.trafficLevel = sim.trafficLevel.next()
            Command.VIEW -> scene.view = scene.view.next()
            Command.INCIDENT -> sim.triggerIncident()
            Command.CONTINUE -> sim.continueAfterCrash()
        }
    }
}
