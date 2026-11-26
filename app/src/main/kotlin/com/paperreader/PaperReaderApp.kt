package com.paperreader

import android.app.Application
import com.paperreader.data.AppDatabase
import com.paperreader.data.ReaderSettings
import com.paperreader.epub.Library

class PaperReaderApp : Application() {
    val db by lazy { AppDatabase.create(this) }
    val settings by lazy { ReaderSettings(this) }
    val library by lazy { Library(this, db.books()) }
}
