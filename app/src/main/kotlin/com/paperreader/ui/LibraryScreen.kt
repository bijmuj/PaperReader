package com.paperreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paperreader.PaperReaderApp
import com.paperreader.data.Book
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Long) -> Unit, importing: Boolean, onImport: (Uri) -> Unit, snackbar: SnackbarHostState) {
    val app = LocalContext.current.applicationContext as PaperReaderApp
    val books by app.library.observe().collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<Book?>(null) }

    // Some file managers don't label .epub files, so accept anything and validate on import.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Papers") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/epub+zip", "*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Import EPUB") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            val list = books
            when {
                list == null -> Unit
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No papers yet.\n\nConvert a PDF with paper2epub on your computer, then import the .epub here " +
                            "or open it from your file manager.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyColumn {
                    items(list, key = { it.id }) { book ->
                        BookRow(book, onClick = { onOpen(book.id) }, onDelete = { toDelete = book })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    toDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove paper?") },
            text = { Text("“${book.title}” and your reading position will be removed from the app.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { app.library.delete(book) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        supportingContent = book.authors.takeIf { it.isNotBlank() }?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        },
    ) {
        Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
