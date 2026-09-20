package com.spendly.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The only networking in the app.
 *
 * Plain `HttpURLConnection` rather than a client library: two requests do not
 * justify a dependency, and a small surface is easier to reason about for code
 * whose job is to fetch something that will then be executed.
 *
 * Every request is HTTPS-only. Redirects are followed but re-checked, because
 * the default redirect handling would happily follow https -> http.
 */
object UpdateClient {

    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val USER_AGENT = "Spendly-Updater"

    class UpdateException(message: String) : IOException(message)

    suspend fun fetchText(url: String, maxBytes: Int = 512 * 1024): String =
        withContext(Dispatchers.IO) {
            val connection = openHttps(url)
            try {
                val code = connection.responseCode
                if (code == 404) throw UpdateException("No published release found")
                if (code !in 200..299) throw UpdateException("Update server returned HTTP $code")
                connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    val out = StringBuilder()
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        out.append(buffer, 0, n)
                        if (out.length > maxBytes) throw UpdateException("Update feed is too large")
                    }
                    out.toString()
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Downloads the APK, reporting progress as a 0f..1f fraction.
     *
     * Writes to [destination] only on success — a partial file must never be
     * left somewhere the installer might later pick up.
     */
    suspend fun downloadApk(
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        destination.delete()

        val connection = openHttps(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateException("Download failed with HTTP $code")

            val declared = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
            if (declared > MAX_APK_BYTES) throw UpdateException("Refusing to download ${declared / 1024 / 1024} MB")

            var written = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > MAX_APK_BYTES) throw UpdateException("Download exceeded the size limit")
                        if (declared > 0) onProgress((written.toFloat() / declared).coerceIn(0f, 1f))
                    }
                }
            }
            if (written == 0L) throw UpdateException("Downloaded file was empty")

            if (!partial.renameTo(destination)) {
                throw UpdateException("Could not finalise the downloaded file")
            }
            onProgress(1f)
            destination
        } catch (e: Throwable) {
            partial.delete()
            destination.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Opens an HTTPS connection, following redirects manually so that every hop
     * is re-checked for scheme. `HttpURLConnection` silently refuses to follow
     * https->http, but it also will not tell us, so doing it here keeps the
     * failure explicit rather than surfacing as a confusing empty response.
     */
    private fun openHttps(startUrl: String): HttpURLConnection {
        var current = startUrl
        repeat(MAX_REDIRECTS) {
            if (!current.startsWith("https://", ignoreCase = true)) {
                throw UpdateException("Refusing a non-HTTPS update URL")
            }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw UpdateException("Update server sent a redirect with no target")
                current = URL(URL(current), location).toString()
                return@repeat
            }
            return connection
        }
        throw UpdateException("Too many redirects from the update server")
    }
}
