package com.chrisgoac.gymlog.sync

import android.content.ContentResolver
import androidx.core.net.toUri
import com.chrisgoac.gymlog.settings.BackupDocuments
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** Opens Storage Access Framework documents the user picked; the picker grants access, no storage permission. */
class ContentResolverBackupDocuments(private val resolver: ContentResolver) : BackupDocuments {
    // "wt": truncate, so re-exporting over a longer file never leaves a stale tail.
    override fun openForWrite(uri: String): OutputStream =
        resolver.openOutputStream(uri.toUri(), "wt") ?: throw FileNotFoundException("Cannot write $uri")

    override fun openForRead(uri: String): InputStream =
        resolver.openInputStream(uri.toUri()) ?: throw FileNotFoundException("Cannot read $uri")
}
