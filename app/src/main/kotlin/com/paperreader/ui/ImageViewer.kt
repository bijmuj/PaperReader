package com.paperreader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes [file], downsampling only if a side exceeds [maxSide] pixels. */
fun decodeImage(file: File, maxSide: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

@Composable
fun LocalImage(file: File?, maxSide: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = file?.let { withContext(Dispatchers.IO) { decodeImage(it, maxSide) } }
    }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier) }
}

/** Full-screen viewer: pinch to zoom around the fingers, drag to pan, double-tap to toggle 3x. */
@Composable
fun ImageViewer(file: File?, onDismiss: () -> Unit, onOriginalPage: (() -> Unit)? = null) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val loaded by produceState<Pair<Boolean, ImageBitmap?>>(false to null, file) {
            // 4096 px keeps us under the GPU texture limit while preserving our full-res renders.
            value = true to file?.let { withContext(Dispatchers.IO) { decodeImage(it, 4096) } }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val (done, bitmap) = loaded
            when {
                bitmap != null -> ZoomableImage(bitmap)
                done -> Text("Could not load image", color = Color.White, modifier = Modifier.align(Alignment.Center))
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            ) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            if (onOriginalPage != null) {
                androidx.compose.material3.TextButton(
                    onClick = onOriginalPage,
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 12.dp),
                ) { Text("Show on PDF page", color = Color.White) }
            }
        }
    }
}

@Composable
internal fun ZoomableImage(bitmap: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val center = Offset(w / 2, h / 2)

        fun clamp(o: Offset, s: Float): Offset {
            // Size of the fitted image on screen, before zoom.
            val fit = minOf(w / bitmap.width, h / bitmap.height)
            val iw = bitmap.width * fit * s
            val ih = bitmap.height * fit * s
            val mx = ((iw - w) / 2).coerceAtLeast(0f)
            val my = ((ih - h) / 2).coerceAtLeast(0f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            offset = clamp((center - tap) * (scale - 1f), scale)
                        }
                    })
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, 12f)
                        val z = s / scale
                        // Keep the content under the fingers in place while zooming.
                        offset = clamp((centroid - center) * (1 - z) + offset * z + pan, s)
                        scale = s
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
