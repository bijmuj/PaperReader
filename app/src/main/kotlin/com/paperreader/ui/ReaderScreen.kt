package com.paperreader.ui

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material.icons.filled.Share
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
import com.paperreader.data.Highlight
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject
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
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    /** (matches, current index, more than shown), or null before the first search. */
    var found by remember { mutableStateOf<Triple<Int, Int, Boolean>?>(null) }
    var menu by remember { mutableStateOf(false) }

    fun onSearchResult(json: String?) {
        found = runCatching { JSONObject(json ?: "") }.getOrNull()?.let {
            Triple(it.optInt("count"), it.optInt("index", -1), it.optBoolean("capped"))
        }
    }

    fun endSearch() {
        searching = false
        found = null
        webView.evaluateJavascript("window.prSearchClear && prSearchClear()", null)
    }

    LaunchedEffect(searching, query) {
        if (!searching) return@LaunchedEffect
        if (query.isBlank()) {
            webView.evaluateJavascript("prSearchClear()", null)
            found = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350) // search once typing pauses
        webView.evaluateJavascript("prSearch(${jsString(query)})", ::onSearchResult)
    }

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

    BackHandler(enabled = searching) { endSearch() }

    fun go(href: String) {
        vm.urlFor(href)?.let { webView.evaluateJavascript("prGo(${jsString(it)})", null) }
    }

    SideEffect {
        webView.onSelectionAction = { action ->
            webView.evaluateJavascript("prTakeSelection()") { result ->
                val selection = runCatching { JSONObject(result) }.getOrNull() ?: return@evaluateJavascript
                scope.launch {
                    val h = vm.addHighlight(selection) ?: return@launch
                    webView.evaluateJavascript("prAddHighlight(${vm.highlightJson(h)})", null)
                    if (action == SelectionAction.NOTE) vm.popup = Popup.Highlight(h.id, editNote = true)
                }
            }
        }
    }
    val highlights by vm.highlights.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ReaderDrawer(
                epub = epub,
                currentHref = epub.spine.getOrNull(vm.chapter),
                isOpen = drawerState.isOpen,
                highlights = highlights,
                lost = vm.lostHighlights,
                chapterTitle = { href -> vm.chapterTitle(epub.spine.indexOf(href)) },
                onSelect = { href ->
                    go(href)
                    scope.launch { drawerState.close() }
                },
                onHighlight = { id ->
                    webView.evaluateJavascript("prShowHighlight($id)", null)
                    scope.launch { drawerState.close() }
                },
                onShare = {
                    webView.evaluateJavascript("prHighlightExport()") { result ->
                        val exported = runCatching { org.json.JSONArray(result) }.getOrElse { org.json.JSONArray() }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Notes: ${book.title}")
                            putExtra(Intent.EXTRA_TEXT, vm.notesMarkdown(exported))
                        }
                        context.startActivity(Intent.createChooser(send, "Share highlights and notes"))
                    }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (searching) {
                    SearchBar(
                        query = query,
                        found = found,
                        onQuery = { query = it },
                        onStep = { dir -> webView.evaluateJavascript("prSearchStep($dir)", ::onSearchResult) },
                        onClose = ::endSearch,
                    )
                } else TopAppBar(
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
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search in paper") }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, "Contents")
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Reading settings") }
                        if (vm.originalPdf != null) {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Original PDF page") }, onClick = {
                                        menu = false
                                        webView.evaluateJavascript("prPdfPage()") { r ->
                                            vm.popup = Popup.PdfPage(r?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                                        }
                                    })
                                }
                            }
                        }
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
private fun ReaderDrawer(
    epub: EpubBook,
    currentHref: String?,
    isOpen: Boolean,
    highlights: List<Highlight>,
    lost: Set<Long>,
    chapterTitle: (String) -> String,
    onSelect: (String) -> Unit,
    onHighlight: (Long) -> Unit,
    onShare: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalDrawerSheet {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                Text(if (highlights.isEmpty()) "Highlights" else "Highlights (${highlights.size})")
            })
        }
        if (tab == 0) {
            TocList(epub, currentHref, isOpen, onSelect)
        } else {
            HighlightList(epub, highlights, lost, chapterTitle, onHighlight, onShare)
        }
    }
}

@Composable
private fun HighlightList(
    epub: EpubBook,
    highlights: List<Highlight>,
    lost: Set<Long>,
    chapterTitle: (String) -> String,
    onHighlight: (Long) -> Unit,
    onShare: () -> Unit,
) {
    if (highlights.isEmpty()) {
        Text(
            "No highlights yet.\n\nSelect text with a long press, then choose Highlight or Note.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(28.dp),
        )
        return
    }
    // In reading order: by chapter, then position.
    val sorted = remember(highlights) { highlights.sortedWith(compareBy({ epub.spine.indexOf(it.chapter) }, { it.start })) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "share") {
            TextButton(onClick = onShare, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share as Markdown")
            }
        }
        itemsIndexed(sorted, key = { _, h -> h.id }) { i, h ->
            if (i == 0 || sorted[i - 1].chapter != h.chapter) {
                Text(
                    chapterTitle(h.chapter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = h.id !in lost) { onHighlight(h.id) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(highlightSwatch(h.color), RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        h.quote.replace(Regex("\\s+"), " "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (h.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            h.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (h.id in lost) {
                        Text(
                            "Not found in this version of the text",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

fun highlightSwatch(color: String) = when (color) {
    "green" -> Color(0xFF46C85A)
    "blue" -> Color(0xFF4691FF)
    "pink" -> Color(0xFFFF5096)
    else -> Color(0xFFFFCD00)
}

@Composable
private fun TocList(epub: EpubBook, currentHref: String?, isOpen: Boolean, onSelect: (String) -> Unit) {
    val current = epub.toc.indexOfFirst { it.href.substringBefore('#') == currentHref }
    val listState = rememberLazyListState()
    LaunchedEffect(isOpen) {
        if (isOpen && current >= 0) listState.scrollToItem((current - 2).coerceAtLeast(0))
    }
    Column {
        Spacer(Modifier.height(8.dp))
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
                        .clickable { vm.popup = Popup.Image(p.src, p.pdfPage, p.pdfClip) },
                )
                if (p.caption.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(p.caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (p.pdfPage >= 0 && vm.originalPdf != null) {
                        TextButton(onClick = { vm.popup = Popup.PdfPage(p.pdfPage, p.pdfClip) }) { Text("PDF page") }
                    }
                    TextButton(onClick = { vm.popup = Popup.Image(p.src, p.pdfPage, p.pdfClip) }) { Text("Zoom") }
                    TextButton(onClick = {
                        webView.evaluateJavascript("prJump(${jsString(p.target)})", null)
                        vm.popup = null
                    }) { Text("Go to figure") }
                }
            }
        }
        is Popup.Image -> ImageViewer(
            file = vm.fileFor(p.src),
            onDismiss = dismiss,
            onOriginalPage = if (p.pdfPage >= 0 && vm.originalPdf != null) {
                { vm.popup = Popup.PdfPage(p.pdfPage, p.pdfClip) }
            } else null,
        )
        is Popup.PdfPage -> vm.originalPdf?.let { pdf ->
            val clip = p.clip.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 4 }?.toFloatArray()
            PdfPageViewer(pdf, p.page, clip, onDismiss = dismiss)
        } ?: run { vm.popup = null }
        is Popup.Highlight -> HighlightSheet(vm, webView, p)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightSheet(vm: ReaderViewModel, webView: WebView, p: Popup.Highlight) {
    val h = vm.highlight(p.id) ?: run {
        vm.popup = null
        return
    }
    val context = LocalContext.current
    var note by remember(p.id) { mutableStateOf(h.note) }
    val focus = remember { FocusRequester() }

    fun sync(color: String, hasNote: Boolean) =
        webView.evaluateJavascript("prUpdateHighlight(${h.id}, ${jsString(color)}, $hasNote)", null)

    fun close() {
        vm.setHighlightNote(h.id, note.trim())
        sync(vm.highlight(h.id)?.color ?: h.color, note.isNotBlank())
        vm.popup = null
    }

    ModalBottomSheet(onDismissRequest = ::close) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(highlightSwatch(h.color), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                Text(
                    h.quote.replace(Regex("\\s+"), " "),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HIGHLIGHT_COLORS.forEach { c ->
                    val selected = c == h.color
                    Box(
                        Modifier
                            .size(if (selected) 36.dp else 30.dp)
                            .clip(CircleShape)
                            .background(highlightSwatch(c))
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable {
                                vm.setHighlightColor(h.id, c)
                                sync(c, note.isNotBlank())
                            }
                            .semantics { contentDescription = "$c highlight" },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    vm.deleteHighlight(h.id)
                    webView.evaluateJavascript("prRemoveHighlight(${h.id})", null)
                    vm.popup = null
                }) { Text("Delete") }
                TextButton(onClick = {
                    val text = h.quote.replace(Regex("\\s+"), " ") + if (note.isNotBlank()) "\n\n${note.trim()}" else ""
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Highlight", text))
                }) { Text("Copy") }
                TextButton(onClick = ::close) { Text("Done") }
            }
        }
    }
    LaunchedEffect(p.id) { if (p.editNote) focus.requestFocus() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    found: Triple<Int, Int, Boolean>?,
    onQuery: (String) -> Unit,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search in paper") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onStep(1) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (found != null && query.isNotBlank()) {
                val (count, index, capped) = found
                Text(
                    if (count == 0) "None" else "${index + 1} of $count${if (capped) "+" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val any = (found?.first ?: 0) > 0
            IconButton(onClick = { onStep(-1) }, enabled = any) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = { onStep(1) }, enabled = any) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
        },
    )
}
