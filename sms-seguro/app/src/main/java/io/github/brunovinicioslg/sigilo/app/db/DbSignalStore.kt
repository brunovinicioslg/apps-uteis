package io.github.brunovinicioslg.sigilo.app.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.SecureRandom
import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * The Signal protocol state, kept in the encrypted database. The local identity is created on
 * first use. Contacts' identity keys are trusted on first use; a different key later is not
 * trusted until the user accepts it (see SessionManager.trustIdentity).
 */
class DbSignalStore(private val db: SigiloDatabase, now: () -> Long = System::currentTimeMillis) : SignalProtocolStore {

    private val clock = now

    private val local: Pair<IdentityKeyPair, Int> by lazy {
        db.queryOne("SELECT key_pair, registration_id FROM local_identity WHERE id = 1") { IdentityKeyPair(it.getBlob(0)) to it.getInt(1) }
            ?: run {
                val pair = IdentityKeyPair.generate()
                // Registration ids are 14-bit in the Signal protocol.
                val registrationId = 1 + SecureRandom().nextInt(MAX_REGISTRATION_ID)
                db.insert("local_identity", ContentValues().apply {
                    put("id", 1)
                    put("key_pair", pair.serialize())
                    put("registration_id", registrationId)
                })
                pair to registrationId
            }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = local.first

    override fun getLocalRegistrationId(): Int = local.second

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        if (existing == identityKey) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        db.insert("identities", ContentValues().apply {
            put("address", address.name)
            put("identity_key", identityKey.serialize())
            put("verified", 0)
            put("updated_at", clock())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return if (existing == null) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val known = getIdentity(address) ?: return true
        return known == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        db.queryOne("SELECT identity_key FROM identities WHERE address = ?", address.name) { IdentityKey(it.getBlob(0)) }

    fun isVerified(address: String): Boolean =
        db.queryOne("SELECT verified FROM identities WHERE address = ?", address) { it.getInt(0) == 1 } ?: false

    /** After comparing the safety number in person. */
    fun setVerified(address: String, verified: Boolean) {
        db.update("identities", ContentValues().apply { put("verified", if (verified) 1 else 0) }, "address = ?", address)
    }

    // One-time prekeys.
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        db.queryOne("SELECT record FROM prekeys WHERE id = ?", preKeyId) { PreKeyRecord(it.getBlob(0)) }
            ?: throw InvalidKeyIdException("no prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = putRecord("prekeys", preKeyId, record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean = contains("prekeys", preKeyId)

    override fun removePreKey(preKeyId: Int) {
        db.delete("prekeys", "id = ?", preKeyId)
    }

    // Signed prekeys.
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        db.queryOne("SELECT record FROM signed_prekeys WHERE id = ?", signedPreKeyId) { SignedPreKeyRecord(it.getBlob(0)) }
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = db.query("SELECT record FROM signed_prekeys") { SignedPreKeyRecord(it.getBlob(0)) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = putRecord("signed_prekeys", signedPreKeyId, record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = contains("signed_prekeys", signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        db.delete("signed_prekeys", "id = ?", signedPreKeyId)
    }

    // Kyber (post-quantum) prekeys: one per invite, so each is used at most once.
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        db.queryOne("SELECT record FROM kyber_prekeys WHERE id = ?", kyberPreKeyId) { KyberPreKeyRecord(it.getBlob(0)) }
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = db.query("SELECT record FROM kyber_prekeys") { KyberPreKeyRecord(it.getBlob(0)) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = putRecord("kyber_prekeys", kyberPreKeyId, record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = contains("kyber_prekeys", kyberPreKeyId)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val inserted = db.insert("kyber_used", ContentValues().apply {
            put("kyber_id", kyberPreKeyId)
            put("signed_id", signedPreKeyId)
            put("base_key", baseKey.serialize())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        // The same setup replayed to start a second session: refuse, as a one-time key must.
        if (inserted == -1L) throw ReusedBaseKeyException("kyber prekey $kyberPreKeyId already used with this base key")
        // Forward secrecy: the private half is not needed any more once the session exists.
        db.delete("kyber_prekeys", "id = ?", kyberPreKeyId)
    }

    // Sessions.
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        db.queryOne("SELECT record FROM sessions WHERE address = ? AND device = ?", address.name, address.deviceId) { SessionRecord(it.getBlob(0)) }
            ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = addresses.map { address ->
        db.queryOne("SELECT record FROM sessions WHERE address = ? AND device = ?", address.name, address.deviceId) { SessionRecord(it.getBlob(0)) }
            ?: throw NoSessionException("no session for $address")
    }

    override fun getSubDeviceSessions(name: String): List<Int> =
        db.query("SELECT device FROM sessions WHERE address = ? AND device != 1", name) { it.getInt(0) }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        db.insert("sessions", ContentValues().apply {
            put("address", address.name)
            put("device", address.deviceId)
            put("record", record.serialize())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        val record = db.queryOne("SELECT record FROM sessions WHERE address = ? AND device = ?", address.name, address.deviceId) {
            SessionRecord(it.getBlob(0))
        } ?: return false
        return record.hasSenderChain()
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        db.delete("sessions", "address = ? AND device = ?", address.name, address.deviceId)
    }

    override fun deleteAllSessions(name: String) {
        db.delete("sessions", "address = ?", name)
    }

    // Sender keys (group messaging; unused until groups arrive, kept for the interface).
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        db.insert("sender_keys", ContentValues().apply {
            put("address", sender.name)
            put("device", sender.deviceId)
            put("distribution_id", distributionId.toString())
            put("record", record.serialize())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        db.queryOne(
            "SELECT record FROM sender_keys WHERE address = ? AND device = ? AND distribution_id = ?",
            sender.name, sender.deviceId, distributionId.toString(),
        ) { SenderKeyRecord(it.getBlob(0)) }

    private fun putRecord(table: String, id: Int, record: ByteArray) {
        db.insert(table, ContentValues().apply {
            put("id", id)
            put("record", record)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun contains(table: String, id: Int): Boolean = db.longOrNull("SELECT 1 FROM $table WHERE id = ?", id) != null

    private companion object {
        const val MAX_REGISTRATION_ID = 16_380
    }
}
