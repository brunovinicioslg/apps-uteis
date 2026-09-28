package io.github.brunovinicioslg.sigilo.peer

import io.github.brunovinicioslg.sigilo.Messenger
import io.github.brunovinicioslg.sigilo.Received
import io.github.brunovinicioslg.sigilo.crypto.SessionManager
import io.github.brunovinicioslg.sigilo.wire.Content
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.system.exitProcess
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
 * Usage (state kept in --state, a folder):
 *   invite  --state dir --to +55...                 prints the invite SMS, one per line
 *   send    --state dir --to +55... --text "..."    prints the SMS of an encrypted message
 *   ack     --state dir --to +55...                 prints a confirmation (also completes a handshake)
 *   receive --state dir --from +55... --file f      reads SMS texts (one per line) and prints what arrived
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = args.drop(1).chunked(2).filter { it.size == 2 }.associate { (k, v) -> k.removePrefix("--") to v }
    val dir = File(options["state"] ?: usage())
    dir.mkdirs()
    val messenger = Messenger(SessionManager(FileStore(dir)))
    val now = System.currentTimeMillis()
    when (command) {
        "invite" -> messenger.composeInvite().forEach(::println)
        "send" -> messenger.composeText(options["to"] ?: usage(), options["text"] ?: usage(), 0, now).forEach(::println)
        "ack" -> messenger.composeAck(options["to"] ?: usage(), now).forEach(::println)
        "receive" -> {
            val from = options["from"] ?: usage()
            for (line in File(options["file"] ?: usage()).readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                when (val received = messenger.receive(from, line, now) ?: continue) {
                    is Received.Message -> when (val content = received.content) {
                        is Content.Text -> println("TEXT ${content.body}")
                        is Content.TimerChange -> println("TIMER ${content.expireSeconds}")
                        is Content.Ack -> println("ACK${if (received.acknowledgeSetup) " setup" else ""}")
                        is Content.Unknown -> println("UNKNOWN ${content.type}")
                    }
                    Received.InviteAccepted -> println("INVITE accepted")
                    is Received.InviteWithNewIdentity -> println("INVITE new-identity")
                    is Received.IdentityChanged -> println("IDENTITY changed")
                    Received.Duplicate -> println("DUPLICATE")
                    is Received.Failed -> println("FAILED ${received.reason}")
                }
            }
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: invite|send|ack|receive --state dir [--to|--from number] [--text t] [--file f]")
    exitProcess(2)
}

/** The Signal protocol state in plain files: fine for a test tool, never for real use. */
private class FileStore(private val dir: File) : SignalProtocolStore {
    private fun file(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun read(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()
    private fun write(name: String, bytes: ByteArray) = file(name).writeBytes(bytes)

    private val identity: IdentityKeyPair = read("identity")?.let(::IdentityKeyPair) ?: IdentityKeyPair.generate().also { write("identity", it.serialize()) }
    private val registrationId: Int = read("registration")?.decodeToString()?.toInt()
        ?: (1 + SecureRandom().nextInt(16_380)).also { write("registration", it.toString().encodeToByteArray()) }

    override fun getIdentityKeyPair() = identity
    override fun getLocalRegistrationId() = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val old = getIdentity(address)
        write("id-${address.name}", identityKey.serialize())
        return if (old != null && old != identityKey) IdentityKeyStore.IdentityChange.REPLACED_EXISTING else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction) =
        getIdentity(address)?.let { it == identityKey } ?: true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = read("id-${address.name}")?.let(::IdentityKey)

    override fun loadPreKey(preKeyId: Int) = read("prekey-$preKeyId")?.let(::PreKeyRecord) ?: throw InvalidKeyIdException("no prekey $preKeyId")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = write("prekey-$preKeyId", record.serialize())
    override fun containsPreKey(preKeyId: Int) = file("prekey-$preKeyId").isFile
    override fun removePreKey(preKeyId: Int) {
        file("prekey-$preKeyId").delete()
    }

    override fun loadSignedPreKey(signedPreKeyId: Int) = read("signed-$signedPreKeyId")?.let(::SignedPreKeyRecord) ?: throw InvalidKeyIdException("no signed $signedPreKeyId")
    override fun loadSignedPreKeys() = dir.listFiles { f -> f.name.startsWith("signed-") }.orEmpty().map { SignedPreKeyRecord(it.readBytes()) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = write("signed-$signedPreKeyId", record.serialize())
    override fun containsSignedPreKey(signedPreKeyId: Int) = file("signed-$signedPreKeyId").isFile
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        file("signed-$signedPreKeyId").delete()
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int) = read("kyber-$kyberPreKeyId")?.let(::KyberPreKeyRecord) ?: throw InvalidKeyIdException("no kyber $kyberPreKeyId")
    override fun loadKyberPreKeys() = dir.listFiles { f -> f.name.startsWith("kyber-") }.orEmpty().map { KyberPreKeyRecord(it.readBytes()) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = write("kyber-$kyberPreKeyId", record.serialize())
    override fun containsKyberPreKey(kyberPreKeyId: Int) = file("kyber-$kyberPreKeyId").isFile
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val used = file("kyberused-$kyberPreKeyId-$signedPreKeyId-${baseKey.serialize().contentHashCode()}")
        if (used.isFile) throw ReusedBaseKeyException("reused")
        used.writeText("1")
    }

    override fun loadSession(address: SignalProtocolAddress) = read("session-${address.name}-${address.deviceId}")?.let(::SessionRecord) ?: SessionRecord()
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>) = addresses.map { a ->
        read("session-${a.name}-${a.deviceId}")?.let(::SessionRecord) ?: throw NoSessionException("no session $a")
    }
    override fun getSubDeviceSessions(name: String) = emptyList<Int>()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = write("session-${address.name}-${address.deviceId}", record.serialize())
    override fun containsSession(address: SignalProtocolAddress) = read("session-${address.name}-${address.deviceId}")?.let { SessionRecord(it).hasSenderChain() } ?: false
    override fun deleteSession(address: SignalProtocolAddress) {
        file("session-${address.name}-${address.deviceId}").delete()
    }
    override fun deleteAllSessions(name: String) {
        dir.listFiles { f -> f.name.startsWith(file("session-$name-").name) }.orEmpty().forEach { it.delete() }
    }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) = Unit
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null
}
