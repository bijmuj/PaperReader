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
import com.paperreader.convert.Arxiv
import com.paperreader.data.Book
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Long) -> Unit,
    status: String?,
    onImport: (Uri) -> Unit,
    onArxiv: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    val app = LocalContext.current.applicationContext as PaperReaderApp
    val books by app.library.observe().collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<Book?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var arxivDialog by remember { mutableStateOf(false) }

    // Some file managers don't label .epub files, so accept anything and validate on import.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Papers") }) },
        floatingActionButton = {
            if (status == null) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Add paper") },
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("From arXiv…") }, onClick = {
                            addMenu = false
                            arxivDialog = true
                        })
                        DropdownMenuItem(text = { Text("PDF or EPUB file…") }, onClick = {
                            addMenu = false
                            picker.launch(arrayOf("application/pdf", "application/epub+zip", "*/*"))
                        })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (status != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    status,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val list = books
            when {
                list == null -> Unit
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No papers yet.\n\nTap Add paper to paste an arXiv link or pick a PDF, or share a link or " +
                            "a PDF to Paper Reader from another app.",
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

    if (arxivDialog) {
        ArxivDialog(onDismiss = { arxivDialog = false }, onConvert = {
            arxivDialog = false
            onArxiv(it)
        })
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
private fun ArxivDialog(onDismiss: () -> Unit, onConvert: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val valid = Arxiv.parseId(text) != null || Arxiv.findId(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from arXiv") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("arXiv link or ID") },
                placeholder = { Text("2604.14191") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onConvert(text) }),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConvert(text) }) { Text("Convert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
