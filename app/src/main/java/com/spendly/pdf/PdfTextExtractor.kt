package com.spendly.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls the text out of a PDF.
 *
 * Android's own `PdfRenderer` only rasterises pages to bitmaps, so reading the
 * words requires a real PDF library. Everything runs locally — the document is
 * opened from the content URI the picker returned, read into memory, and never
 * written anywhere or sent anywhere.
 */
object PdfTextExtractor {

    sealed interface Result {
        data class Text(val text: String, val pages: Int) : Result

        /** Bank statements are routinely locked with an IC number or birth date. */
        data object NeedsPassword : Result

        data class WrongPassword(val message: String) : Result

        data class Failed(val message: String) : Result

        /** Opened fine but contains no extractable text — usually a scan. */
        data object NoTextLayer : Result
    }

    /** Statements are tens of KB of text; anything past this is not a statement. */
    private const val MAX_CHARS = 2_000_000

    suspend fun extract(
        context: Context,
        uri: Uri,
        password: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext Result.Failed("Could not open that file")

                PDDocument.load(input, password ?: "").use { document ->
                    val stripper = PDFTextStripper().apply {
                        // Statement rows are columns of text; sorting by position
                        // keeps each row on one line instead of interleaving them.
                        sortByPosition = true
                    }
                    val text = stripper.getText(document)
                    when {
                        text.isBlank() -> Result.NoTextLayer
                        text.length > MAX_CHARS -> Result.Failed("That PDF is too large to read")
                        else -> Result.Text(text, document.numberOfPages)
                    }
                }
            }
        } catch (e: InvalidPasswordException) {
            // The same exception covers "locked" and "wrong key", so the
            // distinction is whether we already tried something.
            if (password.isNullOrEmpty()) {
                Result.NeedsPassword
            } else {
                Result.WrongPassword("That password did not open the file")
            }
        } catch (e: OutOfMemoryError) {
            Result.Failed("That PDF is too large to read on this device")
        } catch (e: Exception) {
            Result.Failed(e.message?.takeIf { it.isNotBlank() } ?: "That file is not a readable PDF")
        }
    }
}
