package io.github.brunovinicioslg.sossego.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The lists and the history of blocked calls. Read by the call screening on every call. */
class SossegoDatabase(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {

    init {
        // The screening reads while the screen writes.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                list TEXT NOT NULL,
                match_kind TEXT NOT NULL,
                pattern TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                UNIQUE (list, match_kind, pattern)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                number_key TEXT NOT NULL,
                reason TEXT NOT NULL,
                silenced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX history_key_time ON history (number_key, time)")
        db.execSQL("CREATE INDEX history_time ON history (time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first; later versions migrate here, never by dropping the user's lists.
    }

    companion object {
        const val NAME = "sossego.db"
        private const val VERSION = 1
    }
}
