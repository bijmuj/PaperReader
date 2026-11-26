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
import com.paperreader.PaperReaderApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    /** An EPUB handed to us by another app ("Open with"), waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PaperReaderTheme {
                AppRoot(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { incoming.value = it }
    }
}

@Composable
private fun AppRoot(incoming: MutableStateFlow<Uri?>) {
    val app = LocalContext.current.applicationContext as PaperReaderApp
    var openBook by rememberSaveable { mutableStateOf<Long?>(null) }
    var importing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun import(uri: Uri) {
        importing = true
        scope.launch {
            try {
                openBook = app.library.import(uri)
            } catch (e: Exception) {
                openBook = null
                snackbar.showSnackbar(e.message ?: "Import failed")
            } finally {
                importing = false
            }
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
        LibraryScreen(onOpen = { openBook = it }, importing = importing, onImport = ::import, snackbar = snackbar)
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
