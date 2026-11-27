package com.paperreader.epub

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.paperreader.data.Book
import com.paperreader.data.BookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imports, unpacks and deletes books. Unpacked books live in filesDir/books. */
class Library(private val context: Context, private val dao: BookDao) {
    val booksDir: File = File(context.filesDir, "books").apply { mkdirs() }

    fun observe() = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)

    fun folder(book: Book) = File(booksDir, book.folder)

    /**
     * Unpacks the EPUB at [uri] and adds it to the library, keeping [original] (the PDF it was
     * converted from) beside it. Returns the new book id.
     */
    suspend fun import(uri: Uri, original: File? = null): Long = withContext(Dispatchers.IO) {
        val folder = UUID.randomUUID().toString()
        val dest = File(booksDir, folder)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
            input.use { unzip(it, dest) }
            val epub = runCatching { EpubBook.parse(dest) }.getOrElse {
                throw IOException("${displayName(uri) ?: "This file"} is not a readable EPUB", it)
            }
            original?.copyTo(File(dest, ORIGINAL_PDF), overwrite = true)
            dao.insert(
                Book(
                    title = epub.title,
                    authors = epub.authors.joinToString(", "),
                    folder = folder,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        dao.delete(book.id)
        dao.deleteProgress(book.id)
        dao.deleteHighlights(book.id)
        folder(book).deleteRecursively()
    }

    private fun unzip(input: java.io.InputStream, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                val out = File(dest, entry.name)
                // Refuse entries that would escape the book folder ("../", absolute paths).
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        if (entries == 0) throw IOException("Not a ZIP/EPUB file")
    }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}

/** The PDF a book was converted from, kept in the book's folder. */
const val ORIGINAL_PDF = "original.pdf"
