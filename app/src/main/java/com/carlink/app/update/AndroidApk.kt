package com.carlink.app.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/**
 * [ApkInspector] on PackageManager: `getPackageArchiveInfo` with GET_SIGNING_CERTIFICATES on
 * API 28+ (the current signer; with key rotation the last of the history), GET_SIGNATURES below.
 */
class AndroidApkInspector(context: Context) : ApkInspector {
    private val pm = context.applicationContext.packageManager

    override fun inspect(file: File): ApkFacts? {
        val path = file.absolutePath
        val info = archiveInfo(pm, path, modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            ?: return null
        return facts(info)
    }

    companion object {
        /** The installed app's own signers (an update must come from the same key to install at all). */
        fun ownSigners(context: Context): Set<String> = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @SuppressLint("PackageManagerGetSignatures")
                val legacy = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                legacy
            }
            facts(info).signerSha256
        }.getOrDefault(emptySet())

        @Suppress("DEPRECATION")
        @SuppressLint("PackageManagerGetSignatures")
        private fun archiveInfo(pm: PackageManager, path: String, modern: Boolean): PackageInfo? {
            if (modern) {
                val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                if (info?.signingInfo != null) return info
            }
            return pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

        @Suppress("DEPRECATION")
        private fun facts(info: PackageInfo): ApkFacts {
            val signers: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                val signing = info.signingInfo!!
                if (signing.hasMultipleSigners()) signing.apkContentsSigners
                else signing.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) } ?: emptyArray()
            } else {
                info.signatures ?: emptyArray()
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            return ApkFacts(info.packageName.orEmpty(), versionCode,
                signers.map { JavaSha256.sha256Hex(it.toByteArray()) }.toSet())
        }
    }
}
