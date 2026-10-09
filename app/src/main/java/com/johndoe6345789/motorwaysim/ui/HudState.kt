package com.johndoe6345789.motorwaysim.ui

import com.johndoe6345789.motorwaysim.render.ViewMode
import com.johndoe6345789.motorwaysim.sim.Beacon
import com.johndoe6345789.motorwaysim.sim.Carriageway
import com.johndoe6345789.motorwaysim.sim.Junction
import com.johndoe6345789.motorwaysim.sim.LinkKind
import com.johndoe6345789.motorwaysim.sim.MotorwayLink
import com.johndoe6345789.motorwaysim.sim.Pose
import com.johndoe6345789.motorwaysim.sim.RingLink
import com.johndoe6345789.motorwaysim.sim.Road
import com.johndoe6345789.motorwaysim.sim.Role
import com.johndoe6345789.motorwaysim.sim.Simulation
import com.johndoe6345789.motorwaysim.sim.Units
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** An immutable snapshot of what the HUD shows, made on the GL thread for the UI thread. */
class HudState(
    val speedMph: Int,
    val limitMph: Int,
    val variableLimit: Boolean,
    val place: String,
    val mode: String,
    val miles: Double,
    val time: Double,
    val trafficMph: Int,
    val flashes: Int,
    val crashes: Int,
    /** Next gantry on the motorway: distance (m), or -1 when not on the motorway. */
    val gantryDistance: Int,
    val gantryLimit: Int?,
    val gantryClosed: Int,
    val guidance: String?,
    val banner: String?,
    val bannerWarning: Boolean,
    val flashAge: Double,
    val flashText: String,
    val crashed: Boolean,
    val paused: Boolean,
    val autopilot: Boolean,
    val traffic: String,
    val view: String,
    val showHint: Boolean,
    /** Mini-map, heading-up, in metres around the player: road polylines and vehicles (x, y, kind). */
    val mapRoads: List<FloatArray>,
    val mapCars: FloatArray,
) {
    companion object {
        const val MAP_RANGE = 320.0
        const val CAR = 0f
        const val HEAVY = 1f
        const val PLAYER = 2f
        const val POLICE = 3f
        const val RECOVERY = 4f
        const val WRECK = 5f

        fun from(sim: Simulation, paused: Boolean, view: ViewMode): HudState {
            val p = sim.player
            val link = p.link
            val mw = link as? MotorwayLink
            var gantryDistance = -1
            var gantryLimit: Int? = null
            var gantryClosed = 0
            if (mw != null) {
                var g = Road.gantryIndexAt(p.s) + 1
                if (!Road.hasGantry(g)) g++
                gantryDistance = (g * Road.GANTRY_SPACING - p.s).roundToInt()
                val sig = sim.signalAt(mw.cw, g)
                gantryLimit = sig.limitMph
                gantryClosed = sig.closedLanes
            }
            val event = sim.events.lastOrNull()?.takeIf { sim.time - it.time < 6 }
            val flashAge = sim.time - sim.lastFlashTime
            return HudState(
                speedMph = Units.msToMph(p.v).roundToInt(),
                limitMph = sim.limitMphFor(p),
                variableLimit = sim.isVariableLimitFor(p),
                place = place(sim),
                mode = when {
                    sim.crashed -> "Crashed"
                    sim.autopilot -> "Autopilot"
                    sim.brake -> "Braking"
                    sim.throttle -> "Accelerating"
                    else -> "Cruise"
                },
                miles = sim.distanceTravelled / Units.METRES_PER_MILE,
                time = sim.time,
                trafficMph = Units.msToMph(sim.averageSpeed()).roundToInt(),
                flashes = sim.cameraFlashes,
                crashes = sim.crashes,
                gantryDistance = gantryDistance,
                gantryLimit = gantryLimit,
                gantryClosed = gantryClosed,
                guidance = guidance(sim),
                banner = event?.text,
                bannerWarning = event?.warning ?: false,
                flashAge = flashAge,
                flashText = "Speed camera: ${sim.lastFlashSpeedMph} mph in a ${sim.lastFlashLimitMph}",
                crashed = sim.crashed,
                paused = paused,
                autopilot = sim.autopilot,
                traffic = sim.trafficLevel.label,
                view = view.label,
                showHint = sim.time < 14 && !sim.autopilot && !sim.crashed,
                mapRoads = mapRoads(sim),
                mapCars = mapCars(sim),
            )
        }

        private fun place(sim: Simulation): String {
            val p = sim.player
            val link = p.link
            return when (link) {
                is MotorwayLink -> if (p.lane < 0) "${link.cw.label} · slip lane" else "${link.cw.label} · lane ${p.lane + 1}"
                is RingLink -> "J${link.junction!!.number} roundabout"
                else -> "J${link.junction?.number ?: ""} ${link.label}"
            }
        }

        private fun guidance(sim: Simulation): String? {
            if (sim.crashed) return null
            val p = sim.player
            val link = p.link
            when (link) {
                is MotorwayLink -> {
                    val off = Road.junctionOffset(p.s)
                    val centre = Road.nextJunctionCentre(p.s)
                    val n = Road.FIRST_JUNCTION_NUMBER + link.cw.junctionAt(centre)
                    if (p.lane == -1) {
                        return if (off in Road.MERGE_START..Road.MERGE_END) "Merge into lane 1: press ▶" else "Leaving at J$n for the roundabout"
                    }
                    val toExit = centre + Road.DIVERGE_START - p.s
                    if (Road.isDivergeZone(p.s)) {
                        return if (p.lane == 0) "Press ◀ now to exit at J$n" else "J$n exit: too late unless you're in lane 1"
                    }
                    if (toExit in 0.0..1600.0) {
                        val d = (toExit / 10).roundToInt() * 10
                        return if (p.lane == 0) "J$n in $d m: press ◀ at the exit to leave" else "J$n in $d m: move to lane 1 to leave"
                    }
                    return null
                }
                is RingLink -> {
                    val j = link.junction!!
                    val chosen = p.ringExit
                    return if (chosen != null) "Taking the exit for ${chosen.name} (▶ to stay on)"
                    else "◀ to take the next exit: ${j.nextExit(p.s).name}"
                }
                else -> return when (link.kind) {
                    LinkKind.OFF_SLIP, LinkKind.LOCAL_IN -> "Roundabout ahead: give way to traffic from the right"
                    LinkKind.ON_SLIP -> "Joining the motorway: build up speed, then merge"
                    LinkKind.LOCAL_OUT -> "A71: the road turns round ahead and leads back to the junction"
                    LinkKind.LOOP -> "Turning loop"
                    else -> null
                }
            }
        }

        private fun mapRoads(sim: Simulation): List<FloatArray> {
            val p = sim.player
            val roads = ArrayList<FloatArray>()
            val px = p.pose.x
            val py = p.pose.y
            val phi = p.pose.heading - Math.PI / 2
            val c = cos(phi)
            val s = sin(phi)
            fun add(xs: DoubleArray, ys: DoubleArray) {
                val out = FloatArray(xs.size * 2)
                for (i in xs.indices) {
                    val dx = xs[i] - px
                    val dy = ys[i] - py
                    out[2 * i] = (dx * c + dy * s).toFloat()
                    out[2 * i + 1] = (-dx * s + dy * c).toFloat()
                }
                roads += out
            }
            val r = MAP_RANGE * 1.5
            for (cw in Carriageway.entries) {
                val x = cw.worldX(Road.laneCenter(1.5))
                add(doubleArrayOf(x, x), doubleArrayOf(py - r, py + r))
            }
            val k = Road.junctionNearest(py)
            val j: Junction = sim.network.junctions[k] ?: return roads
            if (abs(j.centreY - py) > 1200) return roads
            val pose = Pose()
            for (link in j.links) {
                val n = (link.length / 8).toInt().coerceAtLeast(2)
                val xs = DoubleArray(n + 1)
                val ys = DoubleArray(n + 1)
                for (i in 0..n) {
                    link.pose(link.length * i / n, 0.0, pose)
                    xs[i] = pose.x
                    ys[i] = pose.y
                }
                add(xs, ys)
            }
            return roads
        }

        private fun mapCars(sim: Simulation): FloatArray {
            val p = sim.player
            val phi = p.pose.heading - Math.PI / 2
            val c = cos(phi)
            val s = sin(phi)
            val list = ArrayList<Float>()
            for (v in sim.vehicles) {
                val dx = v.pose.x - p.pose.x
                val dy = v.pose.y - p.pose.y
                if (abs(dx) > MAP_RANGE * 1.5 || abs(dy) > MAP_RANGE * 1.5) continue
                list += (dx * c + dy * s).toFloat()
                list += (-dx * s + dy * c).toFloat()
                list += when {
                    v === p -> PLAYER
                    v.role == Role.WRECK -> WRECK
                    v.beacon == Beacon.BLUE || v.role == Role.POLICE -> POLICE
                    v.role == Role.RECOVERY -> RECOVERY
                    v.type.isHeavy -> HEAVY
                    else -> CAR
                }
            }
            return list.toFloatArray()
        }
    }
}
