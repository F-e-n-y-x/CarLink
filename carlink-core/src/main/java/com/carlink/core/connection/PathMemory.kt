package com.carlink.core.connection

import android.content.Context
import com.carlink.core.core.Engine
import com.carlink.core.detect.KnownPhones
import com.carlink.core.settings.WirelessPath

/** The wireless path that last connected, per phone and engine; tried first next time. */
class PathMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_path_memory", Context.MODE_PRIVATE)

    fun lastWorking(engine: Engine, phoneAddress: String): WirelessPath? =
        prefs.getString(key(engine, phoneAddress), null)?.let { runCatching { WirelessPath.valueOf(it) }.getOrNull() }

    fun remember(engine: Engine, phoneAddress: String, path: WirelessPath) {
        prefs.edit().putString(key(engine, phoneAddress), path.name).apply()
    }

    private fun key(engine: Engine, address: String) = "${engine.name}:${KnownPhones.normalize(address)}"
}
