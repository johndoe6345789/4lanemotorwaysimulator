package com.johndoe6345789.motorwaysim.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class TrafficLevel(val label: String, val vehiclesPerKmPerLane: Double) {
    LIGHT("Light", 7.0),
    MODERATE("Moderate", 15.0),
    HEAVY("Heavy", 25.0),
    RUSH_HOUR("Rush hour", 36.0);

    fun next(): TrafficLevel = entries[(ordinal + 1) % entries.size]
}

/** What an overhead gantry is displaying. */
data class GantrySignal(
    /** Mandatory variable speed limit, or null when the national limit applies (blank signals). */
    val limitMph: Int?,
    /** Lane index showing a red X, or -1 when all lanes are open. */
    val closedLane: Int,
    /** Simulation time at which the speed limit was last changed. */
    val since: Double = -100.0,
    /** Text on the variable message sign beside the gantry, if any. */
    val message: String? = null,
) {
    companion object {
        val BLANK = GantrySignal(null, -1)
    }
}

class Incident(val vehicle: Vehicle) {
    val lane get() = vehicle.lane
    val s get() = vehicle.s
}

/**
 * Microscopic traffic simulation of a single four-lane carriageway.
 *
 * Longitudinal behaviour uses the Intelligent Driver Model; lane changes use
 * MOBIL with a keep-left bias and a no-undertaking rule as described in the UK
 * Highway Code. The simulation only models a moving window of road around the
 * player's car: traffic is spawned at the edges of the window and removed when
 * it falls out of it.
 */
class Simulation(seed: Long = System.nanoTime()) {
    private val rng = Random(seed)
    private var nextId = 1

    val vehicles = ArrayList<Vehicle>()
    lateinit var player: Vehicle
        private set

    var time = 0.0
        private set

    var trafficLevel = TrafficLevel.MODERATE
    var incidentsEnabled = true

    /** When true the player's car drives itself using the same model as the AI traffic. */
    var autopilot = false

    // Player controls (manual driving).
    var throttle = false
    var brake = false

    var crashed = false
        private set
    var distanceTravelled = 0.0
        private set
    var cameraFlashes = 0
        private set
    var lastFlashTime = -100.0
        private set
    var lastFlashSpeedMph = 0
        private set
    var lastFlashLimitMph = 0
        private set

    /** Number of times the anti-overlap safety net had to separate two AI vehicles. */
    var aiInterventions = 0
        private set

    val incidents = ArrayList<Incident>()
    var lastIncidentTime = -100.0
        private set
    private var nextIncidentTime = 0.0

    private val signals = HashMap<Long, GantrySignal>()
    private val laneLists = Array(Road.LANES) { ArrayList<Vehicle>() }
    private var spawnTimer = 0.0
    private var signalTimer = 0.0

    init {
        reset()
    }

    fun reset() {
        vehicles.clear()
        incidents.clear()
        signals.clear()
        time = 0.0
        crashed = false
        distanceTravelled = 0.0
        cameraFlashes = 0
        lastFlashTime = -100.0
        lastIncidentTime = -100.0
        aiInterventions = 0
        throttle = false
        brake = false
        nextIncidentTime = 60.0 + rng.nextDouble() * 90.0

        player = Vehicle(
            id = nextId++, type = VehicleType.CAR, s = 250.0, lane = 1,
            v = Units.mphToMs(60.0), desiredFactor = 1.0, politeness = 0.3,
            yieldsToMergers = true, color = PLAYER_COLOR, isPlayer = true,
        )
        vehicles.add(player)
        populate()
        rebuildLaneLists()
        updateSignals()
    }

    // ---------------------------------------------------------------- queries

    fun signalAt(gantry: Long): GantrySignal = signals[gantry] ?: GantrySignal.BLANK

    /** The speed limit (m/s) in force at position [s]: set by the last gantry passed. */
    fun limitAt(s: Double): Double {
        val mph = signalAt(Road.gantryIndexAt(s)).limitMph ?: Road.NATIONAL_LIMIT_MPH
        return Units.mphToMs(mph.toDouble())
    }

    fun limitMphAt(s: Double): Int = signalAt(Road.gantryIndexAt(s)).limitMph ?: Road.NATIONAL_LIMIT_MPH

    /** Whether the limit at [s] is a variable (smart motorway) limit rather than the national limit. */
    fun isVariableLimitAt(s: Double) = signalAt(Road.gantryIndexAt(s)).limitMph != null

    fun averageSpeed(): Double {
        var sum = 0.0
        var n = 0
        for (c in vehicles) if (c.type != VehicleType.BREAKDOWN) { sum += c.v; n++ }
        return if (n == 0) 0.0 else sum / n
    }

    // ---------------------------------------------------------------- controls

    /** Player-initiated lane change: no safety checks, it's the driver's responsibility. */
    fun steer(direction: Int) {
        if (crashed) return
        autopilot = false
        val target = player.lane + direction
        if (player.isChangingLane || target !in 0 until Road.LANES) return
        player.startLaneChange(target, time, PLAYER_LANE_CHANGE_TIME)
    }

    /** Places a broken-down vehicle in a random lane some way ahead of the player. */
    fun triggerIncident() {
        if (crashed) return
        val lane = rng.nextInt(Road.LANES)
        val s = player.s + AHEAD - 40.0
        // Clear the spot, leaving approaching traffic room to stop (it is far outside the view).
        vehicles.removeAll { !it.isPlayer && it.occupies(lane) && it.s > s - 160 && it.rear < s + 20 }
        val bd = Vehicle(
            id = nextId++, type = VehicleType.BREAKDOWN, s = s, lane = lane, v = 0.0,
            desiredFactor = 0.0, politeness = 0.0, yieldsToMergers = false,
            color = CAR_COLORS[rng.nextInt(CAR_COLORS.size)],
        )
        bd.indicator = 2 // hazard lights
        vehicles.add(bd)
        incidents.add(Incident(bd))
        lastIncidentTime = time
        rebuildLaneLists()
        updateSignals()
    }

    // ---------------------------------------------------------------- stepping

    /** Advances the simulation by [dt] seconds (internally sub-stepped). */
    fun update(dt: Double) {
        if (crashed) return
        var remaining = min(dt, 0.25)
        while (remaining > 1e-9) {
            val h = min(remaining, MAX_STEP)
            step(h)
            remaining -= h
            if (crashed) break
        }
    }

    private fun step(dt: Double) {
        time += dt
        rebuildLaneLists()

        for (c in vehicles) c.acc = computeAccel(c)

        for (c in vehicles) {
            if (c.type == VehicleType.BREAKDOWN) continue
            if (c.isPlayer && !autopilot) continue
            if (time >= c.nextDecisionTime) {
                c.nextDecisionTime = time + 0.4 + rng.nextDouble() * 0.4
                decideLaneChange(c)
            }
        }

        val playerS = player.s
        for (c in vehicles) {
            if (c.type == VehicleType.BREAKDOWN) continue
            c.v = max(0.0, c.v + c.acc * dt)
            if (c.isPlayer) c.v = min(c.v, PLAYER_TOP_SPEED)
            c.s += c.v * dt
            c.advanceLaneChange(dt)
        }
        distanceTravelled += player.s - playerS

        rebuildLaneLists()
        separateOverlappingAi()
        checkPlayerCollision()
        checkSpeedCamera(playerS, player.s)

        spawnTimer -= dt
        if (spawnTimer <= 0) {
            spawnTimer = 0.2
            despawn()
            spawn()
        }
        signalTimer -= dt
        if (signalTimer <= 0) {
            signalTimer = 1.5
            updateSignals()
        }
        if (incidentsEnabled && time >= nextIncidentTime && incidents.isEmpty()) {
            triggerIncident()
            nextIncidentTime = time + 120.0 + rng.nextDouble() * 120.0
        }
    }

    // ---------------------------------------------------------------- driver model

    private fun desiredSpeedOf(c: Vehicle): Double {
        var limit = limitAt(c.s)
        // Drivers adapt to a lower limit shown on the next gantry before reaching it.
        val nextGantry = (Road.gantryIndexAt(c.s) + 1)
        if (nextGantry * Road.GANTRY_SPACING - c.s < 250) {
            signalAt(nextGantry).limitMph?.let { limit = min(limit, Units.mphToMs(it.toDouble())) }
        }
        return max(1.0, c.desiredSpeed(limit))
    }

    private fun idmTo(c: Vehicle, v0: Double, leader: Vehicle?): Double {
        if (leader == null) return Idm.freeAccel(c.type, c.v, v0)
        return Idm.accel(c.type, c.v, v0, leader.rear - c.s, leader.v)
    }

    /** IDM acceleration in lane [l], including the no-undertaking rule relative to lane l+1. */
    private fun accelInLane(c: Vehicle, l: Int, v0: Double): Double {
        var a = idmTo(c, v0, leaderIn(l, c))
        if (c.v > UNDERTAKE_SPEED && l + 1 < Road.LANES) {
            val right = leaderIn(l + 1, c)
            if (right != null && right.type != VehicleType.BREAKDOWN && right.v < c.v &&
                right.rear - c.s < max(60.0, 2.5 * c.v)
            ) {
                a = min(a, max(idmTo(c, v0, right), -3.0))
            }
        }
        return a
    }

    private fun computeAccel(c: Vehicle): Double {
        if (c.type == VehicleType.BREAKDOWN) return 0.0
        if (c.isPlayer && !autopilot) {
            val drag = 0.2 + 0.00035 * c.v * c.v
            return when {
                brake -> -8.0
                throttle -> 3.4 * (1 - (c.v / PLAYER_TOP_SPEED).let { it * it }) - drag + 0.2
                else -> 0.0 // cruise control holds the current speed
            }
        }
        val v0 = desiredSpeedOf(c)
        var a = accelInLane(c, c.lane, v0)
        if (c.isChangingLane) {
            a = min(a, idmTo(c, v0, leaderIn(c.fromLane, c)))
        } else if (c.yieldsToMergers) {
            // Let a driver waiting to merge into our lane in ahead of us ("zip merging").
            for (d in intArrayOf(-1, 1)) {
                val l = c.lane + d
                if (l !in 0 until Road.LANES) continue
                for (m in laneLists[l]) {
                    if (m.wantsLane == c.lane && m.rear > c.s && m.rear - c.s < 50.0) {
                        a = min(a, max(idmTo(c, v0, m), -2.5))
                    }
                }
            }
        }
        return a
    }

    private fun incidentAhead(l: Int, s: Double, within: Double): Incident? =
        incidents.firstOrNull { it.lane == l && it.s > s && it.vehicle.rear - s < within }

    private fun decideLaneChange(c: Vehicle) {
        if (c.isChangingLane || time - c.lastLaneChangeTime < LANE_CHANGE_COOLDOWN) return
        val v0 = desiredSpeedOf(c)
        val aCur = accelInLane(c, c.lane, v0)
        val oldLeader = leaderIn(c.lane, c)
        val oldFollower = followerIn(c.lane, c)
        val blocked = incidentAhead(c.lane, c.s, 700.0)

        var bestTarget = -1
        var bestScore = 0.0
        var wanted = -1

        for (dir in intArrayOf(1, -1)) {
            val t = c.lane + dir
            if (!c.canUseLane(t)) continue
            if (incidentAhead(t, c.s, 700.0) != null) continue

            val newLeader = leaderIn(t, c)
            val newFollower = followerIn(t, c)

            var urgency = 0.0
            if (blocked != null) {
                val dist = blocked.vehicle.rear - c.s
                urgency = 1.0 + 4.0 * (1.0 - dist / 700.0).coerceIn(0.0, 1.0)
            }
            // Safety: nobody (including us) may need to brake harder than bSafe.
            val bSafe = if (urgency > 3.0 && c.v < 8.0) 6.0 else SAFE_BRAKING
            var safe = true
            if (newLeader != null && newLeader.rear - c.s < c.type.minGap) safe = false
            var aNfOld = 0.0
            var aNfNew = 0.0
            if (safe && newFollower != null) {
                if (c.rear - newFollower.s < newFollower.type.minGap) safe = false
                else {
                    val v0f = desiredSpeedOf(newFollower)
                    aNfOld = idmTo(newFollower, v0f, newLeader)
                    aNfNew = idmTo(newFollower, v0f, c)
                    if (aNfNew < -bSafe) safe = false
                }
            }
            val aNew = accelInLane(c, t, v0)
            if (aNew < -bSafe) safe = false

            if (!safe) {
                if (urgency > 0) wanted = t
                continue
            }

            var aOfOld = 0.0
            var aOfNew = 0.0
            if (oldFollower != null) {
                val v0o = desiredSpeedOf(oldFollower)
                aOfOld = idmTo(oldFollower, v0o, c)
                aOfNew = idmTo(oldFollower, v0o, oldLeader)
            }
            val incentive = aNew - aCur + c.politeness * ((aNfNew - aNfOld) + (aOfNew - aOfOld))
            var threshold = CHANGE_THRESHOLD + if (dir > 0) KEEP_LEFT_BIAS else -KEEP_LEFT_BIAS
            if (dir > 0 && c.type.isHeavy) threshold += 0.2
            val score = incentive - threshold + urgency
            if (score > bestScore) {
                bestScore = score
                bestTarget = t
            }
        }

        if (bestTarget >= 0) {
            val duration = when {
                c.isPlayer -> 2.5
                c.type.isHeavy -> 4.0
                else -> 2.4 + rng.nextDouble() * 1.4
            }
            c.startLaneChange(bestTarget, time, duration)
            // Make the move visible to drivers deciding later in this same step.
            laneLists[bestTarget].add(c)
            laneLists[bestTarget].sortWith(BY_POSITION)
        } else {
            c.wantsLane = wanted
            c.indicator = if (wanted >= 0) (if (wanted > c.lane) 1 else -1) else 0
        }
    }

    // ---------------------------------------------------------------- lane bookkeeping

    private fun rebuildLaneLists() {
        for (l in laneLists) l.clear()
        for (c in vehicles) {
            laneLists[c.lane].add(c)
            if (c.isChangingLane && c.fromLane != c.lane) laneLists[c.fromLane].add(c)
        }
        for (l in laneLists) l.sortWith(BY_POSITION)
    }

    private fun isAhead(o: Vehicle, c: Vehicle) = o.s > c.s || (o.s == c.s && o.id > c.id)

    /** The nearest vehicle occupying lane [l] whose front is ahead of [c]'s front. */
    fun leaderIn(l: Int, c: Vehicle): Vehicle? {
        for (o in laneLists[l]) if (o !== c && isAhead(o, c)) return o
        return null
    }

    /** The nearest vehicle occupying lane [l] whose front is behind [c]'s front. */
    fun followerIn(l: Int, c: Vehicle): Vehicle? {
        val list = laneLists[l]
        for (i in list.indices.reversed()) {
            val o = list[i]
            if (o !== c && !isAhead(o, c)) return o
        }
        return null
    }

    /**
     * The IDM is collision free, but the player can provoke impossible situations
     * (e.g. cutting in and stopping). Keep AI vehicles from driving through each other.
     */
    private fun separateOverlappingAi() {
        for (list in laneLists) {
            for (i in list.size - 2 downTo 0) {
                val f = list[i]
                val l = list[i + 1]
                if (f.isPlayer || l.isPlayer || f.type == VehicleType.BREAKDOWN) continue
                if (f.s > l.rear - 0.2) {
                    f.s = l.rear - 0.2
                    f.v = min(f.v, l.v)
                    aiInterventions++
                }
            }
        }
    }

    private fun checkPlayerCollision() {
        val p = player
        for (o in vehicles) {
            if (o === p) continue
            if (o.rear < p.s && p.rear < o.s && abs(o.x - p.x) < (o.width + p.width) / 2 - 0.15) {
                crashed = true
                return
            }
        }
    }

    private fun checkSpeedCamera(before: Double, after: Double) {
        val g = Road.gantryIndexAt(after)
        if (Road.gantryIndexAt(before) == g) return
        // Cameras allow drivers a grace period to react to a newly changed limit.
        if (time - signalAt(g).since < CAMERA_GRACE_PERIOD) return
        val limitMph = limitMphAt(after)
        val mph = Units.msToMph(player.v)
        // Typical enforcement threshold: limit + 10% + 2 mph.
        if (mph > limitMph * 1.1 + 2) {
            cameraFlashes++
            lastFlashTime = time
            lastFlashSpeedMph = mph.toInt()
            lastFlashLimitMph = limitMph
        }
    }

    // ---------------------------------------------------------------- traffic management

    private fun updateSignals() {
        val first = Road.gantryIndexAt(player.s - BEHIND) - 1
        val last = Road.gantryIndexAt(player.s + AHEAD) + 2
        signals.keys.removeAll { it < first - 2 || it > last + 2 }
        for (g in first..last) {
            val gs = g * Road.GANTRY_SPACING
            var limit: Int? = null
            var closed = -1
            var message: String? = null
            for (inc in incidents) {
                val d = inc.s - gs
                if (d in 0.0..1000.0) {
                    limit = minLimit(limit, 50); closed = inc.lane
                    message = "LANE CLOSED"
                } else if (d in 1000.0..2000.0) {
                    limit = minLimit(limit, 60)
                    message = "INCIDENT AHEAD"
                }
            }
            // Queue protection: slow traffic downstream lowers the limit upstream.
            if (gs + 1000 <= player.s + AHEAD) {
                var sum = 0.0
                var n = 0
                for (c in vehicles) {
                    if (c.type != VehicleType.BREAKDOWN && c.s >= gs && c.s < gs + 1000) { sum += c.v; n++ }
                }
                if (n >= 8) {
                    val mph = Units.msToMph(sum / n)
                    when {
                        mph < 25 -> limit = minLimit(limit, 40)
                        mph < 40 -> limit = minLimit(limit, 50)
                        mph < 50 -> limit = minLimit(limit, 60)
                    }
                    if (mph < 50 && message == null) message = "QUEUE CAUTION"
                }
            } else {
                // Beyond what we simulate: keep whatever was displayed before.
                signals[g]?.let { if (closed < 0 && limit == null) { limit = it.limitMph; message = it.message } }
            }
            val old = signals[g]
            val since = if (old != null && old.limitMph != limit) time else old?.since ?: -100.0
            signals[g] = GantrySignal(limit, closed, since, message)
        }
    }

    private fun minLimit(a: Int?, b: Int) = if (a == null) b else min(a, b)

    private fun despawn() {
        val lo = player.s - BEHIND - 60
        val hi = player.s + AHEAD + 60
        vehicles.removeAll { !it.isPlayer && (it.s < lo || it.rear > hi) }
        incidents.removeAll { it.vehicle !in vehicles }
    }

    private fun spawn() {
        val window = AHEAD + BEHIND
        for (l in 0 until Road.LANES) {
            var count = 0
            var speedSum = 0.0
            for (c in laneLists[l]) {
                if (c.lane == l && c.type != VehicleType.BREAKDOWN) { count++; speedSum += c.v }
            }
            val target = trafficLevel.vehiclesPerKmPerLane * LANE_SHARE[l] * window / 1000.0
            if (count >= target) continue
            val c = newVehicle(l, 0.0, 0.0)
            val laneSpeed = if (count > 0) speedSum / count else desiredSpeedOf(c)
            val behind = if (rng.nextDouble() < 0.2) rng.nextBoolean() else laneSpeed > player.v
            c.s = if (behind) player.s - BEHIND + rng.nextDouble() * 30 else player.s + AHEAD - rng.nextDouble() * 30
            c.v = min(desiredSpeedOf(c), laneSpeed * (0.9 + 0.1 * rng.nextDouble()))
            if (fitsAt(c, l)) {
                vehicles.add(c)
                laneLists[l].add(c)
                laneLists[l].sortWith(BY_POSITION)
            }
        }
    }

    /** Whether [c] can be inserted into lane [l] without forcing anyone to brake hard. */
    private fun fitsAt(c: Vehicle, l: Int): Boolean {
        val leader = leaderIn(l, c)
        if (leader != null) {
            val gap = leader.rear - c.s
            if (gap < c.type.minGap + 0.6 * c.v * c.type.timeHeadway) return false
            c.v = min(c.v, leader.v + gap / 10.0)
        }
        val follower = followerIn(l, c)
        if (follower != null) {
            val gap = c.rear - follower.s
            if (gap < follower.type.minGap + 0.8 * follower.v * follower.type.timeHeadway) return false
            if (idmTo(follower, desiredSpeedOf(follower), c) < -2.0) return false
        }
        return true
    }

    private fun populate() {
        for (l in 0 until Road.LANES) {
            val spacing = 1000.0 / (trafficLevel.vehiclesPerKmPerLane * LANE_SHARE[l])
            var leader: Vehicle? = null
            var s = player.s + AHEAD - rng.nextDouble() * spacing
            while (s > player.s - BEHIND) {
                if (l == player.lane && s < player.rear && (leader == null || leader.s > player.s)) {
                    leader = player
                    s = min(s, player.rear - 30)
                }
                val tooCloseToPlayer = l == player.lane && s > player.rear - 30 && s < player.s + 40
                if (tooCloseToPlayer) {
                    s -= spacing
                    continue
                }
                val c = newVehicle(l, s, 0.0)
                // Start everyone close to equilibrium with the vehicle actually in front.
                var v = desiredSpeedOf(c) * 0.95
                if (leader != null) {
                    val gap = leader.rear - s
                    v = min(v, min(leader.v, (gap - c.type.minGap) / c.type.timeHeadway))
                }
                c.v = max(0.0, v)
                vehicles.add(c)
                leader = c
                s = c.rear - max(c.type.minGap + 2.0, (spacing - 6.0) * (0.7 + 0.6 * rng.nextDouble()))
            }
        }
    }

    private fun newVehicle(lane: Int, s: Double, v: Double): Vehicle {
        val r = rng.nextDouble()
        var type = when {
            r < 0.70 -> VehicleType.CAR
            r < 0.84 -> VehicleType.VAN
            r < 0.96 -> VehicleType.LORRY
            else -> VehicleType.COACH
        }
        // Heavy vehicles keep to the nearside lanes.
        if (type.isHeavy && (lane >= 2 && rng.nextDouble() < 0.8 || lane == Road.LORRY_BANNED_LANE)) {
            type = VehicleType.CAR
        }
        val factor = when (type) {
            VehicleType.CAR -> 0.92 + rng.nextDouble() * 0.24 // 64–81 mph on a 70 limit
            VehicleType.VAN -> 0.9 + rng.nextDouble() * 0.18
            else -> 1.0
        }
        val color = when (type) {
            VehicleType.LORRY, VehicleType.COACH -> HEAVY_COLORS[rng.nextInt(HEAVY_COLORS.size)]
            VehicleType.VAN -> VAN_COLORS[rng.nextInt(VAN_COLORS.size)]
            else -> CAR_COLORS[rng.nextInt(CAR_COLORS.size)]
        }
        return Vehicle(
            id = nextId++, type = type, s = s, lane = lane, v = v,
            desiredFactor = factor,
            politeness = 0.1 + rng.nextDouble() * 0.4,
            yieldsToMergers = rng.nextDouble() < 0.75,
            color = color,
        ).also { it.nextDecisionTime = rng.nextDouble() }
    }

    companion object {
        const val MAX_STEP = 1.0 / 60.0
        const val AHEAD = 900.0
        const val BEHIND = 450.0
        const val PLAYER_TOP_SPEED = 62.0 // ≈ 139 mph
        const val PLAYER_LANE_CHANGE_TIME = 1.3
        const val LANE_CHANGE_COOLDOWN = 4.0
        const val SAFE_BRAKING = 4.0
        const val CAMERA_GRACE_PERIOD = 10.0
        const val CHANGE_THRESHOLD = 0.15
        const val KEEP_LEFT_BIAS = 0.3
        /** Above this speed (≈ 38 mph) vehicles must not pass slower traffic on its left. */
        val UNDERTAKE_SPEED = Units.mphToMs(38.0)

        /** Relative use of each lane (lane 1 → lane 4). */
        val LANE_SHARE = doubleArrayOf(1.1, 1.05, 1.0, 0.85)

        const val PLAYER_COLOR = 0xFFFFC21A.toInt()
        val CAR_COLORS = intArrayOf(
            0xFFF2F2F2.toInt(), 0xFF1E1E1E.toInt(), 0xFFA8ADB3.toInt(), 0xFF5B6168.toInt(),
            0xFF1F3A68.toInt(), 0xFFB3202A.toInt(), 0xFF2F6FB5.toInt(), 0xFF2E5E3A.toInt(),
            0xFF7A2E3A.toInt(), 0xFFD9D4C7.toInt(), 0xFF3C3F44.toInt(), 0xFFE07A22.toInt(),
        )
        val VAN_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFE9E9E9.toInt(), 0xFF8A9199.toInt(), 0xFF1C4E8A.toInt(),
        )
        val HEAVY_COLORS = intArrayOf(
            0xFFEDEDED.toInt(), 0xFF2B5DA8.toInt(), 0xFFB82E2E.toInt(), 0xFF2F7D4F.toInt(),
            0xFF333333.toInt(), 0xFFD8A31A.toInt(),
        )

        private val BY_POSITION = Comparator<Vehicle> { a, b ->
            val c = a.s.compareTo(b.s)
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }
}
