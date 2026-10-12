package com.chrisgoac.gymlog.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** A file the camera app writes into, and the content URI handed to it. */
data class CaptureTarget(val uri: Uri, val file: File)

/**
 * Camera capture without the CAMERA permission: `ACTION_IMAGE_CAPTURE` (ActivityResultContracts.TakePicture)
 * writes into a [FileProvider] URI of a file in a dedicated per-account capture directory
 * (`files/capture/<hash>`, the only FileProvider root), removed with the account's local data. The app declares no CAMERA permission, which is what keeps that
 * intent usable without one. The URI is ingested like a Photo Picker URI and the capture file released.
 */
class CameraCaptureTargets(context: Context) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.media"

    fun create(userHash: String): CaptureTarget {
        val dir = captureDir(userHash).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        return CaptureTarget(FileProvider.getUriForFile(appContext, authority, file), file)
    }

    /** Deletes the capture file once it was ingested (or the capture was cancelled). */
    fun release(target: CaptureTarget) {
        target.file.delete()
    }

    /** Deletes every capture of an account whose local data is being deleted. */
    fun forget(userHash: String) {
        captureDir(userHash).deleteRecursively()
    }

    /** Removes captures a process death left behind (never ingested). */
    fun sweep(userHash: String, olderThanMillis: Long = STALE_MILLIS) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        captureDir(userHash).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun captureDir(userHash: String) = File(appContext.filesDir, "capture/$userHash")

    private companion object {
        const val STALE_MILLIS = 24 * 60 * 60_000L
    }
}
