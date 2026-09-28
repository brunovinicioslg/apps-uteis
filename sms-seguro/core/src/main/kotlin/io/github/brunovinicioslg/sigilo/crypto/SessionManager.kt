package io.github.brunovinicioslg.sigilo.crypto

import io.github.brunovinicioslg.sigilo.wire.MalformedException
import java.security.SecureRandom
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** An encrypted Signal message; [preKey] marks the first message of a session, which carries its setup. */
class Sealed(val preKey: Boolean, val bytes: ByteArray)

sealed interface Opened {
    class Plaintext(val bytes: ByteArray) : Opened

    /** Already decrypted before (the SMS network delivered it twice). */
    data object Duplicate : Opened

    /**
     * The contact's identity key differs from the one we knew: they reinstalled the app, changed
     * phones, or someone is impersonating them. The message is kept undecrypted until the user
     * checks the new safety number and accepts the key.
     */
    class IdentityChanged(val newIdentity: IdentityKey) : Opened

    /** No session and no setup data: the contact must send an invite again. */
    data object NoSession : Opened

    /** Corrupted, forged, or from keys we no longer have. */
    class Invalid(val reason: String) : Opened
}

/**
 * Signal protocol sessions with SMS contacts: PQXDH to start (X25519 + ML-KEM-1024, so a
 * recorded conversation stays safe even against future quantum computers) and the double ratchet
 * afterwards (every message has its own key; stealing today's keys does not reveal past messages).
 * Contacts are identified by their normalized phone number.
 */
class SessionManager(private val store: SignalProtocolStore, private val random: SecureRandom = SecureRandom()) {

    private val localAddress = SignalProtocolAddress(LOCAL_NAME, KeyBundle.DEVICE_ID)

    private fun address(contact: String) = SignalProtocolAddress(contact, KeyBundle.DEVICE_ID)

    // libsignal's constructors disagree on argument order: SessionBuilder takes (remote, local)
    // while SessionCipher takes (local, remote). Swapping them fails only at runtime, so every use
    // goes through these two functions.
    private fun builderFor(contact: String) = SessionBuilder(store, address(contact), localAddress)

    private fun cipherFor(contact: String) = SessionCipher(store, localAddress, address(contact))

    /**
     * Fresh public keys for an invite or QR code. Each bundle gets new one-time keys, so a bundle
     * starts at most one session, and the private halves stay in the store until used.
     */
    fun createBundle(now: Long = System.currentTimeMillis()): KeyBundle {
        val identity = store.identityKeyPair
        val signed = ECKeyPair.generate()
        val signedId = freeId { store.containsSignedPreKey(it) }
        val signedSignature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
        store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, now, signed, signedSignature))

        val oneTime = ECKeyPair.generate()
        val oneTimeId = freeId { store.containsPreKey(it) }
        store.storePreKey(oneTimeId, PreKeyRecord(oneTimeId, oneTime))

        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberId = freeId { store.containsKyberPreKey(it) }
        val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, now, kyber, kyberSignature))

        return KeyBundle(
            registrationId = store.localRegistrationId,
            identityKey = identity.publicKey.serialize(),
            signedPreKeyId = signedId,
            signedPreKey = signed.publicKey.serialize(),
            signedPreKeySignature = signedSignature,
            preKeyId = oneTimeId,
            preKey = oneTime.publicKey.serialize(),
            kyberPreKeyId = kyberId,
            kyberPreKey = kyber.publicKey.serialize(),
            kyberPreKeySignature = kyberSignature,
        )
    }

    /**
     * Starts a session from a contact's bundle; our next message to them sets up theirs.
     * @return null on success, or the new identity when it differs from the one we trusted.
     */
    fun acceptBundle(contact: String, bundle: KeyBundle): IdentityKey? {
        val preKeyBundle = bundle.toPreKeyBundle()
        return try {
            builderFor(contact).process(preKeyBundle)
            null
        } catch (e: UntrustedIdentityException) {
            preKeyBundle.identityKey
        } catch (e: InvalidKeyException) {
            throw MalformedException("bundle rejected: ${e.message}")
        }
    }

    fun hasSession(contact: String): Boolean = store.containsSession(address(contact))

    fun encrypt(contact: String, plaintext: ByteArray): Sealed {
        val message: CiphertextMessage = try {
            cipherFor(contact).encrypt(plaintext)
        } catch (e: NoSessionException) {
            throw IllegalStateException("no session with $contact", e)
        }
        return Sealed(message.type == CiphertextMessage.PREKEY_TYPE, message.serialize())
    }

    fun decrypt(contact: String, sealed: Sealed): Opened {
        val cipher = cipherFor(contact)
        return try {
            val bytes = if (sealed.preKey) cipher.decrypt(PreKeySignalMessage(sealed.bytes)) else cipher.decrypt(SignalMessage(sealed.bytes))
            Opened.Plaintext(bytes)
        } catch (e: DuplicateMessageException) {
            Opened.Duplicate
        } catch (e: UntrustedIdentityException) {
            val newIdentity = if (sealed.preKey) runCatching { PreKeySignalMessage(sealed.bytes).identityKey }.getOrNull() else null
            if (newIdentity != null) Opened.IdentityChanged(newIdentity) else Opened.Invalid("untrusted identity")
        } catch (e: NoSessionException) {
            Opened.NoSession
        } catch (e: InvalidMessageException) {
            Opened.Invalid("invalid message: ${e.message}")
        } catch (e: InvalidKeyIdException) {
            Opened.Invalid("setup keys already used or deleted: ${e.message}")
        } catch (e: InvalidKeyException) {
            Opened.Invalid("invalid key: ${e.message}")
        } catch (e: InvalidVersionException) {
            Opened.Invalid("unsupported version: ${e.message}")
        } catch (e: LegacyMessageException) {
            Opened.Invalid("legacy message")
        }
    }

    /** After the user checked the new safety number: trust the key and forget the old session. */
    fun trustIdentity(contact: String, identity: IdentityKey) {
        store.saveIdentity(address(contact), identity)
        store.deleteSession(address(contact))
    }

    fun identityOf(contact: String): IdentityKey? = store.getIdentity(address(contact))

    /**
     * The 60-digit safety number both phones show; equal on both sides only if nobody is in the
     * middle. It depends on the two identity keys alone, so phone number formats cannot break it.
     */
    fun safetyNumber(contact: String): String? {
        val remote = identityOf(contact) ?: return null
        return NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
            .createFor(FINGERPRINT_VERSION, ByteArray(0), store.identityKeyPair.publicKey, ByteArray(0), remote)
            .displayableFingerprint.displayText
    }

    private fun freeId(taken: (Int) -> Boolean): Int {
        while (true) {
            val id = 1 + random.nextInt(MAX_KEY_ID)
            if (!taken(id)) return id
        }
    }

    private companion object {
        const val LOCAL_NAME = "self"
        const val MAX_KEY_ID = 0xFFFFFF
        const val FINGERPRINT_ITERATIONS = 5200
        const val FINGERPRINT_VERSION = 2
    }
}
