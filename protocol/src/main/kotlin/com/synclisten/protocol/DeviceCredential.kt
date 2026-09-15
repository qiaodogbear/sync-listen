package com.synclisten.protocol

import java.security.MessageDigest

object DeviceCredential {
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // A server receives only its own scoped credential, never the installation secret.
    fun forServer(secret: String, origin: String, userId: String): String =
        hash("sync-listen-v1\n$origin\n$userId\n$secret")

    fun matches(storedHash: String?, credential: String): Boolean =
        !storedHash.isNullOrEmpty() && MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.US_ASCII), hash(credential).toByteArray(Charsets.US_ASCII),
        )
}
