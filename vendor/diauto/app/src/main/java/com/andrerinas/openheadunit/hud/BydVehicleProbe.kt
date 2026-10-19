package com.andrerinas.openheadunit.hud

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.lang.reflect.Modifier

/**
 * Read-only inventory of the BYD vehicle interfaces present on this head unit, for the diagnostic
 * report. It looks things up and lists them; it never calls a setter, sends a broadcast, binds a
 * service or writes any vehicle value. Every step is isolated so one missing piece cannot hide
 * the rest.
 *
 * Its purpose is to tell, from one report, which navigation/cluster/HUD paths a given firmware
 * could support - in particular on units (such as DiLink 3.0) where none of the validated outputs
 * is available and the setting is therefore hidden.
 */
internal object BydVehicleProbe {
    private const val SDK_PREFIX = "android.hardware.bydauto"
    private const val INSTRUMENT_DEVICE = "$SDK_PREFIX.instrument.BYDAutoInstrumentDevice"
    private const val FEATURE_IDS = "$SDK_PREFIX.BYDAutoFeatureIds"
    private val packageHints = listOf("byd", "someip", "amap", "instrument", "cluster", "hud", "navi")
    private val featureHints = Regex("NAVI|GUIDE|HUD|MUSIC|MEDIA|SONG|SINGER|ARTIST|ALBUM|LYRIC|RADIO|PLAY|TEXT|STRING|THEME", RegexOption.IGNORE_CASE)

    fun appliesTo(): Boolean =
        Build.MANUFACTURER.contains("BYD", ignoreCase = true) || Build.BRAND.contains("BYD", ignoreCase = true)

    fun report(context: Context): List<String> {
        val out = mutableListOf<String>()
        section(out, "Fingerprint") { listOf(Build.FINGERPRINT) }
        section(out, "Validated outputs") {
            listOf(
                "standalone windshield HUD available=${BydStandaloneHudOutput.available(context)}",
                "navigation setting shown=${BydNavigationOutputs.available(context)}"
            )
        }
        section(out, "Related packages") { relatedPackages(context) }
        section(out, "BYD permissions declared on this unit") { bydPermissions(context) }
        section(out, "SDK classes in the boot class path") { sdkClasses() }
        section(out, "Instrument device") { instrumentDevice(context) }
        section(out, "Instrument feature IDs") { instrumentFeatureIds() }
        section(out, "Other feature IDs matching navigation/media hints") { otherFeatureIds() }
        return out
    }

    private fun section(out: MutableList<String>, title: String, block: () -> List<String>) {
        out += "[$title]"
        val lines = try {
            block().ifEmpty { listOf("(none)") }
        } catch (t: Throwable) {
            listOf("(unavailable: ${t.javaClass.simpleName}: ${t.message})")
        }
        out += lines.map { "  $it" }
    }

    // Firmware only: reports are posted publicly, and a user's own apps (a "navi" app, say) are
    // neither BYD interfaces nor anyone else's business.
    private fun systemPackages(context: Context): List<PackageInfo> =
        context.packageManager.getInstalledPackages(0)
            .filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 }

    private fun relatedPackages(context: Context): List<String> =
        systemPackages(context)
            .map { it.packageName to (it.versionName ?: "?") }
            .filter { (name, _) -> packageHints.any { name.contains(it, ignoreCase = true) } }
            .sortedBy { it.first }
            .take(150)
            .map { (name, version) -> "$name $version" }

    @Suppress("DEPRECATION")
    private fun bydPermissions(context: Context): List<String> {
        val pm = context.packageManager
        val result = sortedMapOf<String, String>()
        // Per package rather than one GET_PERMISSIONS listing: on a unit with hundreds of system
        // packages the single call can exceed the binder transaction limit.
        for (name in systemPackages(context).map { it.packageName }) {
            val pkg = runCatching { pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: continue
            for (perm in pkg.permissions.orEmpty()) {
                if (!perm.name.contains("BYD", ignoreCase = true)) continue
                val base = perm.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                val level = when (base) {
                    PermissionInfo.PROTECTION_NORMAL -> "normal"
                    PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                    PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                    else -> "level=$base"
                }
                val held = context.checkPermission(perm.name, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED
                result[perm.name] = "$level, declared by ${pkg.packageName}, held=$held"
            }
        }
        return result.entries.take(200).map { "${it.key}: ${it.value}" }
    }

    @Suppress("DEPRECATION")
    private fun sdkClasses(): List<String> {
        val jars = (System.getenv("BOOTCLASSPATH") ?: "").split(':').filter { it.isNotBlank() }
        val found = sortedSetOf<String>()
        val notes = mutableListOf<String>()
        for (jar in jars) {
            try {
                val entries = dalvik.system.DexFile(jar).entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    if (name.startsWith(SDK_PREFIX) && !name.contains('$')) found += name
                }
            } catch (t: Throwable) {
                if (jar.contains("byd", ignoreCase = true)) notes += "$jar unreadable: ${t.javaClass.simpleName}"
            }
        }
        return notes + found.take(300).toList() + listOf("(${found.size} classes)")
    }

    private fun instrumentDevice(context: Context): List<String> {
        val cls = Class.forName(INSTRUMENT_DEVICE)
        val lines = mutableListOf<String>()
        // getInstance only returns the SDK's handle object; nothing is sent to the vehicle.
        lines += try {
            val instance = cls.getMethod("getInstance", Context::class.java).invoke(null, context)
            "getInstance: ok (${instance?.javaClass?.name})"
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            "getInstance: failed ${cause.javaClass.simpleName}: ${cause.message}"
        }
        lines += cls.methods
            .filter { it.declaringClass == cls && Modifier.isPublic(it.modifiers) }
            .map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}" }
            .sorted()
            .take(250)
        return lines
    }

    private fun instrumentFeatureIds(): List<String> =
        constantsOf(Class.forName("$FEATURE_IDS\$Instrument")).take(500)

    private fun otherFeatureIds(): List<String> {
        val root = Class.forName(FEATURE_IDS)
        val lines = mutableListOf<String>()
        lines += "groups: " + root.declaredClasses.map { it.simpleName }.sorted().joinToString()
        for (group in root.declaredClasses.sortedBy { it.simpleName }) {
            if (group.simpleName == "Instrument") continue
            constantsOf(group).filter { featureHints.containsMatchIn(it) }
                .forEach { lines += "${group.simpleName}.$it" }
        }
        return lines.take(400)
    }

    private fun constantsOf(cls: Class<*>): List<String> =
        cls.fields
            .filter { Modifier.isStatic(it.modifiers) && (it.type == Int::class.javaPrimitiveType || it.type == String::class.java) }
            .map { field -> "${field.name}=${runCatching { field.get(null) }.getOrNull()}" }
            .sorted()
}
