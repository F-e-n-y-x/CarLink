package com.carlink.core.detect

/** Reads a system property through the hidden `android.os.SystemProperties`; null when unset or unreadable. */
internal object SystemProps {
    fun get(key: String): String? = runCatching {
        val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, key, "") as? String)?.ifEmpty { null }
    }.getOrNull()
}
