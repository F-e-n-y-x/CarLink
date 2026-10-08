package com.andrerinas.openheadunit

import android.app.Activity
import com.andrerinas.openheadunit.main.MainActivity

/**
 * CarLink: lets an APK that embeds this engine choose the screens the engine opens on its own
 * (notification tap, boot launch, return after a Wi-Fi Direct prompt). Standalone DiAuto keeps
 * its MainActivity.
 */
object HostIntegration {
    @Volatile
    @JvmStatic
    var homeActivity: Class<out Activity> = MainActivity::class.java
}
