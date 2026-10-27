package com.carlink.app.diagnostics

import com.carlink.core.connection.DeviceSignals
import com.carlink.core.connection.FailureReason
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.Transport
import com.carlink.core.diagnostics.LogMasking
import com.carlink.core.settings.WirelessPath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Report a problem on GitHub": a prefilled new-issue link for the repository's bug form
 * (`.github/ISSUE_TEMPLATE/bug_report.yml`). There is no GitHub sign-in in CarLink: the stereo's
 * browser opens the form and the driver taps Submit (the browser must be signed in once).
 *
 * Pure: everything comes in through [Facts], so the link is unit-tested
 * (GitHubIssueReportTest). [IssueReporter] gathers the facts on a device.
 *
 * Each query parameter is a form field's `id`; GitHub uses the `id` as "the canonical identifier
 * for the field in URL query parameter prefills" (docs.github.com, "Syntax for GitHub's form
 * schema"). Dropdowns only take a value that is exactly one of their option labels, otherwise
 * they stay empty, so the labels below must match the form word for word (a test reads the form).
 */
object GitHubIssueReport {
    const val NEW_ISSUE = "https://github.com/F-e-n-y-x/CarLink/issues/new"
    const val TEMPLATE = "bug_report.yml"

    /**
     * The whole link, after percent-encoding, stays at or under this many characters. GitHub does
     * not publish a number: a link over its server's limit gets "414 URI Too Long" (docs.github.com,
     * "Creating an issue" › "Creating an issue from a URL query"), and the common front-end and
     * proxy limit is 8 KB (8,192 bytes for the request line). 7,500 leaves room for the request
     * line around the link and works in every common browser (old Android WebViews included).
     */
    const val URL_BUDGET = 7_500

    /** The QR fallback's link: title, version, device and the error only, so a phone can scan it. */
    const val SHORT_URL_BUDGET = 2_000

    /** Engine problem lines are taken from this window before the report. */
    const val ENGINE_PROBLEM_WINDOW_MILLIS = 10 * 60_000L

    /** A single log line longer than this is cut (the full line is in Share log). */
    const val MAX_LINE_CHARS = 220

    // Option labels of the form's dropdowns, word for word.
    const val PROJECTION_CARPLAY = "CarPlay"
    const val PROJECTION_ANDROID_AUTO = "Android Auto"
    const val CONNECTION_USB = "USB cable"
    const val CONNECTION_WIFI_DIRECT = "Wireless, Wi-Fi Direct (Automatic)"
    const val CONNECTION_CAR_HOTSPOT = "Wireless, car hotspot"
    const val CONNECTION_SAME_WIFI = "Wireless, same Wi-Fi network"
    const val CONNECTION_NOT_SURE = "Not sure"

    /** The screen CarLink runs on. [widthDp]/[heightDp] use the panel's real density ([densityDpi]). */
    data class Device(
        val manufacturer: String,
        val model: String,
        val hardware: String,
        val board: String,
        val androidRelease: String,
        val sdk: Int,
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Int,
        val heightDp: Int,
        val densityDpi: Int = 160,
        /** The panel's own density before any `wm density` override, when it differs (0: same or unknown). */
        val stableDensityDpi: Int = 0,
    )

    /** A session the report describes (or the last problem in a phone's record when this process has no session). */
    data class Session(
        val engine: Engine?,
        val transport: Transport?,
        val path: WirelessPath?,
        val phoneName: String?,
        /** The session ended in a failure (or is blocked). */
        val failed: Boolean,
        val problem: LinkProblem? = null,
        val reason: FailureReason? = null,
        /** The engine's own detail string. */
        val detail: String? = null,
        /** carlink.log's session number (`#s<n>`), when known. */
        val sessionNumber: Int? = null,
        /** When it failed (or was last seen), epoch millis. */
        val atMillis: Long? = null,
        /** What the phone said about itself: "iPhone13,1" / "samsung SM-S928B", and "26.5.2" (iOS only). */
        val phoneModel: String? = null,
        val phoneOs: String? = null,
    )

    /** What the stereo Bluetooth probe found (ExternalBluetoothProbe). */
    data class StereoBluetooth(val classification: String, val evidence: String)

    data class Facts(
        val versionName: String,
        val versionCode: Int,
        val device: Device,
        /** The last session of each engine, newest first; the first fills the form's dropdowns. */
        val sessions: List<Session>,
        /** Home's message in the driver's words: its headline and sentence, when something failed. */
        val problemTitle: String?,
        val problemBody: String?,
        val carBrand: String?,
        val stereoBluetooth: StereoBluetooth?,
        /** The merged connection log, newest first (ConnectionLog.read: addresses already masked). */
        val log: List<LogLine>,
        /** Saved Wi-Fi and hotspot passwords: replaced wherever they appear, whatever the line says around them. */
        val secrets: List<String>,
        val nowMillis: Long,
        val timeZone: TimeZone = TimeZone.getDefault(),
        /** One line of the settings that shape a session (connection method, picture, grants, …). */
        val settings: String? = null,
        val hardware: DeviceSignals? = null,
    ) {
        val session: Session? get() = sessions.firstOrNull()
    }

    /** The finished report: the link, the short link for the QR code, and what the sheet shows. */
    data class Report(
        val title: String,
        /** Form field id to value, in the form's order (title first). */
        val fields: LinkedHashMap<String, String>,
        val url: String,
        val shortUrl: String,
        /** The log lines in the excerpt (without its headings), and how many were left for Share log. */
        val logLines: List<String>,
        val omittedLines: Int,
    )

    /** How the report is opened: in a browser, or as a QR code when the screen has none. */
    enum class Route { BROWSER, QR_CODE }

    fun route(browserAvailable: Boolean): Route = if (browserAvailable) Route.BROWSER else Route.QR_CODE

    fun build(facts: Facts): Report {
        val title = title(facts)
        val head = linkedMapOf("title" to title, "version" to version(facts), "screen" to screen(facts.device))
        phone(facts.session)?.let { head["phone"] = it }
        projection(facts.session)?.let { head["projection"] = it }
        connection(facts.session)?.let { head["connection"] = it }
        head["car_brand"] = facts.carBrand ?: "Other / Generic"
        for (key in head.keys.toList()) head[key] = clean(head.getValue(key), facts.secrets)

        // The short link (QR code): title, version, device and the error; a phone scans it from the stereo,
        // so it stays small (a denser code is harder to read off a glossy screen).
        val shortFields = LinkedHashMap(head.filterKeys { it == "title" || it == "version" || it == "screen" })
        shortFields["what_happened"] = clean(whatHappened(facts, short = true), facts.secrets)
        var shortUrl = url(shortFields)
        while (shortUrl.length > SHORT_URL_BUDGET && shortFields["what_happened"]!!.length > 40) {
            shortFields["what_happened"] = shortFields["what_happened"]!!.let { it.take(it.length * 3 / 4) + "…" }
            shortUrl = url(shortFields)
        }

        val fields = LinkedHashMap(head)
        // What happened may use at most half the budget (its least important blocks go first); the log gets the rest.
        var what = clean(whatHappened(facts, short = false, budget = URL_BUDGET / 2), facts.secrets)
        while (encode(what).length > URL_BUDGET / 2) what = what.take(what.length * 3 / 4) + "…"
        fields["what_happened"] = what
        val room = URL_BUDGET - url(fields).length - "&log=".length
        val excerpt = logExcerpt(facts, room)
        fields["log"] = excerpt.text
        return Report(title, fields, url(fields), shortUrl, excerpt.lines, excerpt.omitted)
    }

    // ---- Fields --------------------------------------------------------------------------

    fun title(facts: Facts): String {
        val s = facts.session
        if (s == null || !s.failed) return "Report from Diagnostics"
        val parts = listOfNotNull(
            s.engine?.let(::engineName),
            when (s.transport) { Transport.USB -> "USB"; Transport.WIRELESS -> "wireless"; null -> null },
        )
        val what = facts.problemTitle?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
            ?: s.problem?.name?.lowercase(Locale.ROOT)?.replace('_', ' ') ?: "unknown problem"
        return (parts + "couldn't connect: $what").joinToString(" · ")
    }

    fun version(facts: Facts) = "${facts.versionName} (${facts.versionCode})"

    fun screen(d: Device): String =
        "${d.manufacturer} ${d.model} (hardware ${d.hardware}, board ${d.board}), Android ${d.androidRelease} (SDK ${d.sdk}), " +
            "${d.widthPx}x${d.heightPx} px at ${d.densityDpi} dpi" +
            (if (d.stableDensityDpi > 0 && d.stableDensityDpi != d.densityDpi) " (panel ${d.stableDensityDpi} dpi)" else "") +
            " = ${d.widthDp}x${d.heightDp} dp"

    /** The form's phone field: the model and OS version the phone reported, else what is known and what to add. */
    fun phone(s: Session?): String? {
        if (s == null) return null
        val model = s.phoneModel?.let(::phoneLabel)
        val name = phoneLabel(s.phoneName)
        return when (s.engine) {
            Engine.CARPLAY -> (model ?: name ?: "iPhone") + (s.phoneOs?.let { ", iOS $it" } ?: if (model == null) " (add its model and iOS version)" else " (add its iOS version)")
            Engine.ANDROID_AUTO -> (model ?: name ?: "Android phone") + (s.phoneOs?.let { ", Android $it" } ?: " (add its Android version)")
            null -> name?.let { "$it (add its iOS or Android version)" }
        }
    }

    /**
     * A phone's name without its owner: "Ayush's S25 Ultra" → "S25 Ultra", "iPhone de Carlos" → "iPhone".
     * Null when nothing is left.
     */
    fun phoneLabel(name: String?): String? {
        var n = LogMasking.mask(name?.trim() ?: return null).replace("[address]", "").trim()
        n = n.replace(POSSESSIVE_PREFIX, "").trim()
        APPLE_DEFAULT_NAME.find(n)?.let { n = it.groupValues[1] }
        return n.takeIf { it.isNotEmpty() }
    }

    /** A dropdown label of the form, or null to leave the dropdown for the reporter. */
    fun projection(s: Session?): String? = when (s?.engine) {
        Engine.CARPLAY -> PROJECTION_CARPLAY
        Engine.ANDROID_AUTO -> PROJECTION_ANDROID_AUTO
        null -> null
    }

    /** A dropdown label of the form, or null when there was no session. */
    fun connection(s: Session?): String? = when {
        s?.transport == null -> null
        s.transport == Transport.USB -> CONNECTION_USB
        else -> when (s.path) {
            WirelessPath.WIFI_DIRECT -> CONNECTION_WIFI_DIRECT
            WirelessPath.CAR_HOTSPOT -> CONNECTION_CAR_HOTSPOT
            WirelessPath.SAME_WIFI -> CONNECTION_SAME_WIFI
            null -> CONNECTION_NOT_SURE
        }
    }

    /**
     * What happened: Home's message, then each session labelled with its engine, transport and time
     * (the form's dropdowns describe the first), its timeline, the settings, the stereo's Bluetooth and
     * a performance block. Blocks are dropped least important first to stay within [budget] (encoded).
     */
    fun whatHappened(facts: Facts, short: Boolean, budget: Int = Int.MAX_VALUE): String {
        val s = facts.session
        val head = buildString {
            val home = listOfNotNull(facts.problemTitle?.trim(), facts.problemBody?.trim()).filter { it.isNotEmpty() }
            if (s?.failed == true && home.isNotEmpty()) {
                val said = (if (short) home.take(1) else home).joinToString(" ") { if (it.last() in ".!?…") it else "$it." }
                append("Home said: ").append(said).append("\n\n")
            } else if (s?.failed != true) {
                append("No failure on screen; reported from Diagnostics.\n\n")
            }
        }
        // (priority, text) in display order; a lower priority number is kept longer.
        val blocks = ArrayList<Pair<Int, String>>()
        val sessions = if (short) facts.sessions.take(1) else facts.sessions
        sessions.forEachIndexed { i, session ->
            blocks += (if (i == 0) 0 else 4) to sessionBlock(facts, session, short, labelled = facts.sessions.size > 1 && i == 0)
        }
        if (!short) facts.settings?.let { blocks += 1 to "Settings: $it" }
        facts.stereoBluetooth?.let { blocks += 2 to "Stereo Bluetooth: ${it.classification}" + if (short) "" else " (${it.evidence})" }
        if (!short) performance(facts)?.let { blocks += 3 to it }
        val tail = if (short) "" else "\nWhat I did and what I expected: "
        var used = encode(head + "Technical detail:\n" + tail).length
        val kept = HashSet<Int>()
        for (i in blocks.indices.sortedBy { blocks[it].first }) {
            val cost = encode(blocks[i].second + "\n").length
            if (used + cost <= budget || blocks[i].first == 0) { used += cost; kept += i }
        }
        return buildString {
            append(head)
            if (kept.isNotEmpty()) {
                append("Technical detail:\n")
                blocks.forEachIndexed { i, b -> if (i in kept) append(b.second).append('\n') }
            }
            append(tail)
        }.trimEnd()
    }

    private fun sessionBlock(facts: Facts, s: Session, short: Boolean, labelled: Boolean): String {
        val time = s.atMillis?.let { timestamp(it, facts.timeZone) }
        val header = "Session " + listOfNotNull(
            s.sessionNumber?.let { "#s$it" },
            s.engine?.let(::engineName),
            listOfNotNull(s.transport?.name, s.path?.name).joinToString(" ").takeIf { it.isNotEmpty() },
            if (s.failed) "failed" else "no failure",
            time,
        ).joinToString(" · ") + if (labelled) " (the form's dropdowns describe this one)" else ""
        val lines = listOfNotNull(
            (s.phoneModel ?: s.phoneOs)?.let { "Phone: " + listOfNotNull(s.phoneModel, s.phoneOs?.let { v -> "OS $v" }).joinToString(", ") },
            s.problem?.let { "LinkProblem: $it" },
            s.reason?.let { "FailureReason: $it" },
            s.detail?.takeIf { it.isNotBlank() }?.let { "Engine detail: " + (if (short) it.take(120) else it.take(600)) },
            if (short) null else s.engine?.let { timeline(facts.log, it) }?.let { "Timeline: $it" },
        )
        return (listOf(header) + lines.map { "- $it" }).joinToString("\n")
    }

    // ---- Session timeline, phone facts and performance -----------------------------------

    /** CarLink's newest "start <ENGINE> …" line for [engine] (newest-first [log]). */
    fun lastStart(log: List<LogLine>, engine: Engine): LogLine? =
        log.firstOrNull { it.source == LogSource.CARLINK && START.find(it.text)?.groupValues?.get(1) == engine.name }

    fun sessionNumber(start: LogLine): Int? = START.find(start.text)?.groupValues?.get(2)?.toIntOrNull()

    /**
     * The last [engine] session as "step +ms" steps from CarLink's start line: the engine's
     * WIRED_/WIRELESS_TIMING milestones and CarLink's lines of that session, until the next start.
     */
    fun timeline(log: List<LogLine>, engine: Engine): String? {
        val start = lastStart(log, engine) ?: return null
        val tag = Regex("#s${sessionNumber(start) ?: return null}\\b")
        val end = log.filter { it.source == LogSource.CARLINK && it.timeMillis > start.timeMillis && START.containsMatchIn(it.text) }
            .minOfOrNull { it.timeMillis } ?: Long.MAX_VALUE
        val engineSource = if (engine == Engine.CARPLAY) LogSource.CARPLAY else LogSource.ANDROID_AUTO
        val steps = log.asReversed().filter { it.timeMillis >= start.timeMillis && it.timeMillis < end }.mapNotNull { line ->
            val name = when {
                line.source == LogSource.CARLINK && tag.containsMatchIn(line.text) ->
                    line.text.removePrefix("CarLink: ").replace(tag, "").trim().let { if (it.length > 48) it.take(47) + "…" else it }
                line.source == engineSource -> TIMING_STEP.find(line.text)?.groupValues?.get(2) ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            "$name +${line.timeMillis - start.timeMillis}ms"
        }
        if (steps.isEmpty()) return null
        return (if (steps.size > MAX_STEPS) listOf("…") + steps.takeLast(MAX_STEPS) else steps).joinToString(" · ")
    }

    /** What the phone told the engine about itself since [sinceMillis]: model and OS version (CarPlay: AirPlay SETUP; Android Auto: service discovery). */
    fun phoneFromLog(log: List<LogLine>, engine: Engine, sinceMillis: Long): Pair<String?, String?>? {
        fun known(v: String?) = v?.trim()?.takeUnless { it.isEmpty() || it == "not_reported" }
        for (line in log) {
            if (line.timeMillis < sinceMillis) break
            if (engine == Engine.CARPLAY && line.source == LogSource.CARPLAY) {
                val m = IPHONE_PEER.find(line.text) ?: continue
                return (known(m.groupValues[1]) to known(m.groupValues[2])).takeIf { it.first != null || it.second != null }
            }
            if (engine == Engine.ANDROID_AUTO && line.source == LogSource.ANDROID_AUTO) {
                val m = ANDROID_PEER.find(line.text) ?: continue
                return phoneLabel(known(m.groupValues[1]))?.let { it to null }
            }
        }
        return null
    }

    /** Hardware, then the newest decoder, negotiated picture and frame-rate lines of each engine. */
    fun performance(facts: Facts): String? {
        val out = ArrayList<String>()
        facts.hardware?.let { h ->
            out += "Hardware: " + listOfNotNull(
                "RAM ${h.totalRamMb} MB" + if (h.lowRamDevice) " (low-RAM device)" else "",
                "${h.cpuCores} CPU cores" + if (h.cpuMaxMhz > 0) " up to ${h.cpuMaxMhz} MHz" else "",
                h.gpuRenderer?.let { "GPU $it" },
            ).joinToString(", ")
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = facts.timeZone }
        for ((pattern, count) in PERFORMANCE) {
            facts.log.filter { pattern.containsMatchIn(it.text) }.take(count).asReversed().forEach { line ->
                val text = line.text.substringAfter(" | ").let { if (it.length > 170) it.take(169) + "…" else it }
                out += "${ConnectionLog.sourceTag(line.source)} ${time.format(Date(line.timeMillis))} $text"
            }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" }?.let { "Performance:\n$it" }
    }

    // ---- Log -----------------------------------------------------------------------------

    class Excerpt(val text: String, val lines: List<String>, val omitted: Int)

    /**
     * The log field: one fenced `text` block, filled in priority order until [room] (encoded
     * characters) is used: (a) the last session's CarLink lines (`#s<n>`), (b) engine error,
     * warning and failure lines from the last 10 minutes, (c) the timing lines, (d) the newest
     * lines of the merged log. A lower priority is the first to lose lines; each section keeps its
     * newest lines and is printed oldest first, with repeated lines collapsed ([collapse]). Ends
     * with how many lines only the full log has, and how to add it.
     */
    fun logExcerpt(facts: Facts, room: Int): Excerpt {
        val log = facts.log.distinct()
        val zone = facts.timeZone
        val sections = listOf(
            "last session${sessionTag(log)?.let { " #s$it" } ?: ""}, CarLink" to sessionLines(log),
            "engine errors and warnings, last 10 min" to log.filter {
                it.source != LogSource.CARLINK && it.timeMillis >= facts.nowMillis - ENGINE_PROBLEM_WINDOW_MILLIS && isFailureLine(it)
            },
            "timing" to log.filter { TIMING.containsMatchIn(it.text) },
            "latest lines" to log,
        )
        val open = "```text\n"
        val header = "CarLink ${facts.versionName} log excerpt, ${timestamp(facts.nowMillis, zone)}; " +
            "CP = CarPlay, AA = Android Auto, CL = CarLink\n"
        // The footer's count is not known yet: reserve room for the longest one.
        var used = encode(open + header + footer(log.size) + "```").length
        val taken = HashSet<LogLine>()
        val chosen = ArrayList<Pair<String, MutableList<Repeated>>>()
        var full = false
        for ((name, lines) in sections) {
            if (full) break
            val heading = "-- $name --\n"
            var headingPaid = false
            val picked = mutableListOf<Repeated>()
            for (group in collapse(lines.filter { it !in taken })) { // newest first
                val cost = encode(format(group, facts.secrets, zone) + "\n").length + if (headingPaid) 0 else encode(heading).length
                if (used + cost > room) { full = true; break }
                used += cost
                headingPaid = true
                taken += group.lines
                picked += group
            }
            if (picked.isNotEmpty()) chosen += name to picked
        }
        val rendered = ArrayList<String>()
        val omitted = log.size - taken.size
        val text = buildString {
            append(open).append(header)
            for ((name, picked) in chosen) {
                append("-- ").append(name).append(" --\n")
                picked.sortedBy { it.lastMillis }.forEach { group ->
                    val s = format(group, facts.secrets, zone)
                    rendered += s
                    append(s).append('\n')
                }
            }
            append(footer(omitted))
            append("```")
        }
        return Excerpt(text, rendered, omitted)
    }

    private fun footer(omitted: Int): String =
        (if (omitted > 0) "… $omitted more lines in the full log.\n" else "") +
            "Full log: in CarLink open Settings › General › Diagnostics, tap Copy full log (or Share log), then paste or attach it below.\n"

    /** Lines with the same source, level and text (newest first), as one entry each. */
    class Repeated(val lines: List<LogLine>) {
        val newest: LogLine get() = lines.first()
        val firstMillis: Long get() = lines.minOf { it.timeMillis }
        val lastMillis: Long get() = lines.maxOf { it.timeMillis }
    }

    /** Groups repeated lines (same text after the timestamp), newest group first. */
    fun collapse(lines: List<LogLine>): List<Repeated> =
        lines.groupBy { Triple(it.source, it.level, it.text) }.values.map(::Repeated)

    /** One excerpt entry: a line, or a repeated line as "text ×N (first–last)". */
    fun format(group: Repeated, secrets: List<String>, zone: TimeZone): String {
        val line = format(group.newest, secrets, zone)
        if (group.lines.size == 1) return line
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = zone }
        return "$line ×${group.lines.size} (${time.format(Date(group.firstMillis))}–${time.format(Date(group.lastMillis))})"
    }

    /** The newest `#s<n>` in CarLink's lines. */
    fun sessionTag(log: List<LogLine>): Int? =
        log.firstNotNullOfOrNull { line -> if (line.source == LogSource.CARLINK) SESSION_TAG.find(line.text)?.groupValues?.get(1)?.toIntOrNull() else null }

    /**
     * CarLink's lines of the newest session (newest first). Session numbers restart with the
     * process, so the walk stops at a higher number (an older process) or 30 minutes before the
     * session's last line.
     */
    fun sessionLines(log: List<LogLine>): List<LogLine> {
        val tag = sessionTag(log) ?: return emptyList()
        val out = ArrayList<LogLine>()
        var last: Long? = null
        for (line in log) {
            if (line.source != LogSource.CARLINK) continue
            val n = SESSION_TAG.find(line.text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n == tag) {
                if (last == null) last = line.timeMillis
                if (line.timeMillis < last - 30 * 60_000L) break
                out += line
            } else if (n > tag && last != null) break
        }
        return out
    }

    private fun isFailureLine(line: LogLine): Boolean =
        line.isProblem || FAILURE_WORDS.containsMatchIn(line.text)

    /** One excerpt line: time, source, level and the text, masked again and cut to [MAX_LINE_CHARS]. */
    fun format(line: LogLine, secrets: List<String>, zone: TimeZone): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }.format(Date(line.timeMillis))
        val text = clean(line.text, secrets).let { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS - 1) + "…" else it }
        return "$time ${ConnectionLog.sourceTag(line.source)} ${line.level ?: '-'} $text"
    }

    /**
     * Masks addresses and `password=…`-style values ([LogMasking]), serial numbers, owners' names in
     * phone names ("Ayush's S25 Ultra" → "S25 Ultra") and every saved Wi-Fi password wherever it appears. The log is already masked when it is read; this is the last check
     * before anything leaves the stereo.
     */
    fun clean(text: String, secrets: List<String>): String {
        var out = LogMasking.mask(text).replace(POSSESSIVE, "").replace(SERIAL) { "${it.groupValues[1]}${it.groupValues[2]}[hidden]" }
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { out = out.replace(it, "[hidden]") }
        return out.replace("\r", "")
    }

    // ---- URL -----------------------------------------------------------------------------

    fun url(fields: Map<String, String>): String = buildString {
        append(NEW_ISSUE).append("?template=").append(encode(TEMPLATE))
        fields.forEach { (key, value) -> append('&').append(key).append('=').append(encode(value)) }
    }

    /**
     * Query-value encoding as browsers send forms (application/x-www-form-urlencoded): unreserved
     * characters stay, a space becomes `+`, everything else is UTF-8 percent-encoded. `+` keeps a
     * log line about a third shorter than `%20` would.
     */
    fun encode(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            when {
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> out.append(c.toChar())
                c == ' '.code -> out.append('+')
                else -> out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun timestamp(millis: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply { timeZone = zone }.format(Date(millis))

    private fun engineName(engine: Engine) = when (engine) { Engine.CARPLAY -> "CarPlay"; Engine.ANDROID_AUTO -> "Android Auto" }

    private const val HEX = "0123456789ABCDEF"
    private val SESSION_TAG = Regex("#s(\\d+)\\b")
    private val TIMING = Regex("\\b(WIRED|WIRELESS)_TIMING\\b")
    private val START = Regex("^CarLink: start (CARPLAY|ANDROID_AUTO) \\S+.*#s(\\d+)\\b")
    private val TIMING_STEP = Regex("\\b(WIRED|WIRELESS)_TIMING (\\S+)")
    private val IPHONE_PEER = Regex("Phone display peer model=(\\S+) ios=(\\S+)")
    private val ANDROID_PEER = Regex("Service Discovery Request: .*? brand=(.+)$")
    // An owner's name before a phone name: "Ayush's S25 Ultra", "Carlos’ iPhone" (not CarLink's own words).
    private val POSSESSIVE = Regex("\\b(?!CarLink)\\p{Lu}[\\p{L}.-]{1,24}(?:['’]s|s['’]) (?=\\S)")
    private val POSSESSIVE_PREFIX = Regex("^.*['’]s?\\s+")
    private val APPLE_DEFAULT_NAME = Regex("^(iPhone|iPad)\\b.*", RegexOption.IGNORE_CASE)
    private val SERIAL = Regex("(?i)\\b(serial(?:[ _-]?(?:no|number))?|ro\\.serialno)(\\s*[=:]\\s*)[^\\s,;)]+")
    private const val MAX_STEPS = 24

    /** Lines the performance block takes (newest [Int] of each), CarPlay then Android Auto. */
    private val PERFORMANCE = listOf(
        Regex("^Display request selected=") to 1,
        Regex("^Video: decoder=") to 1,
        Regex("^Video: video stats") to 3,
        Regex("\\[ServiceDiscovery] Negotiating a profile") to 1,
        Regex("Throughput over \\d+ms") to 3,
    )
    private val FAILURE_WORDS = Regex("(?i)\\b(fail(ed|ure)?|error|exception|timed? ?out|refused|abort(ed)?)\\b")
}
