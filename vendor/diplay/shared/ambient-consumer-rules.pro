# Entry point launched by app_process from the installed application classpath.
-keep class com.shilapi.xcertplay.hud.BydAmbientLightTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.BydCallPopupTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.transport.IphoneUsbGrantTool {
    public static void main(java.lang.String[]);
}

# CarLink: rules for R8 in CarLink's release build (DiPlay itself ships unshrunk).
# The other app_process entry points: started by class name from a shell, never called in-app.
-keep class com.shilapi.xcertplay.network.HotspotJoinRepairMain {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.BydCarPlayCallTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.BydClusterSongTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.hud.Byd13CatalogProbeMain {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.mfi.LocalMfiProbe {
    public static void main(java.lang.String[]);
}
# CarLink (0.2.19+): the Wi-Fi scan pause and car-hotspot start helpers, same app_process pattern.
-keep class com.shilapi.xcertplay.network.WifiScanPauseMain {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.network.CarHotspotStartMain {
    public static void main(java.lang.String[]);
}
# CarLink (0.2.21+): the car music rhythm helper, same app_process pattern.
-keep class com.shilapi.xcertplay.hud.BydCarMusicRhythmTool {
    public static void main(java.lang.String[]);
}
# CarLink: linux_i2c_jni.c throws this from native code (FindClass + <init>(int, String)).
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException {
    <init>(int, java.lang.String);
}
