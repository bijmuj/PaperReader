package com.paperreader.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** An imported EPUB. Its unpacked files live in filesDir/books/<folder>. */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val authors: String,
    val folder: String,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)

/**
 * Where the reader was: the current chapter (book-relative href, no fragment)
 * and its scroll fraction, plus every chapter's own scroll fraction as a JSON
 * object {href: fraction} so each chapter reopens where it was left.
 */
@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val bookId: Long,
    val href: String,
    val fraction: Float,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "{}") val chapters: String = "{}",
)

/**
 * A highlight (optionally with a note) in a book. It is anchored by character offsets into the
 * visible text of its chapter, plus the quoted text and some context either side, so it can be
 * found again if the text moves (for example in a re-converted EPUB).
 */
@Entity(tableName = "highlights", indices = [Index("bookId")])
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Spine href of the chapter (book-relative, no fragment). */
    val chapter: String,
    val start: Int,
    val end: Int,
    val quote: String,
    val prefix: String,
    val suffix: String,
    /** yellow | green | blue | pink */
    val color: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun progress(bookId: Long): Progress?

    @Upsert
    suspend fun saveProgress(progress: Progress)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteProgress(bookId: Long)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt")
    fun observeHighlights(bookId: Long): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt")
    suspend fun highlights(bookId: Long): List<Highlight>

    @Insert
    suspend fun insertHighlight(highlight: Highlight): Long

    @Query("UPDATE highlights SET color = :color, updatedAt = :time WHERE id = :id")
    suspend fun setHighlightColor(id: Long, color: String, time: Long)

    @Query("UPDATE highlights SET note = :note, updatedAt = :time WHERE id = :id")
    suspend fun setHighlightNote(id: Long, note: String, time: Long)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteHighlights(bookId: Long)
}

@Database(
    entities = [Book::class, Progress::class, Highlight::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN chapters TEXT NOT NULL DEFAULT '{}'")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "paper_reader.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
