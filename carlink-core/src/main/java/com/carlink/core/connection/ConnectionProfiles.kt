package com.carlink.core.connection

import android.content.Context
import com.carlink.core.core.Engine
import com.carlink.core.core.Transport
import com.carlink.core.detect.KnownPhones
import com.carlink.core.settings.WirelessPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How one attempt on one path ended. [kind] is a [FailureReason] name or `STALL_<stage>`; null when it connected. */
data class AttemptOutcome(
    /** Wall-clock time (ms since the epoch). */
    val atMillis: Long,
    val ok: Boolean,
    /** Connected: adapter start to the first frame. */
    val millis: Long? = null,
    val kind: String? = null,
    /** The phase it ended in. */
    val stage: ProgressStage? = null,
) {
    /** True when a different wireless path could have done better (counts against the path). */
    val pathFault: Boolean
        get() = !ok && (kind == STALL_LINKING || kind == STALL_PICTURE ||
            runCatching { FailureReason.valueOf(kind ?: "") }.getOrNull()?.pathRelated == true)

    companion object {
        val STALL_LINKING = stallKind(ProgressStage.JOINING_WIFI)
        val STALL_PICTURE = stallKind(ProgressStage.STARTING_PROJECTION)
        fun stallKind(stage: ProgressStage?) = "STALL_" + (stage?.name ?: "TOTAL")
    }
}

/** Something that went wrong, for the driver's or a mechanic's eyes (newest first in [PhoneHealth]). */
data class ProblemRecord(
    val atMillis: Long,
    /** [WirelessPath] name, or "USB". */
    val path: String,
    val stage: ProgressStage?,
    val kind: String,
    val detail: String?,
)

data class PathHealth(
    val path: String,
    val attempts: Int,
    val successes: Int,
    val medianTimeToPictureMillis: Long?,
    val lastOk: Boolean?,
)

/**
 * One phone's connection health, for Diagnostics and a launcher: how often it connects, how long
 * it takes, the path that works best and what went wrong lately.
 */
data class PhoneHealth(
    /** [ConnectionProfiles.keyOf]: engine plus Bluetooth address, or plus the USB name. */
    val key: String,
    val engine: Engine,
    val phoneName: String?,
    val attempts: Int,
    val successes: Int,
    /** successes / attempts over the kept history; null before the first attempt. */
    val successRate: Float?,
    val medianTimeToPictureMillis: Long?,
    /** [WirelessPath] name or "USB": the path with the best record, or null when none connected yet. */
    val bestPath: String?,
    val paths: List<PathHealth>,
    val lastProblems: List<ProblemRecord>,
)

/**
 * Per-phone connection history and what [SessionManager] learns from it (host-independent: give it
 * a [Storage]). Main thread.
 *
 * Kept per phone (engine plus Bluetooth address, or plus the USB product name, since a USB session
 * has no Bluetooth address) and per path ("USB" or a [WirelessPath]):
 * - the last [MAX_OUTCOMES] attempt outcomes (connected and how fast, or how it failed);
 * - the last [MAX_SAMPLES] durations of each phase (adapter events: waiting → handshake → linking
 *   → picture) and of each whole attempt;
 * - failure kinds with counts, and the last [MAX_PROBLEMS] problems with their detail.
 *
 * Used for: the order wireless paths are tried in ([rankPaths]), each phase's deadline
 * ([deadline], via [AdaptiveDeadlines]) and [health].
 */
class ConnectionProfiles(
    private val storage: Storage = Storage.Memory(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    /** Where the history lives (SharedPreferences in the app, memory in tests). */
    interface Storage {
        fun read(): String?
        fun write(text: String)

        class Memory(var text: String? = null) : Storage {
            override fun read() = text
            override fun write(text: String) { this.text = text }
        }
    }

    internal class Profile(val key: String, val engine: Engine) {
        var phoneName: String? = null
        var lastUsed = 0L
        val outcomes = LinkedHashMap<String, ArrayDeque<AttemptOutcome>>()
        /** "<path>/<stage name or TOTAL>" → durations. */
        val samples = LinkedHashMap<String, ArrayDeque<Long>>()
        val failures = LinkedHashMap<String, Int>()
        val problems = ArrayDeque<ProblemRecord>()
    }

    private val profiles = LinkedHashMap<String, Profile>()
    private val _health = MutableStateFlow<List<PhoneHealth>>(emptyList())

    /** Every known phone's health, most recently used first. */
    val health: StateFlow<List<PhoneHealth>> = _health.asStateFlow()

    init {
        runCatching { storage.read()?.let(::decode) }
        publish()
    }

    fun healthOf(request: SessionRequest): PhoneHealth? = profiles[keyOf(request)]?.let(::healthOf)

    // --- Recording ----------------------------------------------------------------------------

    /** [stage] took [millis] on [path] (a phase that was left forward, not one cut short). */
    fun recordStage(request: SessionRequest, path: WirelessPath?, stage: ProgressStage, millis: Long) {
        val p = profile(request)
        p.samples.push(sampleKey(pathName(path), stage), millis, MAX_SAMPLES)
        save()
    }

    fun recordSuccess(request: SessionRequest, path: WirelessPath?, millis: Long) {
        val p = profile(request)
        p.outcomes.push(pathName(path), AttemptOutcome(wallClock(), ok = true, millis = millis), MAX_OUTCOMES)
        p.samples.push(sampleKey(pathName(path), null), millis, MAX_SAMPLES)
        save()
    }

    /** An attempt on [path] ended without a picture ([kind]: a [FailureReason] name or [AttemptOutcome.stallKind]). */
    fun recordFailure(request: SessionRequest, path: WirelessPath?, kind: String, stage: ProgressStage?, detail: String?) {
        val p = profile(request)
        p.outcomes.push(pathName(path), AttemptOutcome(wallClock(), ok = false, kind = kind, stage = stage?.let(::normalize)), MAX_OUTCOMES)
        problem(p, path, stage, kind, detail)
        save()
    }

    /** A recovery the watchdog applied without ending the attempt (a nudge, a USB re-switch, a hint). */
    fun recordProblem(request: SessionRequest, path: WirelessPath?, stage: ProgressStage?, kind: String, detail: String?) {
        problem(profile(request), path, stage, kind, detail)
        save()
    }

    // --- What the history says ----------------------------------------------------------------

    /** How long [stage] (null: the whole attempt) may take for this phone on [path] before the watchdog acts. */
    fun deadline(request: SessionRequest, path: WirelessPath?, stage: ProgressStage?): Long =
        AdaptiveDeadlines.deadline(samples(request, path, stage), AdaptiveDeadlines.bounds(stage, request.transport))

    /** This phone's usual (median) time for [stage] on [path], when known. */
    fun usualMillis(request: SessionRequest, path: WirelessPath?, stage: ProgressStage?): Long? =
        samples(request, path, stage).takeIf { it.size >= AdaptiveDeadlines.MIN_SAMPLES }?.let(AdaptiveDeadlines::median)

    /**
     * [candidates] (already in the policy's order) re-ordered by this phone's record: a path that
     * keeps connecting moves up, a path whose last two attempts failed for path reasons moves down.
     * Paths with fewer than two outcomes keep a neutral score, and ties keep the policy's order.
     */
    fun rankPaths(request: SessionRequest, candidates: List<WirelessPath>): List<WirelessPath> {
        if (candidates.size < 2) return candidates
        val p = profiles[keyOf(request)] ?: return candidates
        fun score(path: WirelessPath): Double {
            val relevant = p.outcomes[path.name]?.filter { it.ok || it.pathFault }.orEmpty()
            if (relevant.size < 2) return NEUTRAL
            val ok = relevant.count { it.ok }
            var s = (ok + 1.0) / (relevant.size + 2.0)
            if (relevant.takeLast(2).none { it.ok }) s -= RECENT_FAILURE_PENALTY
            return s
        }
        return candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<WirelessPath>> { score(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    // --- Internals ----------------------------------------------------------------------------

    private fun samples(request: SessionRequest, path: WirelessPath?, stage: ProgressStage?): List<Long> {
        val p = profiles[keyOf(request)] ?: return emptyList()
        val normalized = stage?.let(::normalize)
        val own = p.samples[sampleKey(pathName(path), normalized)]?.toList().orEmpty()
        if (own.size >= AdaptiveDeadlines.MIN_SAMPLES) return own
        // Too few on this path: the phone's other paths of the same transport tell more than nothing.
        if (request.transport == Transport.USB) return own
        val suffix = "/" + (normalized?.name ?: TOTAL)
        return p.samples.filterKeys { it.endsWith(suffix) && !it.startsWith(USB + "/") }.values.flatten()
    }

    private fun profile(request: SessionRequest): Profile {
        val key = keyOf(request)
        val p = profiles.getOrPut(key) { Profile(key, request.engine) }
        p.lastUsed = wallClock()
        sanitize(request.phoneName)?.let { p.phoneName = it }
        if (profiles.size > MAX_PROFILES) {
            profiles.values.minByOrNull { it.lastUsed }?.takeIf { it !== p }?.let { profiles.remove(it.key) }
        }
        return p
    }

    private fun problem(p: Profile, path: WirelessPath?, stage: ProgressStage?, kind: String, detail: String?) {
        p.failures[kind] = (p.failures[kind] ?: 0) + 1
        p.problems.addLast(ProblemRecord(wallClock(), pathName(path), stage?.let(::normalize), kind, sanitize(detail)))
        while (p.problems.size > MAX_PROBLEMS) p.problems.removeFirst()
    }

    private fun save() {
        runCatching { storage.write(encode()) }
        publish()
    }

    private fun publish() {
        _health.value = profiles.values.sortedByDescending { it.lastUsed }.map(::healthOf)
    }

    private fun healthOf(p: Profile): PhoneHealth {
        val paths = p.outcomes.map { (path, outcomes) ->
            PathHealth(path, outcomes.size, outcomes.count { it.ok },
                AdaptiveDeadlines.median(outcomes.filter { it.ok }.mapNotNull { it.millis }), outcomes.lastOrNull()?.ok)
        }
        val all = p.outcomes.values.flatten()
        val successes = all.count { it.ok }
        val best = paths.filter { it.successes > 0 }.maxWithOrNull(
            compareBy<PathHealth> { (it.successes + 1.0) / (it.attempts + 2.0) }
                .thenByDescending { it.medianTimeToPictureMillis ?: Long.MAX_VALUE },
        )
        return PhoneHealth(
            key = p.key, engine = p.engine, phoneName = p.phoneName,
            attempts = all.size, successes = successes,
            successRate = if (all.isEmpty()) null else successes.toFloat() / all.size,
            medianTimeToPictureMillis = AdaptiveDeadlines.median(all.filter { it.ok }.mapNotNull { it.millis }),
            bestPath = best?.path, paths = paths,
            lastProblems = p.problems.reversed().take(HEALTH_PROBLEMS),
        )
    }

    // A small line format (no JSON library needed by a host, and readable in a file dump).
    internal fun encode(): String = buildString {
        appendLine(HEADER)
        for (p in profiles.values) {
            appendLine(listOf("P", p.key, p.engine.name, clean(p.phoneName), p.lastUsed.toString()).joinToString(TAB))
            p.outcomes.forEach { (path, list) ->
                list.forEach { o ->
                    appendLine(listOf("O", path, o.atMillis.toString(), if (o.ok) "1" else "0", o.millis?.toString() ?: NONE,
                        o.kind ?: NONE, o.stage?.name ?: NONE).joinToString(TAB))
                }
            }
            p.samples.forEach { (key, list) -> appendLine(listOf("S", key, list.joinToString(",")).joinToString(TAB)) }
            p.failures.forEach { (kind, count) -> appendLine(listOf("F", kind, count.toString()).joinToString(TAB)) }
            p.problems.forEach { r ->
                appendLine(listOf("R", r.atMillis.toString(), r.path, r.stage?.name ?: NONE, r.kind, clean(r.detail)).joinToString(TAB))
            }
        }
    }

    private fun decode(text: String) {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return
        var current: Profile? = null
        for (line in lines.drop(1)) {
            val f = line.split(TAB)
            runCatching {
                when (f[0]) {
                    "P" -> current = Profile(f[1], Engine.valueOf(f[2])).also {
                        it.phoneName = f[3].takeUnless { n -> n == NONE }
                        it.lastUsed = f[4].toLong()
                        profiles[it.key] = it
                    }
                    "O" -> current?.outcomes?.push(f[1], AttemptOutcome(f[2].toLong(), f[3] == "1",
                        f[4].takeUnless { it == NONE }?.toLong(), f[5].takeUnless { it == NONE }, stageOf(f[6])), MAX_OUTCOMES)
                    "S" -> f[2].split(',').filter { it.isNotEmpty() }.forEach { current?.samples?.push(f[1], it.toLong(), MAX_SAMPLES) }
                    "F" -> current?.failures?.put(f[1], f[2].toInt())
                    "R" -> current?.problems?.addLast(ProblemRecord(f[1].toLong(), f[2], stageOf(f[3]), f[4], f[5].takeUnless { it == NONE }))
                }
            }
        }
    }

    private fun stageOf(name: String) = if (name == NONE) null else runCatching { ProgressStage.valueOf(name) }.getOrNull()

    private fun clean(text: String?): String = sanitize(text) ?: NONE

    /** One line, bounded, never the [NONE] marker: what is kept is exactly what a reload reads back. */
    private fun sanitize(text: String?): String? =
        text?.replace(Regex("[\\t\\r\\n]+"), " ")?.trim()?.take(MAX_DETAIL)?.takeUnless { it.isEmpty() || it == NONE }

    /** Appends to the ring under [key], keeping the newest [max]. */
    private fun <V> LinkedHashMap<String, ArrayDeque<V>>.push(key: String, value: V, max: Int) {
        val ring = getOrPut(key) { ArrayDeque() }
        ring.addLast(value)
        while (ring.size > max) ring.removeFirst()
    }

    companion object {
        const val MAX_OUTCOMES = 20
        const val MAX_SAMPLES = 15
        const val MAX_PROBLEMS = 12
        const val MAX_PROFILES = 16
        const val HEALTH_PROBLEMS = 5
        private const val NEUTRAL = 0.5
        private const val RECENT_FAILURE_PENALTY = 0.25
        private const val MAX_DETAIL = 200
        private const val HEADER = "carlink-profiles 1"
        private const val TAB = "\t"
        private const val NONE = "-"
        private const val TOTAL = "TOTAL"
        const val USB = "USB"

        fun pathName(path: WirelessPath?): String = path?.name ?: USB

        private fun sampleKey(path: String, stage: ProgressStage?) = "$path/" + (stage?.let(::normalize)?.name ?: TOTAL)

        /** [ProgressStage.PHONE_NOT_ANSWERING] is still the waiting phase. */
        fun normalize(stage: ProgressStage): ProgressStage =
            if (stage == ProgressStage.PHONE_NOT_ANSWERING) ProgressStage.WAITING_FOR_PHONE else stage

        /** One phone: its engine and Bluetooth address, or for USB without one its product name. */
        fun keyOf(request: SessionRequest): String {
            val id = request.phoneAddress?.let { KnownPhones.normalize(it) }
                ?: ("usb:" + (request.phoneName?.replace(Regex("\\s+"), " ")?.trim()?.lowercase()?.ifBlank { null } ?: "phone"))
            return "${request.engine.name}:$id"
        }

        /** The app's store, in its own SharedPreferences file. */
        fun sharedPreferences(context: Context): ConnectionProfiles {
            val prefs = context.applicationContext.getSharedPreferences("carlink_connection_profiles", Context.MODE_PRIVATE)
            return ConnectionProfiles(object : Storage {
                override fun read(): String? = prefs.getString("profiles", null)
                override fun write(text: String) { prefs.edit().putString("profiles", text).apply() }
            })
        }
    }
}
