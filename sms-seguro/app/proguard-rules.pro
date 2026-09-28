# libsignal's native (Rust) code calls back into Java by class and method name through JNI, so
# R8 must neither rename nor remove any of it. Without this, release builds fail at the first
# session setup with "JNI error Method not found: loadSession" (found testing the release APK).
-keep class org.signal.libsignal.** { *; }
-keep interface org.signal.libsignal.** { *; }

# The app's own implementations of libsignal's store interfaces are called the same way.
-keep class * implements org.signal.libsignal.protocol.state.SignalProtocolStore { *; }
-keep class * implements org.signal.libsignal.protocol.state.IdentityKeyStore { *; }
-keep class * implements org.signal.libsignal.protocol.state.PreKeyStore { *; }
-keep class * implements org.signal.libsignal.protocol.state.SignedPreKeyStore { *; }
-keep class * implements org.signal.libsignal.protocol.state.KyberPreKeyStore { *; }
-keep class * implements org.signal.libsignal.protocol.state.SessionStore { *; }
-keep class * implements org.signal.libsignal.protocol.groups.state.SenderKeyStore { *; }
