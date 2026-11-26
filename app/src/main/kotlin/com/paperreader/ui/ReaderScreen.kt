package com.paperreader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperreader.PaperReaderApp
import com.paperreader.data.ReaderTheme
import com.paperreader.epub.EpubBook
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

private fun themeBackground(theme: String) = when (theme) {
    "dark" -> 0xFF141414.toInt()
    "sepia" -> 0xFFF4ECD8.toInt()
    else -> 0xFFFFFFFF.toInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperReaderApp
    val vm: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = viewModelFactory { initializer { ReaderViewModel(app, bookId) } },
    )
    val book = vm.book
    val epub = vm.epub

    vm.error?.let { message ->
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message)
                TextButton(onClick = onClose) { Text("Back to library") }
            }
        }
        return
    }
    if (book == null || epub == null) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val settings by app.settings.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val themeName = when (settings.theme) {
        ReaderTheme.SYSTEM -> if (systemDark) "dark" else "light"
        ReaderTheme.LIGHT -> "light"
        ReaderTheme.SEPIA -> "sepia"
        ReaderTheme.DARK -> "dark"
    }
    // Read by the request handler thread when a page is served.
    val themeRef = remember { AtomicReference(themeName) }
    themeRef.set(themeName)

    val webView = remember(book.id) {
        createReaderWebView(
            context = context,
            booksDir = app.library.booksDir,
            vm = vm,
            shell = { shellPage(vm.shellConfig(), themeRef.get()) },
            openExternal = { uri ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
            },
        ).also { wv ->
            wv.setBackgroundColor(themeBackground(themeName))
            wv.settings.textZoom = settings.textZoom
            vm.shellUrl?.let(wv::loadUrl)
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    LaunchedEffect(themeName) {
        webView.setBackgroundColor(themeBackground(themeName))
        webView.evaluateJavascript("window.prSetTheme && prSetTheme('$themeName')", null)
    }
    LaunchedEffect(settings.textZoom) { webView.settings.textZoom = settings.textZoom }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            // Back first retraces jumps made by following links, then leaves the book.
            webView.evaluateJavascript("window.prBack ? prBack() : false") { handled ->
                if (handled != "true") onClose()
            }
        }
    }

    fun go(href: String) {
        vm.urlFor(href)?.let { webView.evaluateJavascript("prGo(${jsString(it)})", null) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            TocDrawer(epub, epub.spine.getOrNull(vm.chapter), drawerState.isOpen) { href ->
                go(href)
                scope.launch { drawerState.close() }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                vm.chapterTitle(vm.chapter).ifEmpty { book.title },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${vm.chapter + 1} / ${epub.spine.size}  ·  ${book.title}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Library") }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, "Contents")
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Reading settings") }
                    },
                )
            },
        ) { padding ->
            AndroidView(factory = { webView }, modifier = Modifier.padding(padding).fillMaxSize())
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReaderSettingsSheet(
                textZoom = settings.textZoom,
                theme = settings.theme,
                onTextZoom = { z -> app.settings.update { it.copy(textZoom = z) } },
                onTheme = { t -> app.settings.update { it.copy(theme = t) } },
            )
        }
    }

    PopupLayer(vm, webView)
}

@Composable
private fun TocDrawer(epub: EpubBook, currentHref: String?, isOpen: Boolean, onSelect: (String) -> Unit) {
    val current = epub.toc.indexOfFirst { it.href.substringBefore('#') == currentHref }
    val listState = rememberLazyListState()
    LaunchedEffect(isOpen) {
        if (isOpen && current >= 0) listState.scrollToItem((current - 2).coerceAtLeast(0))
    }
    ModalDrawerSheet {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
        )
        LazyColumn(state = listState) {
            itemsIndexed(epub.toc) { i, entry ->
                NavigationDrawerItem(
                    label = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    selected = i == current,
                    onClick = { onSelect(entry.href) },
                    modifier = Modifier.padding(start = (12 + 16 * entry.depth).dp, end = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    textZoom: Int,
    theme: ReaderTheme,
    onTextZoom: (Int) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Text size", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onTextZoom((textZoom - 10).coerceAtLeast(70)) }, enabled = textZoom > 70) { Text("A−") }
            Text("$textZoom%", textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onTextZoom((textZoom + 10).coerceAtMost(200)) }, enabled = textZoom < 200) { Text("A+") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val options = ReaderTheme.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = t == theme,
                    onClick = { onTheme(t) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PopupLayer(vm: ReaderViewModel, webView: WebView) {
    val dismiss = { vm.popup = null }
    when (val p = vm.popup) {
        null -> Unit
        is Popup.Note -> ModalBottomSheet(onDismissRequest = dismiss) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text(p.text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        webView.evaluateJavascript("prJump(${jsString(p.target)})", null)
                        vm.popup = null
                    }) { Text("Go to reference") }
                }
            }
        }
        is Popup.Figure -> ModalBottomSheet(onDismissRequest = dismiss) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                LocalImage(
                    file = vm.fileFor(p.src),
                    maxSide = 2048,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .clickable { vm.popup = Popup.Image(p.src) },
                )
                if (p.caption.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(p.caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.popup = Popup.Image(p.src) }) { Text("Zoom") }
                    TextButton(onClick = {
                        webView.evaluateJavascript("prJump(${jsString(p.target)})", null)
                        vm.popup = null
                    }) { Text("Go to figure") }
                }
            }
        }
        is Popup.Image -> ImageViewer(file = vm.fileFor(p.src), onDismiss = dismiss)
    }
}
