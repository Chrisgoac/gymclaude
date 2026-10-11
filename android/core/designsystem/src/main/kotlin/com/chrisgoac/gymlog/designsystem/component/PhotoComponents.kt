package com.chrisgoac.gymlog.designsystem.component

import android.graphics.Bitmap
import android.os.Build
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes the file at [path] (a photo in the account's private storage) down to roughly [maxSidePx] on
 * the longest side, off the main thread, and draws it. Shows nothing until decoded and when [path] is
 * null or unreadable (the caller shows the placeholder / status text). [contentDescription] is the
 * accessible name; pass null for a decorative copy whose container already describes it.
 */
@Composable
fun PrivatePhoto(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxSidePx: Int = 1024,
    contentScale: ContentScale = ContentScale.Crop,
    /** Called when the file exists but cannot be decoded, so the caller can show a placeholder and offer a retry. */
    onUndecodable: (() -> Unit)? = null,
) {
    val result by produceState<Result<Bitmap?>?>(null, path, maxSidePx) {
        value = if (path == null) null else withContext(Dispatchers.IO) { Result.success(decodeSampled(path, maxSidePx)) }
    }
    val image = result?.getOrNull()
    LaunchedEffect(result, path) { if (path != null && result != null && image == null) onUndecodable?.invoke() }
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription, modifier, contentScale = contentScale)
    } else {
        Box(modifier)
    }
}

private fun decodeSampled(path: String, maxSidePx: Int): Bitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSidePx || bounds.outHeight / (sample * 2) >= maxSidePx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/**
 * One photo in a grid: the picture (or a placeholder) above a status line that is always visible text,
 * never an overlay, so it stays legible at 200% font. The whole tile is one button named
 * [description] (which already includes the status) and opens the photo.
 */
@Composable
fun PhotoTile(
    imagePath: String?,
    description: String,
    statusText: String,
    placeholderText: String,
    modifier: Modifier = Modifier,
    statusIsProblem: Boolean = false,
    /** Private-photo cover: blurred where the platform can (API 31+), a neutral cover with [concealedText] elsewhere. */
    concealed: Boolean = false,
    concealedText: String = "",
    onClick: (() -> Unit)? = null,
) {
    val colors = HierroTheme.colors
    var broken by remember(imagePath) { mutableStateOf(false) }
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier
            .then(clickable)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            if (concealed && !canBlur) {
                HierroText(concealedText, Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, colors.ink)
            } else if (imagePath != null && !broken) {
                PrivatePhoto(
                    imagePath, null,
                    Modifier.fillMaxSize().then(if (concealed) Modifier.blur(BLUR_RADIUS) else Modifier),
                    maxSidePx = 512, onUndecodable = { broken = true },
                )
            } else {
                HierroText(placeholderText, Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, colors.textMuted)
            }
        }
        HierroText(
            statusText,
            Modifier.clearAndSetSemantics {},
            HierroTheme.typography.label,
            if (statusIsProblem) colors.error else colors.textMuted,
        )
    }
}


private val BLUR_RADIUS = 24.dp
