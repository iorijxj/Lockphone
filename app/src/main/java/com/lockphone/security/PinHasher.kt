package com.lockphone.security

import java.security.MessageDigest
import java.security.SecureRandom

object PinHasher {
    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, salt: ByteArray, expectedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(pin, salt).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
}
