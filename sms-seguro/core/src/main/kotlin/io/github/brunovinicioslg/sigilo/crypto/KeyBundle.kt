package io.github.brunovinicioslg.sigilo.crypto

import io.github.brunovinicioslg.sigilo.wire.ByteReader
import io.github.brunovinicioslg.sigilo.wire.ByteWriter
import io.github.brunovinicioslg.sigilo.wire.MalformedException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * The public keys someone needs to start an encrypted conversation with us (Signal's PQXDH "prekey
 * bundle"). Without a server, it travels directly: in a QR code in person, or by SMS as an invite.
 * All of it is public; the signatures prove the keys belong to [identityKey].
 */
class KeyBundle(
    val registrationId: Int,
    val identityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val preKeyId: Int,
    val preKey: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
) {
    fun encode(): ByteArray = ByteWriter()
        .byte(VERSION)
        .varint(registrationId.toLong())
        .blob(identityKey)
        .varint(signedPreKeyId.toLong()).blob(signedPreKey).blob(signedPreKeySignature)
        .varint(preKeyId.toLong()).blob(preKey)
        .varint(kyberPreKeyId.toLong()).blob(kyberPreKey).blob(kyberPreKeySignature)
        .toByteArray()

    /** Rebuilds libsignal's bundle; throws [MalformedException] for keys that do not parse. */
    fun toPreKeyBundle(): PreKeyBundle = try {
        PreKeyBundle(
            registrationId, DEVICE_ID,
            preKeyId, ECPublicKey(preKey),
            signedPreKeyId, ECPublicKey(signedPreKey), signedPreKeySignature,
            IdentityKey(identityKey),
            kyberPreKeyId, KEMPublicKey(kyberPreKey), kyberPreKeySignature,
        )
    } catch (e: InvalidKeyException) {
        throw MalformedException("invalid key in bundle: ${e.message}")
    }

    companion object {
        const val VERSION = 1

        /** Signal supports several devices per account; Sigilo has one per phone number. */
        const val DEVICE_ID = 1

        private const val MAX_KEY = 2_048

        fun decode(bytes: ByteArray): KeyBundle {
            val r = ByteReader(bytes)
            val version = r.byte()
            if (version != VERSION) throw MalformedException("bundle version $version")
            val ids = 1L..Int.MAX_VALUE.toLong()
            return KeyBundle(
                registrationId = r.varint(ids).toInt(),
                identityKey = r.blob(MAX_KEY),
                signedPreKeyId = r.varint(ids).toInt(),
                signedPreKey = r.blob(MAX_KEY),
                signedPreKeySignature = r.blob(MAX_KEY),
                preKeyId = r.varint(ids).toInt(),
                preKey = r.blob(MAX_KEY),
                kyberPreKeyId = r.varint(ids).toInt(),
                kyberPreKey = r.blob(MAX_KEY),
                kyberPreKeySignature = r.blob(MAX_KEY),
            ).also { r.expectEnd() }
        }
    }
}
