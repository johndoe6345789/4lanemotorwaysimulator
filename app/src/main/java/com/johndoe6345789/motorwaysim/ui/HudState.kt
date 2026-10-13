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
import com.johndoe6345789.motorwaysim.sim.VehicleType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which screen is showing. */
enum class Screen { GARAGE, DRIVING, PAUSED, BANNED }

/** An immutable snapshot of what the HUD shows, made on the GL thread for the UI thread. */
class HudState(
    val screen: Screen,
    val vehicle: VehicleType,
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
    val points: Int,
    val lastBreach: String?,
    val lastBreachAge: Double,
    val breaches: List<String>,
    val jobs: Int,
    val job: String?,
    val indicator: Int,
    val beaconOn: Boolean,
    val tilt: Boolean,
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

        fun from(sim: Simulation, screen: Screen, view: ViewMode, tilt: Boolean, driveTime: Double): HudState {
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
            val last = sim.conduct.breaches.lastOrNull()
            return HudState(
                screen = screen,
                vehicle = p.type,
                speedMph = Units.msToMph(p.v).roundToInt(),
                limitMph = sim.playerLimitMph(),
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
                points = sim.conduct.points,
                lastBreach = last?.let { "Rule ${it.offence.rules}: ${it.offence.title}" + if (it.offence.points > 0) " +${it.offence.points}" else "" },
                lastBreachAge = last?.let { sim.time - it.time } ?: 1e9,
                breaches = sim.conduct.breaches.map {
                    "Rule ${it.offence.rules}: ${it.offence.title}" + if (it.offence.points > 0) " (${it.offence.points} pts)" else ""
                },
                jobs = sim.jobsDone,
                job = job(sim),
                indicator = p.indicator,
                beaconOn = p.beacon != Beacon.NONE,
                tilt = tilt,
                gantryDistance = gantryDistance,
                gantryLimit = gantryLimit,
                gantryClosed = gantryClosed,
                guidance = guidance(sim),
                banner = event?.text,
                bannerWarning = event?.warning ?: false,
                flashAge = flashAge,
                flashText = "Speed camera: ${sim.lastFlashSpeedMph} mph in a ${sim.lastFlashLimitMph}",
                crashed = sim.crashed,
                autopilot = sim.autopilot,
                traffic = sim.trafficLevel.label,
                view = view.label,
                showHint = screen == Screen.DRIVING && driveTime < 14 && !sim.autopilot && !sim.crashed,
                mapRoads = mapRoads(sim),
                mapCars = mapCars(sim),
            )
        }

        private fun place(sim: Simulation): String {
            val p = sim.player
            val link = p.link
            return when (link) {
                is MotorwayLink -> when {
                    p.lane < 0 && !Road.hasExtraLane(p.s) -> "${link.cw.label} · hard shoulder"
                    p.lane < 0 -> "${link.cw.label} · slip lane"
                    else -> "${link.cw.label} · lane ${p.lane + 1}"
                }
                is RingLink -> "J${link.junction!!.number} roundabout"
                else -> "J${link.junction?.number ?: ""} ${link.label}"
            }
        }

        /** The current job for a police or recovery driver. */
        private fun job(sim: Simulation): String? {
            val p = sim.player
            val inc = sim.incidents.firstOrNull { it.awaitingPlayer } ?: return null
            val cw = inc.cw ?: return null
            val mw = p.link as? MotorwayLink
            val rearmost = inc.wrecks.minByOrNull { it.rear } ?: return null
            val where = if (rearmost.lane < 0) "on the hard shoulder" else "in lane ${rearmost.lane + 1}"
            val dist = if (mw?.cw == cw) (inc.rearS - p.s).roundToInt() else null
            val distText = when {
                dist == null -> "on the ${cw.label}"
                dist > 0 -> "$dist m ahead"
                else -> "behind you"
            }
            return if (p.type == VehicleType.POLICE) "Attend breakdown $where, $distText (blue lights on, stop behind it)"
            else "Recovery job: vehicle $where, $distText (pull in ahead of it and stop)"
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
                    if (p.lane == -1 && Road.isMergeZone(p.s)) return "Signal right and steer into lane 1 when there's a gap (Rule 259)"
                    if (p.lane == -1 && Road.isDivergeZone(p.s)) return "Exiting at J$n: stay in the exit lane"
                    if (p.lane == -1) return "You're on the hard shoulder: emergencies only (Rule 269)"
                    val toExit = centre + Road.DIVERGE_START - p.s
                    if (Road.isDivergeZone(p.s)) {
                        return if (p.lane == 0) "To exit at J$n, signal left and steer into the exit lane" else "J$n exit lane: from lane 1 only"
                    }
                    if (toExit in 0.0..1600.0) {
                        val d = (toExit / 10).roundToInt() * 10
                        return if (p.lane == 0) "J$n in $d m: signal left in good time to leave (Rule 273)" else "J$n in $d m: move to lane 1 if you're leaving"
                    }
                    if (off in Road.MERGE_START - 250..Road.MERGE_END && p.lane == 0) return "Traffic joining from the left: let it merge"
                    return null
                }
                is RingLink -> {
                    val j = link.junction!!
                    return "Keep left to take the next exit (${j.nextExit(p.s).name}); keep right to go round. Signal left as you leave"
                }
                else -> return when (link.kind) {
                    LinkKind.OFF_SLIP, LinkKind.LOCAL_IN -> "Roundabout ahead: give way to traffic from the right (Rule 185)"
                    LinkKind.ON_SLIP -> "Joining: give priority to traffic on the motorway and match its speed (Rule 259)"
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
                    v.beacon == Beacon.BLUE || v.type == VehicleType.POLICE -> POLICE
                    v.type == VehicleType.RECOVERY -> RECOVERY
                    v.type.isHeavy -> HEAVY
                    else -> CAR
                }
            }
            return list.toFloatArray()
        }
    }
}
