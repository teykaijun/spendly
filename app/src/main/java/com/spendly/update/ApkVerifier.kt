package com.spendly.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded APK before it is handed to the installer.
 *
 * Android already refuses to install an update signed by a different key than
 * the installed app, so this is defence in depth rather than the only line —
 * but it matters: it turns a silent, confusing "install failed" into a specific
 * refusal, and it means a tampered APK is deleted rather than passed on to the
 * system installer at all.
 */
object ApkVerifier {

    sealed interface Result {
        data object Ok : Result
        data class Rejected(val reason: String) : Result
    }

    fun verify(
        context: Context,
        apk: File,
        expectedSha256: String?,
    ): Result {
        if (!apk.exists() || apk.length() == 0L) {
            return Result.Rejected("The downloaded file is missing or empty")
        }

        if (expectedSha256 != null) {
            val actual = UpdateClient.sha256Of(apk)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return Result.Rejected("Checksum did not match — the download may be corrupt or tampered with")
            }
        }

        val pm = context.packageManager
        val archive = readArchiveInfo(pm, apk)
            ?: return Result.Rejected("That file is not a readable Android package")

        if (archive.packageName != context.packageName) {
            return Result.Rejected(
                "That package is ${archive.packageName}, not ${context.packageName}",
            )
        }

        val newVersion = archive.longVersionCodeCompat()
        val currentVersion = runCatching {
            pm.getPackageInfo(context.packageName, 0).longVersionCodeCompat()
        }.getOrDefault(0L)
        if (newVersion <= currentVersion) {
            return Result.Rejected("That build ($newVersion) is not newer than the installed one ($currentVersion)")
        }

        val installedCerts = signingCertificates(pm, context.packageName)
        val downloadedCerts = signingCertificatesOfArchive(pm, apk)
        if (installedCerts.isEmpty() || downloadedCerts.isEmpty()) {
            return Result.Rejected("Could not read the signing certificate")
        }
        if (installedCerts.intersect(downloadedCerts).isEmpty()) {
            return Result.Rejected(
                "Signed with a different key than the installed app. Android would " +
                    "refuse this install. Either it is not an official build, or the " +
                    "release was signed with a new keystore.",
            )
        }

        return Result.Ok
    }

    private fun readArchiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        runCatching {
            pm.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull()

    /** SHA-256 of each signing certificate, so sets can be compared directly. */
    @Suppress("DEPRECATION")
    private fun signingCertificates(pm: PackageManager, packageName: String): Set<String> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo ?: return emptySet()
                val signers = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            } else {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            }
        }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun signingCertificatesOfArchive(pm: PackageManager, apk: File): Set<String> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ) ?: return emptySet()
                val signing = info.signingInfo ?: return emptySet()
                val signers = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            } else {
                val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                    ?: return emptySet()
                info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            }
        }.getOrDefault(emptySet())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@Suppress("DEPRECATION")
internal fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
