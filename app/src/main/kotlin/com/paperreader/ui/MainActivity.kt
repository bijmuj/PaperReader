package com.paperreader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.paperreader.PaperReaderApp
import com.paperreader.convert.Arxiv
import com.paperreader.convert.convertArxiv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    /** An EPUB handed to us by another app ("Open with"), waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)

    /** Text shared to us (an arXiv link), waiting to be converted. */
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PaperReaderTheme {
                AppRoot(incoming, sharedText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { incoming.value = it }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
        }
    }
}

@Composable
private fun AppRoot(incoming: MutableStateFlow<Uri?>, sharedText: MutableStateFlow<String?>) {
    val app = LocalContext.current.applicationContext as PaperReaderApp
    var openBook by rememberSaveable { mutableStateOf<Long?>(null) }
    /** What the app is busy with (shown under the library's top bar), or null. */
    var status by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun import(uri: Uri) {
        if (status != null) return
        status = "Importing"
        scope.launch {
            try {
                openBook = app.library.import(uri)
            } catch (e: Exception) {
                openBook = null
                snackbar.showSnackbar(e.message ?: "Import failed")
            } finally {
                status = null
            }
        }
    }

    fun fromArxiv(text: String) {
        if (status != null) return
        if (Arxiv.parseId(text) == null && Arxiv.findId(text) == null) {
            scope.launch { snackbar.showSnackbar("That doesn't look like an arXiv link or ID") }
            return
        }
        status = "Starting"
        scope.launch {
            val out = File(app.cacheDir, "converted/paper.epub")
            try {
                withContext(Dispatchers.IO) {
                    out.parentFile?.mkdirs()
                    convertArxiv(text, app.cacheDir, out) { line -> scope.launch { if (status != null) status = line } }
                }
                status = "Importing"
                openBook = app.library.import(Uri.fromFile(out))
            } catch (e: Exception) {
                Log.e("PaperReader", "arXiv conversion failed", e)
                snackbar.showSnackbar(e.message ?: "Conversion failed")
            } finally {
                out.delete()
                status = null
            }
        }
    }

    val shared by sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let {
            sharedText.value = null
            openBook = null
            fromArxiv(it)
        }
    }

    val pending by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let {
            incoming.value = null
            import(it)
        }
    }

    val id = openBook
    if (id != null) {
        ReaderScreen(bookId = id, onClose = { openBook = null })
    } else {
        LibraryScreen(
            onOpen = { openBook = it },
            status = status,
            onImport = ::import,
            onArxiv = ::fromArxiv,
            snackbar = snackbar,
        )
    }
}

@Composable
fun PaperReaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
