package com.carlink.app.diagnostics

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

    /** The screen CarLink runs on. */
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
    )

    /** The current or last session (or the last problem in a phone's record when this process has no session). */
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
    )

    /** What the stereo Bluetooth probe found (ExternalBluetoothProbe). */
    data class StereoBluetooth(val classification: String, val evidence: String)

    data class Facts(
        val versionName: String,
        val versionCode: Int,
        val device: Device,
        val session: Session?,
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
    )

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
        var what = clean(whatHappened(facts, short = false), facts.secrets)
        // What happened may use at most half the budget; the log gets the rest.
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
            "${d.widthPx}x${d.heightPx} px, ${d.widthDp}x${d.heightDp} dp"

    fun phone(s: Session?): String? {
        if (s == null) return null
        val kind = when (s.engine) { Engine.CARPLAY -> "iPhone"; Engine.ANDROID_AUTO -> "Android phone"; null -> null }
        val name = s.phoneName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            name != null && kind != null -> "$name ($kind; add its iOS or Android version)"
            name != null -> name
            kind != null -> "$kind (add its model and version)"
            else -> null
        }
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

    fun whatHappened(facts: Facts, short: Boolean): String = buildString {
        val s = facts.session
        val home = listOfNotNull(facts.problemTitle?.trim(), facts.problemBody?.trim()).filter { it.isNotEmpty() }
        if (s?.failed == true && home.isNotEmpty()) {
            val said = (if (short) home.take(1) else home).joinToString(" ") { if (it.last() in ".!?…") it else "$it." }
            append("Home said: ").append(said).append("\n\n")
        } else if (s?.failed != true) {
            append("No failure on screen; reported from Diagnostics.\n\n")
        }
        val tech = mutableListOf<String>()
        if (s != null) {
            val time = s.atMillis?.let { timestamp(it, facts.timeZone) }
            tech += listOfNotNull(
                "Session: " + listOfNotNull(
                    s.sessionNumber?.let { "#s$it" },
                    s.engine?.let(::engineName),
                    s.transport?.name,
                    s.path?.name,
                    if (s.failed) "failed" else "no failure",
                ).joinToString(", ") + (time?.let { " at $it" } ?: ""),
                s.problem?.let { "LinkProblem: $it" },
                s.reason?.let { "FailureReason: $it" },
                s.detail?.takeIf { it.isNotBlank() }?.let { "Engine detail: " + (if (short) it.take(120) else it.take(600)) },
            )
        }
        facts.stereoBluetooth?.let { tech += "Stereo Bluetooth: ${it.classification}" + if (short) "" else " (${it.evidence})" }
        if (tech.isNotEmpty()) {
            append("Technical detail:\n")
            tech.forEach { append("- ").append(it).append('\n') }
        }
        if (!short) append("\nWhat I did and what I expected: ")
    }.trimEnd()

    // ---- Log -----------------------------------------------------------------------------

    class Excerpt(val text: String, val lines: List<String>, val omitted: Int)

    /**
     * The log field: one fenced `text` block, filled in priority order until [room] (encoded
     * characters) is used: (a) the last session's CarLink lines (`#s<n>`), (b) engine error,
     * warning and failure lines from the last 10 minutes, (c) the timing lines, (d) the newest
     * lines of the merged log. A lower priority is the first to lose lines; each section keeps its
     * newest lines and is printed oldest first. Ends with how many lines only Share log has.
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
        val footerMax = "… ${log.size} more lines in Share log\n```"
        var used = encode(open + header + footerMax).length
        val taken = LinkedHashSet<LogLine>()
        val chosen = ArrayList<Pair<String, MutableList<LogLine>>>()
        var full = false
        for ((name, lines) in sections) {
            if (full) break
            val heading = "-- $name --\n"
            var headingPaid = false
            val picked = mutableListOf<LogLine>()
            for (line in lines) { // newest first
                if (line in taken) continue
                val cost = encode(format(line, facts.secrets, zone) + "\n").length + if (headingPaid) 0 else encode(heading).length
                if (used + cost > room) { full = true; break }
                used += cost
                headingPaid = true
                taken += line
                picked += line
            }
            if (picked.isNotEmpty()) chosen += name to picked
        }
        val rendered = ArrayList<String>()
        val text = buildString {
            append(open).append(header)
            for ((name, picked) in chosen) {
                append("-- ").append(name).append(" --\n")
                picked.sortedBy { it.timeMillis }.forEach { line ->
                    val s = format(line, facts.secrets, zone)
                    rendered += s
                    append(s).append('\n')
                }
            }
            val omitted = log.size - taken.size
            if (omitted > 0) append("… ").append(omitted).append(" more lines in Share log\n")
            append("```")
        }
        return Excerpt(text, rendered, log.size - taken.size)
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
     * Masks addresses and `password=…`-style values ([LogMasking]), and every saved Wi-Fi password
     * wherever it appears. The log is already masked when it is read; this is the last check
     * before anything leaves the stereo.
     */
    fun clean(text: String, secrets: List<String>): String {
        var out = LogMasking.mask(text)
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
    private val FAILURE_WORDS = Regex("(?i)\\b(fail(ed|ure)?|error|exception|timed? ?out|refused|abort(ed)?)\\b")
}
