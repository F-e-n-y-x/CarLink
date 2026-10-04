# DiPlay DiLink 4.0 — R8 keep rules for the minified standalone car-test build.
# Goal: recover the ~10 MB that an unminified debug build wastes on library code,
# while keeping CarPlay / MFi / JNI paths intact (they rely on class names or reflection).

# Keep all of our own code. It is small; keeping it avoids any reflection/obfuscation
# surprise in the CarPlay host, MFi handshake (Iap2IdentificationConfig, transport.*),
# and the HUD bridge.
-keep class com.shilapi.xcertplay.** { *; }

# CarPlay / androidx.car.app uses template reflection; keep it intact to avoid CarPlay breakage.
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**

# BouncyCastle (MFi crypto) resolves providers/implementations by class name; keep it whole.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JNI native methods must not be stripped or renamed.
-keepclasseswithmembernames class * { native <methods>; }

# Respect explicit @Keep annotations.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }

# Parcelable creators.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Quiet warnings from libraries that reference optional/absent classes.
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn androidx.compose.**
-dontwarn androidx.car.app.**
-dontwarn org.bouncycastle.**
