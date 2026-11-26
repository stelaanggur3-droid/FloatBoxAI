package com.floatbox.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

object OcrHelper {

    private val executor = Executors.newSingleThreadExecutor()

    fun recognizeTextFromUri(
        context: Context,
        imageUri: Uri,
        onPreviewReady: (Bitmap?) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            val previewBitmap = decodeScaledBitmap(context, imageUri, 900)
            onPreviewReady(previewBitmap)
        }

        try {
            val inputImage = InputImage.fromFilePath(context, imageUri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    recognizer.close()
                    onSuccess(visionText.text.trim())
                }
                .addOnFailureListener { e ->
                    recognizer.close()
                    onError(e.localizedMessage ?: "Gagal memproses OCR.")
                }
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Gagal membaca file gambar.")
        }
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width > maxDim || height > maxDim) {
                    val ratio = maxDim.toFloat() / maxOf(width, height).toFloat()
                    decoder.setTargetSize(
                        (width * ratio).toInt().coerceAtLeast(1),
                        (height * ratio).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            null
        }
    }
}