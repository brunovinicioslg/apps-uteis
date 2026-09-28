package io.github.brunovinicioslg.sigilo.app.vault

import io.github.brunovinicioslg.sigilo.wire.ByteReader
import io.github.brunovinicioslg.sigilo.wire.ByteWriter
import io.github.brunovinicioslg.sigilo.wire.MalformedException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.PasswordConverter
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id cost. Stored with each vault, so it can be raised later without breaking old ones. */
data class KdfParams(val memoryKiB: Int = 32 * 1024, val iterations: Int = 3, val parallelism: Int = 1) {
    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .withCharToByteConverter(PasswordConverter.UTF8)
            .build()
        val out = ByteArray(KEY_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, out)
        return out
    }

    companion object {
        const val KEY_BYTES = 32
    }
}

class WrongPasswordException : Exception("wrong password")

/** The vault file is missing, damaged, or was made on another device (restored backup). */
class VaultUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Holds the master key of the encrypted database. The key is random (32 bytes) and never stored in
 * the clear: it is sealed by the device's secure hardware ([KeyWrapper]) and, when the user sets a
 * password, first by a key derived from that password with Argon2id. Opening then needs both the
 * phone and the password; a wrong password fails the AES-GCM check without revealing anything.
 *
 * File layout: magic, mode, [salt and Argon2 costs], hardware-sealed blob.
 */
class Vault(
    private val file: File,
    private val hardware: KeyWrapper,
    private val kdf: KdfParams = KdfParams(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun exists(): Boolean = file.isFile

    /** Whether opening needs a password. */
    fun hasPassword(): Boolean = read().password != null

    /** Creates a new master key; a vault that already exists is never overwritten. */
    fun create(password: CharArray?): ByteArray {
        check(!exists()) { "vault already exists" }
        val key = ByteArray(KdfParams.KEY_BYTES).also(random::nextBytes)
        write(key, password)
        return key
    }

    /** @throws WrongPasswordException, VaultUnavailableException */
    fun open(password: CharArray?): ByteArray {
        val stored = read()
        val inner = try {
            hardware.unwrap(stored.blob)
        } catch (e: GeneralSecurityException) {
            throw VaultUnavailableException("the device key cannot open this vault", e)
        }
        val pw = stored.password ?: return inner
        if (password == null) throw WrongPasswordException()
        val derived = pw.params.derive(password, pw.salt)
        return try {
            aes(Cipher.DECRYPT_MODE, derived, inner)
        } catch (_: AEADBadTagException) {
            throw WrongPasswordException()
        } finally {
            derived.fill(0)
        }
    }

    /** Seals the same master key again, with a new password or with none. */
    fun changePassword(key: ByteArray, newPassword: CharArray?) = write(key, newPassword)

    private class PasswordPart(val salt: ByteArray, val params: KdfParams)

    private class Stored(val password: PasswordPart?, val blob: ByteArray)

    private fun write(key: ByteArray, password: CharArray?) {
        val w = ByteWriter().raw(MAGIC)
        val inner = if (password == null) {
            w.byte(MODE_DEVICE)
            key
        } else {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            w.byte(MODE_PASSWORD).blob(salt)
                .varint(kdf.memoryKiB.toLong()).varint(kdf.iterations.toLong()).varint(kdf.parallelism.toLong())
            val derived = kdf.derive(password, salt)
            try {
                aes(Cipher.ENCRYPT_MODE, derived, key)
            } finally {
                derived.fill(0)
            }
        }
        w.blob(hardware.wrap(inner))
        // Written to a temporary file and moved into place: a crash never leaves half a vault.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(w.toByteArray())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("cannot replace $file")
        }
    }

    private fun read(): Stored {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw VaultUnavailableException("no vault", e)
        }
        return try {
            val r = ByteReader(bytes)
            if (!r.raw(MAGIC.size).contentEquals(MAGIC)) throw VaultUnavailableException("not a vault")
            val password = when (r.byte()) {
                MODE_DEVICE -> null
                MODE_PASSWORD -> PasswordPart(
                    salt = r.blob(),
                    params = KdfParams(
                        memoryKiB = r.varint(8L..MAX_MEMORY_KIB).toInt(),
                        iterations = r.varint(1L..MAX_ITERATIONS).toInt(),
                        parallelism = r.varint(1L..MAX_PARALLELISM).toInt(),
                    ),
                )
                else -> throw VaultUnavailableException("unknown vault mode")
            }
            Stored(password, r.blob())
        } catch (e: MalformedException) {
            throw VaultUnavailableException("damaged vault", e)
        }
    }

    /** AES-256-GCM with a random nonce in front of the ciphertext. */
    private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = SecretKeySpec(key, "AES")
        return if (mode == Cipher.ENCRYPT_MODE) {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            cipher.init(mode, spec, GCMParameterSpec(TAG_BITS, iv))
            iv + cipher.doFinal(data)
        } else {
            if (data.size <= IV_BYTES) throw AEADBadTagException("too short")
            cipher.init(mode, spec, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        }
    }

    private companion object {
        val MAGIC = "SGV1".toByteArray(Charsets.US_ASCII)
        const val MODE_DEVICE = 1
        const val MODE_PASSWORD = 2
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_MEMORY_KIB = 1L shl 22
        const val MAX_ITERATIONS = 100L
        const val MAX_PARALLELISM = 16L
    }
}
