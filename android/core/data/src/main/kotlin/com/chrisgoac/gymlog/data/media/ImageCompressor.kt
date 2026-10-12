package com.chrisgoac.gymlog.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaLimits
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Turns a picked/captured image into the JPEG Android uploads: decoded with a power-of-two
 * `inSampleSize` (never the full-size bitmap of a huge photo), scaled so its longest side is at most
 * [maxSide], EXIF orientation applied to the pixels, transparency flattened on white, and re-encoded at
 * [quality]. The output carries no EXIF at all (no location, no device data).
 */
class ImageCompressor(
    private val maxSide: Int = MediaLimits.COMPRESSED_MAX_SIDE,
    private val quality: Int = MediaLimits.JPEG_QUALITY,
    private val maxBytes: Long = MediaLimits.MAX_BYTES,
) {
    sealed interface Result {
        data class Compressed(val width: Int, val height: Int, val bytes: Long) : Result

        data class Failed(val reason: InvalidImage) : Result
    }

    /** Writes the compressed image to [target] (atomically: a failure leaves no [target]). */
    fun compress(source: File, target: File): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return Result.Failed(InvalidImage.CORRUPT)
        if (bounds.outMimeType?.lowercase() !in DECODABLE_TYPES) return Result.Failed(InvalidImage.UNSUPPORTED)
        if (width.toLong() * height > MAX_SOURCE_PIXELS) return Result.Failed(InvalidImage.TOO_LARGE)

        val decoded = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(width, height))
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return Result.Failed(InvalidImage.CORRUPT)

        val oriented = transform(decoded, orientationOf(source))
        val opaque = if (oriented.hasAlpha()) flattenOnWhite(oriented) else oriented
        try {
            return encode(opaque, target)
        } finally {
            listOf(decoded, oriented, opaque).distinct().forEach(Bitmap::recycle)
        }
    }

    /** Largest power of two that still leaves the longest side at least [maxSide]. */
    private fun sampleSize(longestSide: Int): Int {
        var sample = 1
        while (longestSide / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun orientationOf(source: File): Int = try {
        ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (_: RuntimeException) {
        ExifInterface.ORIENTATION_NORMAL // Malformed EXIF must not reject an otherwise valid image.
    }

    /** Applies [orientation] and the final downscale in one pass. */
    private fun transform(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun encode(bitmap: Bitmap, target: File): Result {
        val temp = File(target.path + ".tmp")
        var q = quality
        while (true) {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)) {
                    temp.delete()
                    return Result.Failed(InvalidImage.CORRUPT)
                }
            }
            if (temp.length() <= maxBytes) break
            q -= QUALITY_STEP
            if (q < MIN_QUALITY) {
                temp.delete()
                return Result.Failed(InvalidImage.TOO_LARGE)
            }
        }
        val bytes = temp.length()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not move the compressed image into place")
        }
        return Result.Compressed(bitmap.width, bitmap.height, bytes)
    }

    companion object {
        /** Source formats decoded and re-encoded as JPEG; anything else (BMP, GIF, ICO…) is refused. */
        val DECODABLE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic", "image/avif")

        /** Decompression-bomb guard on the declared source size (a 108 MP camera fits). */
        const val MAX_SOURCE_PIXELS = 120_000_000L
        private const val QUALITY_STEP = 10
        private const val MIN_QUALITY = 45
    }
}
