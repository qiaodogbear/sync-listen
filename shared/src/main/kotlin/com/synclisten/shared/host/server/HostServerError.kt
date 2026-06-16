package com.synclisten.shared.host.server

class HostServerError(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message)
