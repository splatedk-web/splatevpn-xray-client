package com.danieldk.splatevpn.data

data class ServerEndpointValidation(val isAddressValid: Boolean, val port: Int?) {
    val isValid: Boolean get() = isAddressValid && port != null
}

fun validateServerEndpoint(address: String, port: String): ServerEndpointValidation =
    ServerEndpointValidation(
        isAddressValid = address.trim().let { it.isNotEmpty() && it.none(Char::isWhitespace) && "://" !in it && it.none { char -> char in "/?#@" } },
        port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    )
