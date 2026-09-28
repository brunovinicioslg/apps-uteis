package io.github.brunovinicioslg.sigilo.app.vault

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The device's secure hardware, simulated with a key that lives only in this object. */
class SoftwareKeyWrapper : KeyWrapper {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

class VaultTest {

    @get:Rule val folder = TemporaryFolder()

    // Small costs keep the tests fast; the real app uses the defaults.
    private val fast = KdfParams(memoryKiB = 256, iterations = 1)
    private val hardware = SoftwareKeyWrapper()

    private fun vault(file: File = File(folder.root, "vault.bin"), wrapper: KeyWrapper = hardware, kdf: KdfParams = fast) = Vault(file, wrapper, kdf)

    @Test
    fun withoutPasswordTheDeviceAloneOpensIt() {
        val key = vault().create(null)
        assertThat(key).hasLength(32)
        assertThat(vault().hasPassword()).isFalse()
        assertThat(vault().open(null)).isEqualTo(key)
    }

    @Test
    fun withPasswordBothAreNeeded() {
        val key = vault().create("correta horse".toCharArray())
        assertThat(vault().hasPassword()).isTrue()
        assertThat(vault().open("correta horse".toCharArray())).isEqualTo(key)
        assertThrows(WrongPasswordException::class.java) { vault().open("errada".toCharArray()) }
        assertThrows(WrongPasswordException::class.java) { vault().open(null) }
        // The file alone, on another device, opens nothing, even with the right password.
        assertThrows(VaultUnavailableException::class.java) { vault(wrapper = SoftwareKeyWrapper()).open("correta horse".toCharArray()) }
    }

    @Test
    fun theKeyNeverAppearsInTheFile() {
        val key = vault().create("1234".toCharArray())
        val bytes = File(folder.root, "vault.bin").readBytes()
        assertThat(bytes.toList().windowed(8).none { it == key.take(8) }).isTrue()
    }

    @Test
    fun changingThePasswordKeepsTheSameKey() {
        val key = vault().create("1234".toCharArray())
        vault().changePassword(key, "5678".toCharArray())
        assertThat(vault().open("5678".toCharArray())).isEqualTo(key)
        assertThrows(WrongPasswordException::class.java) { vault().open("1234".toCharArray()) }
        vault().changePassword(key, null)
        assertThat(vault().open(null)).isEqualTo(key)
        assertThat(File(folder.root, "vault.bin.tmp").exists()).isFalse()
    }

    @Test
    fun costsAreReadFromTheFile() {
        val key = vault(kdf = KdfParams(memoryKiB = 512, iterations = 2)).create("senha".toCharArray())
        // A newer app version with other defaults still opens the old vault.
        assertThat(vault(kdf = fast).open("senha".toCharArray())).isEqualTo(key)
    }

    @Test
    fun damagedOrMissingFilesAreReported() {
        assertThrows(VaultUnavailableException::class.java) { vault().open(null) }
        val file = File(folder.root, "vault.bin")
        vault().create(null)
        file.writeBytes(file.readBytes().copyOf(10))
        assertThrows(VaultUnavailableException::class.java) { vault().open(null) }
        file.writeText("não é um cofre")
        assertThrows(VaultUnavailableException::class.java) { vault().open(null) }
    }

    @Test
    fun anExistingVaultIsNeverOverwritten() {
        vault().create(null)
        assertThrows(IllegalStateException::class.java) { vault().create(null) }
    }

    @Test
    fun argon2idIsTheStandardOne() {
        // RFC 9106, section 5.3: the official Argon2id test vector, through the same library.
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(32).withIterations(3).withParallelism(4)
            .withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 }).withAdditional(ByteArray(12) { 4 })
            .build()
        val tag = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(ByteArray(32) { 1 }, tag)
        assertThat(tag.joinToString("") { "%02x".format(it) }).isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")
    }

    @Test
    fun derivedKeysDependOnPasswordAndSalt() {
        val kdf = KdfParams(memoryKiB = 64, iterations = 2)
        val salt = "somesaltsomesalt".toByteArray()
        val out = kdf.derive("password".toCharArray(), salt)
        assertThat(out).hasLength(32)
        assertThat(kdf.derive("password".toCharArray(), salt)).isEqualTo(out)
        assertThat(kdf.derive("passworD".toCharArray(), salt)).isNotEqualTo(out)
        assertThat(kdf.derive("password".toCharArray(), "othersaltothersa".toByteArray())).isNotEqualTo(out)
    }
}

class UnlockGuardTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun pausesGrowAfterFiveWrongPasswords() {
        val file = File(folder.root, "attempts")
        val guard = UnlockGuard(file)
        repeat(4) { guard.recordFailure(1_000) }
        assertThat(guard.waitMillis(1_000)).isEqualTo(0)
        guard.recordFailure(1_000)
        assertThat(guard.waitMillis(1_000)).isEqualTo(30_000)
        assertThat(guard.waitMillis(31_000)).isEqualTo(0)
        guard.recordFailure(31_000)
        assertThat(guard.waitMillis(31_000)).isEqualTo(60_000)
        // The clock moved backwards: still waiting the whole pause.
        assertThat(guard.waitMillis(0)).isEqualTo(60_000)
        repeat(20) { guard.recordFailure(100_000) }
        assertThat(guard.waitMillis(100_000)).isEqualTo(UnlockGuard.MAX_PAUSE_MS)

        // Survives restarts; a success clears it.
        assertThat(UnlockGuard(file).failedAttempts).isEqualTo(26)
        UnlockGuard(file).recordSuccess()
        assertThat(UnlockGuard(file).waitMillis(100_000)).isEqualTo(0)
    }

    @Test
    fun aDamagedCounterStartsPaused() {
        val file = File(folder.root, "attempts").apply { writeText("lixo") }
        assertThat(UnlockGuard(file) { 5_000_000 }.waitMillis(5_000_000)).isEqualTo(UnlockGuard.FIRST_PAUSE_MS)
    }
}
