package io.github.brunovinicioslg.sigilo

import io.github.brunovinicioslg.sigilo.crypto.SessionManager
import java.security.SecureRandom
import kotlin.random.Random
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/** A phone with its own identity and keys, as after installing the app. */
class Phone(val number: String) {
    val store = InMemorySignalProtocolStore(IdentityKeyPair.generate(), 1 + SecureRandom().nextInt(16_380))
    val sessions = SessionManager(store)
    val messenger = Messenger(sessions)
}

fun forEachCase(cases: Int, baseSeed: Long = 20260928L, block: (seed: Long, rnd: Random) -> Unit) {
    repeat(cases) { i -> block(baseSeed + i, Random(baseSeed + i)) }
}
