package com.carlink.app.diagnostics

import com.carlink.core.connection.FailureReason
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.Transport
import com.carlink.core.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.util.TimeZone

class GitHubIssueReportTest {
    private val now = 1_760_500_000_000L
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private val device = GitHubIssueReport.Device("FYT", "UIS7862", "ums512", "s9863a", "10", 29, 1280, 720, 1280, 720)

    private val failedSession = GitHubIssueReport.Session(Engine.ANDROID_AUTO, Transport.WIRELESS, WirelessPath.WIFI_DIRECT, "Pixel 8",
        failed = true, problem = LinkProblem.PHONE_DID_NOT_ANSWER, reason = FailureReason.PHONE_DID_NOT_ANSWER,
        detail = "no RFCOMM answer within 30000 ms", sessionNumber = 7, atMillis = now - 20_000)

    private fun facts(
        session: GitHubIssueReport.Session? = failedSession,
        log: List<LogLine> = emptyList(),
        secrets: List<String> = emptyList(),
        title: String? = "Your phone didn’t answer",
        others: List<GitHubIssueReport.Session> = emptyList(),
    ) = GitHubIssueReport.Facts("1.2.0", 10200, device, listOfNotNull(session) + others, title,
        "Unlock your phone, make sure its Bluetooth is on and connected to this screen, then try again.",
        "Hyundai", GitHubIssueReport.StereoBluetooth("FYT_MODULE", "signals FYT_SETTING,FYT_SERIAL_PORT; DiAuto: none"),
        log, secrets, now, utc)

    /** Query parameters of [url], decoded as a browser form would be. */
    private fun params(url: String): Map<String, String> = url.substringAfter('?').split('&').associate { pair ->
        pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
    }

    private fun line(ageMillis: Long, source: LogSource, text: String, level: Char? = null) =
        LogLine(now - ageMillis, source, level, text)

    @Test fun buildsTheNewIssueLinkWithTheFormsFieldIds() {
        val report = GitHubIssueReport.build(facts(log = listOf(line(1_000, LogSource.CARLINK, "SessionManager: ANDROID_AUTO failed #s7"))))
        assertTrue(report.url.startsWith("https://github.com/F-e-n-y-x/CarLink/issues/new?template=bug_report.yml&title="))
        val p = params(report.url)
        assertEquals(listOf("template", "title", "version", "screen", "phone", "projection", "connection", "car_brand", "what_happened", "log"),
            p.keys.toList())
        assertEquals("Android Auto · wireless · couldn't connect: Your phone didn’t answer", p["title"])
        assertEquals("1.2.0 (10200)", p["version"])
        assertEquals("FYT UIS7862 (hardware ums512, board s9863a), Android 10 (SDK 29), 1280x720 px at 160 dpi = 1280x720 dp", p["screen"])
        assertEquals("Pixel 8 (add its Android version)", p["phone"])
        assertEquals("Android Auto", p["projection"])
        assertEquals("Wireless, Wi-Fi Direct (Automatic)", p["connection"])
        assertEquals("Hyundai", p["car_brand"])
        val what = p.getValue("what_happened")
        assertTrue(what, what.startsWith("Home said: Your phone didn’t answer. Unlock your phone"))
        listOf("#s7", "Android Auto", "WIFI_DIRECT", "LinkProblem: PHONE_DID_NOT_ANSWER", "FailureReason: PHONE_DID_NOT_ANSWER",
            "Engine detail: no RFCOMM answer within 30000 ms", "2025-10-15 03:46:20 +0000", "Stereo Bluetooth: FYT_MODULE",
            "FYT_SERIAL_PORT").forEach { assertTrue(it, what.contains(it)) }
        val log = p.getValue("log")
        assertTrue(log, log.startsWith("```text\n") && log.endsWith("\n```"))
        assertTrue(log, log.contains("-- last session #s7, CarLink --\n") && log.contains("SessionManager: ANDROID_AUTO failed #s7"))
    }

    @Test fun titleWithoutAFailureSaysDiagnostics() {
        assertEquals("Report from Diagnostics", GitHubIssueReport.title(facts(session = null)))
        assertEquals("Report from Diagnostics", GitHubIssueReport.title(facts(session = failedSession.copy(failed = false))))
        assertEquals("CarPlay · USB · couldn't connect: Couldn’t start CarPlay",
            GitHubIssueReport.title(facts(session = failedSession.copy(engine = Engine.CARPLAY, transport = Transport.USB),
                title = "Couldn’t start CarPlay.")))
        // No session: nothing is guessed for the phone or the dropdowns.
        val p = params(GitHubIssueReport.build(facts(session = null)).url)
        assertFalse(p.containsKey("phone") || p.containsKey("projection") || p.containsKey("connection"))
        assertTrue(p.getValue("what_happened").startsWith("No failure on screen"))
    }

    @Test fun encodesLikeABrowserForm() {
        assertEquals("a+b%2Bc%2F%C3%A9%0A%23%26%3D%3F%60-_.~", GitHubIssueReport.encode("a b+c/é\n#&=?`-_.~"))
        val text = "Line 1 & 2 = 100% \"quoted\" #s3\n```\n· …"
        assertEquals(text, URLDecoder.decode(GitHubIssueReport.encode(text), "UTF-8"))
        // Nothing outside the unreserved set, '+' and '%' survives encoding.
        assertTrue(GitHubIssueReport.encode(text).all { it.isLetterOrDigit() || it in "+%-_.~" })
    }

    @Test fun dropdownValuesAreExactOptionLabelsOfTheForm() {
        val form = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, ".github/ISSUE_TEMPLATE/bug_report.yml") }.first { it.isFile }.readLines()
        fun options(id: String): Set<String> {
            val start = form.indexOfFirst { it.trim() == "id: $id" }
            check(start >= 0) { "no field $id" }
            val out = mutableSetOf<String>()
            var inOptions = false
            for (raw in form.drop(start + 1)) {
                val t = raw.trim()
                if (t.startsWith("- type:")) break
                if (t == "options:") { inOptions = true; continue }
                if (inOptions && t.startsWith("- ")) out += t.removePrefix("- ").trim().trim('"')
                else if (inOptions && t.isNotEmpty()) inOptions = false
            }
            return out
        }
        // Every value the builder can produce for the two dropdowns.
        val routes: List<Pair<Transport, WirelessPath?>> =
            listOf<Pair<Transport, WirelessPath?>>(Transport.USB to null, Transport.WIRELESS to null) +
                WirelessPath.entries.map { Transport.WIRELESS to it }
        val sessions: List<GitHubIssueReport.Session> = Engine.entries.flatMap { e ->
            routes.map { (t, p) -> GitHubIssueReport.Session(e, t, p, "Phone", failed = true) }
        }
        val projections = sessions.mapNotNull { GitHubIssueReport.projection(it) }.toSet()
        val connections = sessions.mapNotNull { GitHubIssueReport.connection(it) }.toSet()
        assertEquals(setOf("CarPlay", "Android Auto"), projections)
        assertTrue("${options("projection")}", options("projection").containsAll(projections))
        assertEquals(5, connections.size)
        assertTrue("${options("connection")} vs $connections", options("connection").containsAll(connections))
        // The text fields the builder fills exist too.
        listOf("version", "screen", "phone", "car_brand", "what_happened", "log").forEach { id ->
            assertTrue(id, form.any { it.trim() == "id: $id" })
        }
    }

    @Test fun neverSendsAnAddressOrAWifiPassword() {
        val secret = "hunter2-Car!Pass"
        val mac = "AA:BB:CC:DD:EE:FF"
        val log = listOf(
            line(1_000, LogSource.CARLINK, "Bluetooth: Pixel 8 ($mac) connected #s7"),
            line(2_000, LogSource.ANDROID_AUTO, "Joining hotspot, password=$secret", 'W'),
            line(3_000, LogSource.CARPLAY, "credentials ready: $secret failed", 'E'),
            line(4_000, LogSource.CARLINK, "Same Wi-Fi saved psk: \"$secret\""),
        )
        val session = failedSession.copy(phoneName = "Pixel 8 $mac", detail = "peer ${mac.lowercase()} refused passphrase=$secret")
        val report = GitHubIssueReport.build(facts(session = session, log = log, secrets = listOf(secret)))
        for (url in listOf(report.url, report.shortUrl)) {
            val decoded = URLDecoder.decode(url, "UTF-8")
            assertFalse(decoded, decoded.contains(secret))
            assertFalse(decoded, decoded.contains(mac, ignoreCase = true))
            assertFalse(url.contains(GitHubIssueReport.encode(secret)))
        }
        val decoded = URLDecoder.decode(report.url, "UTF-8")
        assertTrue(decoded.contains("[address]") && decoded.contains("[hidden]"))
        // Without the saved list, `password=`-style values are still masked by LogMasking.
        val unlisted = URLDecoder.decode(GitHubIssueReport.build(facts(log = log.take(2))).url, "UTF-8")
        assertFalse(unlisted.contains(secret))
    }

    /** A long, busy log: 400 lines of each kind of section, newest first. */
    private fun busyLog(): List<LogLine> {
        val lines = ArrayList<LogLine>()
        repeat(400) { i ->
            val age = 1_000L + i * 1_000
            lines += line(age, LogSource.CARLINK, "SessionManager: step $i of the session #s7")
            lines += line(age + 100, LogSource.ANDROID_AUTO, "Handshake error $i: SSL read returned -1", 'E')
            lines += line(age + 200, LogSource.CARPLAY, "WIRELESS_TIMING phase-$i start+${i}ms session+${i}ms")
            lines += line(age + 300, LogSource.CARPLAY, "ordinary line $i")
        }
        return lines
    }

    @Test fun staysWithinTheBudgetAndSaysHowManyLinesShareLogHas() {
        val log = busyLog()
        val report = GitHubIssueReport.build(facts(log = log))
        assertTrue("${report.url.length}", report.url.length <= GitHubIssueReport.URL_BUDGET)
        assertTrue("${report.url.length}", report.url.length > GitHubIssueReport.URL_BUDGET - 400)
        val excerpt = params(report.url).getValue("log")
        assertEquals(log.size - report.logLines.size, report.omittedLines)
        assertTrue(excerpt, excerpt.contains("… ${report.omittedLines} more lines in the full log.\nFull log: in CarLink open Settings"))
        assertTrue(excerpt, excerpt.endsWith("paste or attach it below.\n```"))
        // The session's lines come first and are printed oldest first.
        assertTrue(excerpt.contains("-- last session #s7, CarLink --"))
        val steps = Regex("step (\\d+) of").findAll(excerpt).map { it.groupValues[1].toInt() }.toList()
        assertEquals(steps.sortedDescending(), steps)
        assertTrue(steps.contains(0))
    }

    @Test fun trimsTheLowestPriorityFirst() {
        val log = listOf(
            line(1_000, LogSource.CARPLAY, "ordinary newest line"),
            line(2_000, LogSource.CARPLAY, "WIRED_TIMING first-frame plug+3400ms session+3100ms"),
            line(3_000, LogSource.ANDROID_AUTO, "Connection failed: phone closed the socket", 'E'),
            line(4_000, LogSource.CARLINK, "SessionManager: ANDROID_AUTO failed #s7"),
            line(11 * 60_000L, LogSource.ANDROID_AUTO, "Old error outside the window", 'E'),
        )
        val f = facts(log = log)
        fun sections(room: Int) = GitHubIssueReport.logExcerpt(f, room).text.lines().filter { it.startsWith("-- ") }
        val all = sections(100_000)
        assertEquals(listOf("-- last session #s7, CarLink --", "-- engine errors and warnings, last 10 min --", "-- timing --",
            "-- latest lines --"), all)
        // The old error appears only among the latest lines, never as a recent engine problem.
        val full = GitHubIssueReport.logExcerpt(f, 100_000).text
        assertTrue(full.indexOf("Old error") > full.indexOf("-- latest lines --"))
        // Shrinking the room drops (d), then (c), then (b), and keeps (a) to the end.
        val sizes = (200..2_000 step 10).map { sections(it) }
        val seen = sizes.map { it.size }.distinct()
        assertEquals(listOf(0, 1, 2, 3, 4), seen)
        sizes.forEach { s -> assertEquals(all.take(s.size), s) }
    }

    @Test fun shortLinkForTheQrCodeHasNoLog() {
        val report = GitHubIssueReport.build(facts(log = busyLog(),
            session = failedSession.copy(detail = "x".repeat(5_000))))
        assertTrue("${report.shortUrl.length}", report.shortUrl.length < GitHubIssueReport.SHORT_URL_BUDGET)
        val p = params(report.shortUrl)
        assertNull(p["log"])
        assertNotNull(p["title"]); assertNotNull(p["version"]); assertNotNull(p["screen"])
        assertTrue(p.getValue("what_happened").contains("LinkProblem: PHONE_DID_NOT_ANSWER"))
    }

    @Test fun noBrowserFallsBackToTheQrCode() {
        assertEquals(GitHubIssueReport.Route.BROWSER, GitHubIssueReport.route(browserAvailable = true))
        assertEquals(GitHubIssueReport.Route.QR_CODE, GitHubIssueReport.route(browserAvailable = false))
    }

    @Test fun sessionLinesStopAtAnOlderProcess() {
        val log = listOf(
            line(1_000, LogSource.CARLINK, "connected on USB in 3400 ms #s2"),
            line(2_000, LogSource.CARLINK, "starting #s2"),
            line(3_000, LogSource.CARLINK, "earlier session #s1"),
            line(4_000, LogSource.CARLINK, "older process #s5"),
            line(5_000, LogSource.CARLINK, "older process, same number #s2"),
        )
        assertEquals(2, GitHubIssueReport.sessionTag(log))
        assertEquals(listOf("connected on USB in 3400 ms #s2", "starting #s2"), GitHubIssueReport.sessionLines(log).map { it.text })
    }

    @Test fun screenShowsTheRealDensity() {
        assertEquals("A B (hardware h, board b), Android 14 (SDK 34), 1920x1200 px at 240 dpi (panel 320 dpi) = 1280x800 dp",
            GitHubIssueReport.screen(GitHubIssueReport.Device("A", "B", "h", "b", "14", 34, 1920, 1200, 1280, 800, 240, 320)))
    }

    @Test fun phoneFieldUsesWhatThePhoneReportedAndNoOwnerNames() {
        val iphone = GitHubIssueReport.Session(Engine.CARPLAY, Transport.USB, null, "iPhone", failed = false)
        assertEquals("iPhone (add its model and iOS version)", GitHubIssueReport.phone(iphone))
        assertEquals("iPhone13,1, iOS 26.5.2", GitHubIssueReport.phone(iphone.copy(phoneModel = "iPhone13,1", phoneOs = "26.5.2")))
        val android = GitHubIssueReport.Session(Engine.ANDROID_AUTO, Transport.WIRELESS, null, "Ayush's S25 Ultra", failed = false)
        assertEquals("S25 Ultra (add its Android version)", GitHubIssueReport.phone(android))
        assertEquals("samsung SM-S928B (add its Android version)", GitHubIssueReport.phone(android.copy(phoneModel = "samsung SM-S928B")))
        assertEquals("iPhone", GitHubIssueReport.phoneLabel("iPhone de Carlos"))
        assertEquals("iPhone", GitHubIssueReport.phoneLabel("Carlos’ iPhone"))
        assertEquals("BT4652", GitHubIssueReport.phoneLabel("BT4652"))
        assertNull(GitHubIssueReport.phoneLabel("  "))
    }

    @Test fun readsThePhonesModelAndVersionFromTheEngineLogs() {
        val log = listOf(
            line(1_000, LogSource.CARPLAY, "Phone display peer model=iPhone13,1 ios=26.5.2 airplay=950.7.1"),
            line(2_000, LogSource.ANDROID_AUTO, "AapControl.serviceDiscoveryRequest | Service Discovery Request: Android brand=Ayush's Pixel 8"),
            line(9_000, LogSource.CARPLAY, "Phone display peer model=not_reported ios=not_reported airplay=1"),
        )
        assertEquals("iPhone13,1" to "26.5.2", GitHubIssueReport.phoneFromLog(log, Engine.CARPLAY, now - 5_000))
        assertEquals("Pixel 8" to null, GitHubIssueReport.phoneFromLog(log, Engine.ANDROID_AUTO, now - 5_000))
        // Before the session started, or nothing reported: unknown.
        assertNull(GitHubIssueReport.phoneFromLog(log, Engine.CARPLAY, now - 500))
        assertNull(GitHubIssueReport.phoneFromLog(log.drop(2), Engine.CARPLAY, 0))
    }

    @Test fun collapsesRepeatedLines() {
        val log = listOf(
            line(1_000, LogSource.CARLINK, "Switcher: show=false"),
            line(2_000, LogSource.CARLINK, "Switcher: show=true"),
            line(3_000, LogSource.CARLINK, "Switcher: show=false"),
            line(4_000, LogSource.CARPLAY, "Switcher: show=false"),
            line(5_000, LogSource.CARLINK, "Switcher: show=false"),
        )
        val groups = GitHubIssueReport.collapse(log)
        assertEquals(listOf(3, 1, 1), groups.map { it.lines.size })
        assertEquals("03:46:39.000 CL - Switcher: show=false ×3 (03:46:35–03:46:39)",
            GitHubIssueReport.format(groups[0], emptyList(), utc))
        val excerpt = GitHubIssueReport.logExcerpt(facts(log = log), 100_000)
        assertEquals(3, excerpt.lines.size)
        assertEquals(0, excerpt.omitted)
    }

    @Test fun timelineOfEachEnginesLastSession() {
        val log = listOf(
            line(1_000, LogSource.CARLINK, "CarLink: start CARPLAY USB known=true #s9"),
            line(2_000, LogSource.ANDROID_AUTO, "WIRELESS_TIMING first-frame start+4100ms"),
            line(3_000, LogSource.CARLINK, "CarLink: ANDROID_AUTO connected on WIFI_DIRECT in 4300 ms #s8"),
            line(4_000, LogSource.CARPLAY, "WIRED_TIMING iphone-found plug+100ms session+0ms"),
            line(6_000, LogSource.ANDROID_AUTO, "WIRELESS_TIMING group-created start+900ms"),
            line(7_000, LogSource.CARLINK, "CarLink: start ANDROID_AUTO WIRELESS known=true #s8"),
            line(8_000, LogSource.CARLINK, "CarLink: start ANDROID_AUTO USB known=true #s7"),
        )
        assertEquals("start ANDROID_AUTO WIRELESS known=true +0ms · group-created +1000ms · " +
            "ANDROID_AUTO connected on WIFI_DIRECT in 4300 ms +4000ms · first-frame +5000ms",
            GitHubIssueReport.timeline(log, Engine.ANDROID_AUTO))
        // The CarPlay session started later: only its own line; the earlier CarPlay milestone is not part of it.
        assertEquals("start CARPLAY USB known=true +0ms", GitHubIssueReport.timeline(log, Engine.CARPLAY))
        assertNull(GitHubIssueReport.timeline(emptyList(), Engine.CARPLAY))
    }

    @Test fun labelsEachEnginesSessionAndKeepsThePromptWithinBudget() {
        val carPlay = GitHubIssueReport.Session(Engine.CARPLAY, Transport.USB, null, "iPhone", failed = false, detail = "phase Connected",
            sessionNumber = 3, atMillis = now - 3_600_000, phoneModel = "iPhone13,1", phoneOs = "26.5.2")
        val f = facts(others = listOf(carPlay)).copy(settings = "connection AUTOMATIC; overlay granted",
            hardware = com.carlink.core.connection.DeviceSignals(2_900, true, 8, listOf("arm64-v8a"), 1_500, "Mali-G52"),
            log = listOf(line(1_000, LogSource.ANDROID_AUTO,
                "VideoDecoder.logThroughput | Throughput over 5003ms: rendered=279 (55fps), dropped=0, codec=c2.qti.hevc.decoder")))
        val what = GitHubIssueReport.whatHappened(f, short = false)
        assertTrue(what, what.contains("Session #s7 · Android Auto · WIRELESS WIFI_DIRECT · failed · 2025-10-15 03:46:20 +0000 (the form's dropdowns describe this one)"))
        assertTrue(what, what.contains("Session #s3 · CarPlay · USB · no failure · 2025-10-15 02:46:40 +0000\n- Phone: iPhone13,1, OS 26.5.2"))
        assertTrue(what, what.contains("Settings: connection AUTOMATIC; overlay granted"))
        assertTrue(what, what.contains("- Hardware: RAM 2900 MB (low-RAM device), 8 CPU cores up to 1500 MHz, GPU Mali-G52"))
        assertTrue(what, what.contains("- AA 03:46:39 Throughput over 5003ms: rendered=279 (55fps)"))
        assertTrue(what, what.endsWith("What I did and what I expected:"))
        // A tight budget drops the other session and the performance block first, never the prompt.
        val budget = GitHubIssueReport.encode(what).length - GitHubIssueReport.encode(what.substringAfter("Performance:")).length
        val tight = GitHubIssueReport.whatHappened(f, short = false, budget = budget)
        assertFalse(tight, tight.contains("#s3") || tight.contains("Performance"))
        assertTrue(tight, tight.contains("#s7") && tight.endsWith("What I did and what I expected:"))
    }

    @Test fun removesSerialsAndOwnerNames() {
        assertEquals("Bluetooth: S25 Ultra connected, serial=[hidden]",
            GitHubIssueReport.clean("Bluetooth: Ayush's S25 Ultra connected, serial=R5CT12345", emptyList()))
        assertEquals("CarLink's own log", GitHubIssueReport.clean("CarLink's own log", emptyList()))
    }
}
