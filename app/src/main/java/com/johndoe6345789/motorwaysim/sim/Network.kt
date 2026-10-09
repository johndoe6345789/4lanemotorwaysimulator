package com.johndoe6345789.motorwaysim.sim

import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class LinkKind { MOTORWAY, OFF_SLIP, ON_SLIP, RING, LOCAL_IN, LOCAL_OUT, LOOP }

/** A piece of road that vehicles drive along, with its own longitudinal coordinate s. */
abstract class Link(val kind: LinkKind, val junction: Junction?) {
    abstract val length: Double
    open val minLane = 0
    open val maxLane = 0
    val lanes by lazy { Array(maxLane - minLane + 1) { ArrayList<Vehicle>() } }

    fun list(lane: Int): ArrayList<Vehicle> = lanes[lane - minLane]

    /**
     * World pose of the point [s] along this link at lateral coordinate [lateral]: a (fractional)
     * lane index on the motorway, or metres right of the centre line on other roads.
     */
    abstract fun pose(s: Double, lateral: Double, out: Pose)

    /** Half the drivable width of a single-lane road (m). */
    open val halfWidth: Double = Road.SLIP_WIDTH / 2

    /** Posted speed limit in mph (motorway limits come from the gantries instead). */
    abstract val limitMph: Int

    /** Comfortable speed for the bends at [s] (m/s). */
    open fun advisorySpeed(s: Double): Double = Path.MAX_ADVISORY

    /** Forward distance from [from] to [to] along the link (wraps on the roundabout). */
    open fun forward(from: Double, to: Double) = to - from

    /** Short name for the HUD. */
    abstract val label: String
}

class MotorwayLink(val cw: Carriageway) : Link(LinkKind.MOTORWAY, null) {
    override val length = Double.POSITIVE_INFINITY
    override val minLane = -1
    override val maxLane = Road.LANES - 1
    override val limitMph = Road.NATIONAL_LIMIT_MPH
    override val label = cw.label

    override fun pose(s: Double, lateral: Double, out: Pose) {
        out.x = cw.worldX(Road.laneCenter(lateral))
        out.y = cw.worldY(s)
        out.z = 0.0
        out.heading = cw.heading
    }
}

class PathLink(
    kind: LinkKind, junction: Junction, val path: Path, override val limitMph: Int, override val label: String,
) : Link(kind, junction) {
    override val length = path.length
    override fun pose(s: Double, lateral: Double, out: Pose) = path.pose(s, out, lateral)
    override fun advisorySpeed(s: Double) = path.advisorySpeed(s)
    override val halfWidth = if (kind == LinkKind.LOCAL_IN || kind == LinkKind.LOCAL_OUT || kind == LinkKind.LOOP) 2.0 else Road.SLIP_WIDTH / 2

    /** Where vehicles go at the end of this link (null: they leave the simulation). */
    var next: Link? = null

    /** Position on [next] where this link joins it. */
    var nextS = 0.0
    var nextLane = 0

    /** For roundabout entries: the ring position where this link joins. */
    var ringPort: RingPort? = null
}

class RingLink(junction: Junction, val path: Path) : Link(LinkKind.RING, junction) {
    override val length = path.length
    override val limitMph = Road.RING_SPEED_MPH
    override val label = "Roundabout"
    override fun pose(s: Double, lateral: Double, out: Pose) = path.pose(wrap(s), out, lateral)
    override val halfWidth = Road.RING_WIDTH / 2
    override fun advisorySpeed(s: Double) = path.advisorySpeed(wrap(s))
    override fun forward(from: Double, to: Double) = wrap(to - from)
    fun wrap(s: Double): Double = ((s % length) + length) % length
}

/** Where a road meets the roundabout. */
class RingPort(val ringS: Double, val link: PathLink, val isEntry: Boolean, val name: String)

/**
 * A grade-separated junction: an elevated roundabout spanning the motorway with two
 * bridges, connected to each carriageway by an exit (off) slip and an entry (on) slip,
 * plus a local A-road on each side.
 *
 * Arms are laid out for the northbound side and then rotated by 180° for the southbound side.
 */
class Junction(val k: Int) {
    val centreY = Road.junctionY(k)
    val number = Road.FIRST_JUNCTION_NUMBER + k
    val ring: RingLink
    val offSlip = EnumMap<Carriageway, PathLink>(Carriageway::class.java)
    val onSlip = EnumMap<Carriageway, PathLink>(Carriageway::class.java)
    val localIn = EnumMap<Carriageway, PathLink>(Carriageway::class.java) // keyed by the carriageway on whose side the road lies
    val localOut = EnumMap<Carriageway, PathLink>(Carriageway::class.java)
    val loop = EnumMap<Carriageway, PathLink>(Carriageway::class.java)
    val entries = ArrayList<RingPort>()
    val exits = ArrayList<RingPort>()
    val links = ArrayList<Link>()

    init {
        val r = Road.RING_RADIUS
        val cy = centreY
        val ringPath = Path.arc(0.0, cy, r, Road.deg(RING_START), -2 * PI, Road.RING_HEIGHT)
        ring = RingLink(this, ringPath)
        links += ring

        for (cw in Carriageway.entries) {
            val side = if (cw == Carriageway.NORTH) "West" else "East"
            val rotate = cw == Carriageway.SOUTH
            fun build(kind: LinkKind, limit: Int, label: String, make: () -> Path, z: (Double) -> Double): PathLink {
                val p = make()
                val path = if (rotate) p.rotated180(0.0, cy, z) else p
                return PathLink(kind, this, path, limit, label).also { links += it }
            }

            // Exit slip: leaves the diverge lane and climbs to the roundabout.
            val js = Road.DIVERGE_END
            val x0 = Carriageway.NORTH.worldX(Road.laneCenter(-1.0))
            val (ex, ey, eh) = ringEntryPoint(ARM_NB_OFF)
            val offZ = Path.ramp(0.05, Road.RING_HEIGHT, 0.2, 0.92)
            val off = build(LinkKind.OFF_SLIP, Road.NATIONAL_LIMIT_MPH, "Exit slip", {
                Path.bezier(x0, cy + js, PI / 2, ex, cy + ey, eh, offZ)
            }, offZ)
            offSlip[cw] = off

            // Entry slip: from the roundabout down to the acceleration lane.
            val (xx, xy, xh) = ringExitPoint(ARM_NB_ON)
            val onZ = Path.ramp(Road.RING_HEIGHT, 0.05, 0.08, 0.8)
            val on = build(LinkKind.ON_SLIP, Road.NATIONAL_LIMIT_MPH, "Entry slip", {
                Path.bezier(xx, cy + xy, xh, x0, cy + Road.MERGE_START, PI / 2, onZ)
            }, onZ)
            onSlip[cw] = on

            // Local A-road: two-way, with a turning loop at the far end.
            val far = -(Road.RING_RADIUS + Road.LOCAL_LENGTH)
            val off2 = Road.LOCAL_OFFSET
            val (ix, iy, ih) = ringEntryPoint(ARM_LOCAL_IN, LOCAL_RADIAL)
            val inZ = Path.ramp(0.0, Road.RING_HEIGHT, 0.55, 0.97)
            val lin = build(LinkKind.LOCAL_IN, Road.LOCAL_LIMIT_MPH, "A71 $side", {
                Path.bezier(far, cy + off2, 0.0, ix, cy + iy, ih, inZ, handle = 250.0, handleEnd = 30.0)
            }, inZ)
            localIn[cw] = lin
            val (ox, oy, oh) = ringExitPoint(ARM_LOCAL_OUT, LOCAL_RADIAL)
            val outZ = Path.ramp(Road.RING_HEIGHT, 0.0, 0.03, 0.45)
            val lout = build(LinkKind.LOCAL_OUT, Road.LOCAL_LIMIT_MPH, "A71 $side", {
                Path.bezier(ox, cy + oy, oh, far, cy - off2, PI, outZ, handle = 30.0, handleEnd = 250.0)
            }, outZ)
            localOut[cw] = lout
            val lp = build(LinkKind.LOOP, 15, "Turning loop", {
                Path.bezier(far, cy - off2, PI, far, cy + off2, 0.0, { 0.0 }, handle = 16.0)
            }, { 0.0 })
            loop[cw] = lp

            // Connections.
            val ringAngleOffset = if (rotate) 180.0 else 0.0
            val inPort = RingPort(ringS(ARM_NB_OFF + ringAngleOffset), off, true, "")
            off.next = ring; off.nextS = inPort.ringS; off.ringPort = inPort
            val localInPort = RingPort(ringS(ARM_LOCAL_IN + ringAngleOffset), lin, true, "")
            lin.next = ring; lin.nextS = localInPort.ringS; lin.ringPort = localInPort
            entries += inPort
            entries += localInPort
            exits += RingPort(ringS(ARM_NB_ON + ringAngleOffset), on, false, cw.label)
            exits += RingPort(ringS(ARM_LOCAL_OUT + ringAngleOffset), lout, false, "A71 $side")
            lout.next = lp
            lp.next = lin
        }
        // On-slips join the motorway acceleration lane; set by the network once motorway links exist.
        exits.sortBy { it.ringS }
        entries.sortBy { it.ringS }
    }

    fun connect(motorway: Map<Carriageway, MotorwayLink>) {
        for (cw in Carriageway.entries) {
            val on = onSlip.getValue(cw)
            on.next = motorway.getValue(cw)
            on.nextS = cw.junctionS(k) + Road.MERGE_START
            on.nextLane = -1
        }
    }

    /** The first exit at or after ring position [s] (in the direction of circulation). */
    fun nextExit(s: Double, after: Double = 0.5): RingPort =
        exits.minBy { ring.forward(s + after, it.ringS) }

    companion object {
        /** Ring s = 0 is at this angle; circulation is clockwise. */
        const val RING_START = 220.0
        const val ARM_NB_OFF = 220.0
        // Keeping left, traffic arriving from the west is on the north side of the road, and
        // circulating traffic passes each arm's exit before its entry.
        const val ARM_LOCAL_OUT = 186.0
        const val ARM_LOCAL_IN = 174.0
        const val ARM_NB_ON = 140.0

        fun ringS(angleDeg: Double): Double {
            val d = ((RING_START - angleDeg) % 360 + 360) % 360
            return Road.deg(d) * Road.RING_RADIUS
        }

        /** Point on the ring at [angleDeg] relative to the junction centre, with the heading of a joining road. */
        /** Slip roads join at a shallow angle; two-way local roads approach more squarely. */
        private const val SLIP_RADIAL = 0.6
        private const val LOCAL_RADIAL = 0.92

        private fun ringEntryPoint(angleDeg: Double, radial: Double = SLIP_RADIAL): Triple<Double, Double, Double> {
            val a = Road.deg(angleDeg)
            val r = Road.RING_RADIUS
            val t = kotlin.math.sqrt(1 - radial * radial)
            // Blend of "heading inwards" and the clockwise tangent.
            val hx = -cos(a) * radial + sin(a) * t
            val hy = -sin(a) * radial - cos(a) * t
            return Triple(r * cos(a), r * sin(a), kotlin.math.atan2(hy, hx))
        }

        private fun ringExitPoint(angleDeg: Double, radial: Double = SLIP_RADIAL): Triple<Double, Double, Double> {
            val a = Road.deg(angleDeg)
            val r = Road.RING_RADIUS
            val t = kotlin.math.sqrt(1 - radial * radial)
            val hx = cos(a) * radial + sin(a) * t
            val hy = sin(a) * radial - cos(a) * t
            return Triple(r * cos(a), r * sin(a), kotlin.math.atan2(hy, hx))
        }
    }
}

/** The two motorway carriageways plus the junctions near the area being simulated. */
class Network {
    val motorway: Map<Carriageway, MotorwayLink> =
        EnumMap<Carriageway, MotorwayLink>(Carriageway::class.java).apply { for (c in Carriageway.entries) put(c, MotorwayLink(c)) }
    val junctions = java.util.TreeMap<Int, Junction>()

    fun junction(k: Int): Junction = junctions.getOrPut(k) { Junction(k).also { it.connect(motorway) } }

    /** Creates junctions within [range] of world y and returns those that went out of range. */
    fun maintain(y: Double, range: Double): List<Junction> {
        val lo = Road.junctionNearest(y - range)
        val hi = Road.junctionNearest(y + range)
        for (k in lo..hi) junction(k)
        val gone = junctions.values.filter { it.k < lo || it.k > hi }
        for (j in gone) junctions.remove(j.k)
        return gone
    }

    fun allLinks(): Sequence<Link> = sequence {
        yieldAll(motorway.values)
        for (j in junctions.values) yieldAll(j.links)
    }
}
