package com.dataproxy.proxy

/**
 * SOCKS5 username/password auth settings (RFC 1929).
 *
 * When [enabled] is false the server advertises only NO_AUTH (method 0x00).
 * When enabled, it advertises only USERNAME/PASSWORD (method 0x02) and
 * validates credentials against [username] / [password].
 */
data class AuthConfig(
    val enabled: Boolean,
    val username: String,
    val password: String,
) {
    companion object {
        val Disabled = AuthConfig(enabled = false, username = "", password = "")
    }
}
