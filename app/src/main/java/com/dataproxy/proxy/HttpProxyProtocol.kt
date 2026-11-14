package com.dataproxy.proxy

import java.net.URI
import java.util.Base64
import java.security.MessageDigest

/** Pure JVM HTTP/1.x proxy handshake parser and request normalizer. */
internal object HttpProxyProtocol {
    const val MAX_HEADER_BYTES = 24 * 1024
    const val MAX_REQUEST_LINE_BYTES = 4096

    data class Target(val host: String, val port: Int) {
        fun label(): String = (if (host.contains(':')) "[" + host + "]" else host) + ":" + port
    }

    data class Request(
        val method: String,
        val version: String,
        val target: Target,
        val path: String?,
        val headers: List<Pair<String, String>>,
        val contentLength: Long?,
        val chunked: Boolean,
        val expectContinue: Boolean,
    ) {
        val isConnect: Boolean get() = method.equals("CONNECT", ignoreCase = true)
    }

    class BadRequest(val status: Int, val reason: String) : Exception(reason)

    fun parse(raw: String): Request {
        if (raw.toByteArray(Charsets.ISO_8859_1).size > MAX_HEADER_BYTES)
            throw BadRequest(431, "Request Header Fields Too Large")
        if (!raw.endsWith("\r\n\r\n")) throw BadRequest(400, "Bad Request")
        val lines = raw.dropLast(4).split("\r\n")
        if (lines.isEmpty() || lines[0].length > MAX_REQUEST_LINE_BYTES)
            throw BadRequest(400, "Bad Request")
        val first = lines[0].split(' ')
        if (first.size != 3 || !first[0].matches(Regex("[!#$%&'*+.^_~0-9A-Za-z|-]+")) ||
            (first[2] != "HTTP/1.0" && first[2] != "HTTP/1.1"))
            throw BadRequest(400, "Bad Request")
        val headers = lines.drop(1).map { line ->
            if (line.isEmpty() || line[0].isWhitespace()) throw BadRequest(400, "Bad Request")
            val i = line.indexOf(':')
            if (i <= 0 || !line.substring(0, i).matches(Regex("[!#$%&'*+.^_~0-9A-Za-z|-]+")))
                throw BadRequest(400, "Bad Request")
            val value = line.substring(i + 1).trim()
            if (value.any { it.code < 32 && it != '\t' || it.code == 127 })
                throw BadRequest(400, "Bad Request")
            line.substring(0, i) to value
        }
        fun header(name: String) = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
        val contentLengths = header("Content-Length")
        val contentLength = if (contentLengths.isEmpty()) null else {
            if (contentLengths.distinct().size != 1 || !contentLengths[0].matches(Regex("[0-9]+")))
                throw BadRequest(400, "Bad Request")
            contentLengths[0].toLongOrNull() ?: throw BadRequest(400, "Bad Request")
        }
        val encodings = header("Transfer-Encoding")
        val chunked = encodings.isNotEmpty()
        if (chunked && (contentLength != null || encodings.size != 1 ||
                !encodings[0].equals("chunked", ignoreCase = true)))
            throw BadRequest(400, "Bad Request")
        if (header("Upgrade").isNotEmpty()) throw BadRequest(501, "Not Implemented")

        val method = first[0]
        val isConnect = method.equals("CONNECT", ignoreCase = true)
        if (isConnect && (contentLength != null || chunked))
            throw BadRequest(400, "Bad Request")
        val target: Target
        val path: String?
        if (isConnect) {
            target = parseAuthority(first[1], requiredPort = true)
            path = null
        } else {
            val uri = try { URI(first[1]) } catch (_: Exception) {
                throw BadRequest(400, "Bad Request")
            }
            if (!uri.scheme.equals("http", ignoreCase = true))
                throw BadRequest(400, "Bad Request")
            if (uri.rawUserInfo != null || uri.rawFragment != null || uri.host == null)
                throw BadRequest(400, "Bad Request")
            val p = if (uri.port == -1) 80 else uri.port
            if (p !in 1..65535) throw BadRequest(400, "Bad Request")
            target = Target(uri.host.removeSurrounding("[", "]"), p)
            path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                (uri.rawQuery?.let { "?" + it } ?: "")
        }
        return Request(method, first[2], target, path, headers, contentLength, chunked,
            header("Expect").any { it.equals("100-continue", ignoreCase = true) })
    }

    fun parseAuthority(authority: String, requiredPort: Boolean): Target {
        if (authority.isBlank() || authority.any { it <= ' ' || it == '@' || it == '%' || it == '/' || it == '?' || it == '#' })
            throw BadRequest(400, "Bad Request")
        val colon = authority.lastIndexOf(':')
        if (colon <= 0 || colon == authority.lastIndex)
            throw BadRequest(400, "Bad Request")
        val rawHost = authority.substring(0, colon)
        val host = if (rawHost.startsWith("[")) {
            // CONNECT IPv6 literals must use [IPv6]:port (RFC 3986).
            if (!rawHost.endsWith("]") || rawHost.length < 4 ||
                rawHost.substring(1, rawHost.length - 1).contains('[') ||
                !rawHost.contains(':'))
                throw BadRequest(400, "Bad Request")
            rawHost.substring(1, rawHost.length - 1)
        } else {
            // A single colon separates host and port for DNS names and IPv4.
            // Unbracketed IPv6 is ambiguous and must be rejected.
            if (rawHost.any { it == ':' || it == '[' || it == ']' })
                throw BadRequest(400, "Bad Request")
            rawHost
        }
        val port = authority.substring(colon + 1).toIntOrNull()
            ?: throw BadRequest(400, "Bad Request")
        if (port !in 1..65535 || host.isBlank() || host.contains('[') || host.contains(']'))
            throw BadRequest(400, "Bad Request")
        return Target(host, port)
    }

    fun authenticated(headers: List<Pair<String, String>>, auth: AuthConfig): Boolean {
        if (!auth.enabled) return true
        if (auth.username.isEmpty() || auth.password.isEmpty()) return false
        val values = headers.filter { it.first.equals("Proxy-Authorization", ignoreCase = true) }
        if (values.size != 1) return false
        val parts = values[0].second.split(' ', limit = 2)
        if (parts.size != 2 || !parts[0].equals("Basic", ignoreCase = true)) return false
        val actual = runCatching { Base64.getDecoder().decode(parts[1].trim()) }.getOrNull()
            ?: return false
        val expected = (auth.username + ":" + auth.password).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(actual, expected)
    }

    fun forwardHeader(request: Request): ByteArray {
        require(!request.isConnect)
        val connectionTokens = request.headers
            .filter { it.first.equals("Connection", ignoreCase = true) }
            .flatMap { it.second.split(',') }
            .map { it.trim().lowercase() }.toSet()
        val exclude = connectionTokens + setOf(
            "host", "connection", "proxy-connection", "proxy-authorization",
            "proxy-authenticate", "keep-alive", "te", "trailer", "upgrade", "expect"
        )
        val out = StringBuilder()
            .append(request.method).append(' ').append(request.path)
            .append(' ').append(request.version).append("\r\n")
            .append("Host: ").append(
                if (request.target.host.contains(':')) "[" + request.target.host + "]"
                else request.target.host
            )
        if (request.target.port != 80) out.append(':').append(request.target.port)
        out.append("\r\n")
        for ((key, value) in request.headers) {
            if (key.lowercase() in setOf("content-length", "transfer-encoding") ||
                key.lowercase() !in exclude) {
                out.append(key).append(": ").append(value).append("\r\n")
            }
        }
        out.append("Connection: close\r\n\r\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
