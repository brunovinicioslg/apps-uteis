package io.github.brunovinicioslg.sigilo.app.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.Closeable
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The app's encrypted database (SQLCipher, AES-256): conversations, messages and the Signal
 * protocol state. Opened with the vault's master key; tests open the same schema on plain SQLite.
 * Not thread-safe by design: the message engine uses it from a single thread.
 */
class SigiloDatabase private constructor(private val helper: SupportSQLiteOpenHelper) : Closeable {

    val sql: SupportSQLiteDatabase get() = helper.writableDatabase

    fun <T> transaction(block: () -> T): T {
        val db = sql
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun insert(table: String, values: ContentValues, conflict: Int = SQLiteDatabase.CONFLICT_ABORT): Long = sql.insert(table, conflict, values)

    fun update(table: String, values: ContentValues, where: String, vararg args: Any?): Int =
        sql.update(table, SQLiteDatabase.CONFLICT_ABORT, values, where, args)

    fun delete(table: String, where: String, vararg args: Any?): Int = sql.delete(table, where, args)

    fun <T> query(query: String, vararg args: Any?, map: (Cursor) -> T): List<T> =
        sql.query(query, args).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

    fun <T> queryOne(query: String, vararg args: Any?, map: (Cursor) -> T): T? =
        sql.query(query, args).use { c -> if (c.moveToFirst()) map(c) else null }

    fun longOrNull(query: String, vararg args: Any?): Long? = queryOne(query, *args) { if (it.isNull(0)) null else it.getLong(0) }

    override fun close() = helper.close()

    private class Callback : SupportSQLiteOpenHelper.Callback(VERSION) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            // Deleted rows are overwritten, not just unlinked: disappearing messages really go.
            db.query("PRAGMA secure_delete = ON").close()
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            SCHEMA.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first; every later version adds its migration here, with a test.
            error("no migration from $oldVersion to $newVersion")
        }
    }

    companion object {
        const val NAME = "sigilo.db"
        const val VERSION = 1

        fun open(context: Context, factory: SupportSQLiteOpenHelper.Factory, name: String? = NAME): SigiloDatabase {
            val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(Callback()).build()
            // Opened right away, so a wrong key fails here and not in the middle of some later query.
            return SigiloDatabase(factory.create(config)).also { it.sql }
        }

        /** SQLCipher with the master key as a raw key (no key stretching: it is already random). */
        fun encrypted(key: ByteArray): SupportSQLiteOpenHelper.Factory {
            System.loadLibrary("sqlcipher")
            val raw = "x'" + key.joinToString("") { "%02x".format(it) } + "'"
            return SupportOpenHelperFactory(raw.toByteArray(Charsets.US_ASCII))
        }

        private val SCHEMA = listOf(
            // Signal protocol state.
            "CREATE TABLE local_identity (id INTEGER PRIMARY KEY CHECK (id = 1), key_pair BLOB NOT NULL, registration_id INTEGER NOT NULL)",
            "CREATE TABLE prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
            "CREATE TABLE signed_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
            "CREATE TABLE kyber_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
            "CREATE TABLE kyber_used (kyber_id INTEGER NOT NULL, signed_id INTEGER NOT NULL, base_key BLOB NOT NULL, " +
                "PRIMARY KEY (kyber_id, signed_id, base_key))",
            "CREATE TABLE sessions (address TEXT NOT NULL, device INTEGER NOT NULL, record BLOB NOT NULL, PRIMARY KEY (address, device))",
            "CREATE TABLE identities (address TEXT PRIMARY KEY, identity_key BLOB NOT NULL, verified INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL)",
            "CREATE TABLE sender_keys (address TEXT NOT NULL, device INTEGER NOT NULL, distribution_id TEXT NOT NULL, record BLOB NOT NULL, " +
                "PRIMARY KEY (address, device, distribution_id))",
            // Conversations and messages.
            """CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT NOT NULL UNIQUE,
                encryption INTEGER NOT NULL DEFAULT 0,
                expire_seconds INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0,
                last_at INTEGER NOT NULL DEFAULT 0,
                snippet TEXT,
                snippet_kind INTEGER NOT NULL DEFAULT 0,
                subscription_id INTEGER NOT NULL DEFAULT -1,
                key_changed INTEGER NOT NULL DEFAULT 0,
                last_encrypted_sent_at INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                outgoing INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                body TEXT NOT NULL,
                encrypted INTEGER NOT NULL,
                status INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                remote_id INTEGER,
                parts INTEGER NOT NULL DEFAULT 1,
                parts_sent INTEGER NOT NULL DEFAULT 0,
                parts_delivered INTEGER NOT NULL DEFAULT 0,
                provider_id INTEGER,
                expire_seconds INTEGER NOT NULL DEFAULT 0,
                expire_at INTEGER,
                read INTEGER NOT NULL DEFAULT 1
            )""",
            "CREATE INDEX messages_conversation ON messages (conversation_id, received_at)",
            "CREATE INDEX messages_expire ON messages (expire_at) WHERE expire_at IS NOT NULL",
            "CREATE UNIQUE INDEX messages_provider ON messages (provider_id) WHERE provider_id IS NOT NULL",
            // Parts of encrypted messages still waiting for the rest.
            "CREATE TABLE fragments (sender TEXT NOT NULL, message_id INTEGER NOT NULL, text TEXT NOT NULL, received_at INTEGER NOT NULL, " +
                "UNIQUE (sender, text))",
            // Messages that arrived with a changed identity key, kept until the user decides.
            "CREATE TABLE held_envelopes (id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT NOT NULL, envelope BLOB NOT NULL, " +
                "identity BLOB NOT NULL, received_at INTEGER NOT NULL)",
            "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )
    }
}
