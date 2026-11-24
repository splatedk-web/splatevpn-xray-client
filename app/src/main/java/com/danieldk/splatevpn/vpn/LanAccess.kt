package com.danieldk.splatevpn.vpn

data class LanAccess(val bindAddress: String, val username: String, val password: String, val authentication: Boolean = true) {
    init {
        val octets = bindAddress.split('.').map { it.toIntOrNull() ?: -1 }
        require(octets.size == 4 && octets.all { it in 0..255 } &&
            octets.first() in 1..223 && octets.first() != 127 &&
            !(octets[0] == 169 && octets[1] == 254)) { "Choose an active LAN IPv4 address" }
        require(!authentication || username.length in 1..64 && password.length in 12..128) { "Invalid LAN credentials" }
    }
}
