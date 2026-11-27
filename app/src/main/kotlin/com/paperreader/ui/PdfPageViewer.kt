package com.paperreader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Opens [pdf] for rendering pages one at a time (PdfRenderer allows one open page). */
private class PdfPages(pdf: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val count = renderer.pageCount

    /** Renders [index] [width] pixels wide, outlining [mark] (PDF points, top-left origin). */
    @Volatile private var closing = false
    private var closed = false

    suspend fun render(index: Int, width: Int, mark: FloatArray?): Bitmap = lock.withLock {
        check(!closing) { "closed" }
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val scale = width.toFloat() / page.width
                val bmp = Bitmap.createBitmap(width, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if (mark != null && mark.size == 4) {
                    val pad = 4 * scale
                    val r = android.graphics.RectF(mark[0] * scale - pad, mark[1] * scale - pad, mark[2] * scale + pad, mark[3] * scale + pad)
                    Canvas(bmp).apply {
                        drawRoundRect(r, 3 * scale, 3 * scale, Paint().apply { color = 0x2A2A6AD3; style = Paint.Style.FILL })
                        drawRoundRect(r, 3 * scale, 3 * scale, Paint().apply {
                            color = 0xFF2A6AD3.toInt()
                            style = Paint.Style.STROKE
                            strokeWidth = 1.5f * scale
                            isAntiAlias = true
                        })
                    }
                }
                bmp
            }
        }.also { if (closing) shut() }
    }

    /** Closes now, or after the page being rendered (the renderer can't close mid-render). */
    override fun close() {
        closing = true
        if (lock.tryLock()) {
            try { shut() } finally { lock.unlock() }
        }
    }

    private fun shut() {
        if (closed) return
        closed = true
        renderer.close()
        fd.close()
    }
}

/**
 * Full-screen view of a page of the original PDF, zoomable like [ImageViewer], with arrows to
 * turn pages. [mark] outlines a region of [startPage] (a figure's place on its page).
 */
@Composable
fun PdfPageViewer(pdf: File, startPage: Int, mark: FloatArray?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pages = remember(pdf) { runCatching { PdfPages(pdf) }.getOrNull() }
        DisposableEffect(pages) { onDispose { pages?.close() } }
        var page by remember { mutableIntStateOf(startPage.coerceIn(0, ((pages?.count ?: 1) - 1).coerceAtLeast(0))) }
        // Twice the screen width leaves detail for zooming in; 2048 px bounds the memory used.
        val width = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp.toPx() * 2).toInt().coerceIn(800, 2048) }
        val bitmap by produceState<Pair<Int, ImageBitmap?>?>(null, pages, page) {
            value = page to pages?.let { p -> runCatching { p.render(page, width, mark.takeIf { page == startPage }).asImageBitmap() }.getOrNull() }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val shown = bitmap
            when {
                pages == null -> Text("Could not open the PDF", color = Color.White, modifier = Modifier.align(Alignment.Center))
                shown == null || shown.first != page -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                shown.second == null -> Text("Could not show this page", color = Color.White, modifier = Modifier.align(Alignment.Center))
                else -> key(page) { ZoomableImage(shown.second!!) }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            ) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            if (pages != null) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 12.dp)
                        .background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { page-- }, enabled = page > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page", tint = if (page > 0) Color.White else Color.Gray)
                    }
                    Text("Page ${page + 1} of ${pages.count}", color = Color.White)
                    IconButton(onClick = { page++ }, enabled = page < pages.count - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page", tint = if (page < pages.count - 1) Color.White else Color.Gray)
                    }
                }
            }
        }
    }
}
