package com.spendly.update

import android.app.Application
import android.content.Context
import com.spendly.BuildConfig
import com.spendly.data.Prefs
import java.io.File

/**
 * Drives the whole update flow: ask the feed, compare, download, verify, install.
 *
 * Nothing here runs on its own. The network is only ever touched because the
 * user tapped Check for updates, or because they explicitly turned on the
 * check-on-open toggle, which is off by default.
 */
class UpdateRepository private constructor(
    /** Application, not a bare Context: this instance outlives every Activity. */
    private val context: Application,
    private val prefs: Prefs,
) {

    sealed interface Check {
        data class UpToDate(val currentVersionName: String) : Check
        data class Available(val manifest: UpdateManifest) : Check
        data class Failed(val message: String) : Check
        data object NotConfigured : Check
    }

    sealed interface Download {
        data class Ready(val apk: File, val manifest: UpdateManifest) : Download
        data class Failed(val message: String) : Download
    }

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    suspend fun check(): Check {
        val source = UpdateSource.parse(prefs.updateSource.value)
            ?: return Check.NotConfigured

        return try {
            val manifest = when (source) {
                is UpdateSource.GitHubReleases ->
                    UpdateManifestParser.fromGitHubRelease(UpdateClient.fetchText(source.apiUrl))

                is UpdateSource.JsonUrl ->
                    UpdateManifestParser.fromJsonFeed(UpdateClient.fetchText(source.url))
            } ?: return Check.Failed("No installable build found at ${source.describedAs}")

            if (manifest.isNewerThan(currentVersionCode)) {
                Check.Available(manifest)
            } else {
                Check.UpToDate(currentVersionName)
            }
        } catch (e: UpdateClient.UpdateException) {
            Check.Failed(e.message ?: "Could not reach the update server")
        } catch (e: Exception) {
            Check.Failed("Could not reach the update server")
        }
    }

    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit,
    ): Download {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One slot, always overwritten: never accumulate APKs on disk.
        val target = File(dir, "update.apk")

        return try {
            UpdateClient.downloadApk(
                url = manifest.apkUrl,
                destination = target,
                expectedBytes = manifest.sizeBytes,
                onProgress = onProgress,
            )

            when (val verdict = ApkVerifier.verify(context, target, manifest.sha256)) {
                is ApkVerifier.Result.Ok -> Download.Ready(target, manifest)
                is ApkVerifier.Result.Rejected -> {
                    target.delete()
                    Download.Failed(verdict.reason)
                }
            }
        } catch (e: UpdateClient.UpdateException) {
            target.delete()
            Download.Failed(e.message ?: "Download failed")
        } catch (e: Exception) {
            target.delete()
            Download.Failed("Download failed")
        }
    }

    /** Removes any downloaded APK — called once an install succeeds or is abandoned. */
    fun clearDownloads() {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    companion object {
        @Volatile
        private var instance: UpdateRepository? = null

        fun get(context: Context): UpdateRepository = instance ?: synchronized(this) {
            instance ?: UpdateRepository(
                context.applicationContext as Application,
                Prefs.get(context),
            ).also { instance = it }
        }
    }
}
