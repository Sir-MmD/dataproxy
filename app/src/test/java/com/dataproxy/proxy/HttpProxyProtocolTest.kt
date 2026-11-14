package com.dataproxy.proxy

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class HttpProxyProtocolTest {
    private fun parse(line: String, headers: String = "") =
        HttpProxyProtocol.parse(line + "\r\n" + headers + "\r\n")

    @Test fun connectAuthority() {
        val req = parse("CONNECT example.org:443 HTTP/1.1")
        assertTrue(req.isConnect)
        assertEquals("example.org", req.target.host)
        assertEquals(443, req.target.port)
        assertEquals("2001:db8::1", HttpProxyProtocol.parseAuthority("[2001:db8::1]:443", true).host)
        assertEquals("127.0.0.1", HttpProxyProtocol.parseAuthority("127.0.0.1:80", true).host)
        assertBad(400) { HttpProxyProtocol.parseAuthority("2001:db8::1:443", true) }
    }

    @Test fun normalizeAbsoluteRequest() {
        val req = parse("GET http://example.org:8081/a?q=1 HTTP/1.1",
            "Proxy-Authorization: Basic secret\r\nProxy-Connection: keep-alive\r\nX-Test: yes\r\n")
        assertEquals("/a?q=1", req.path)
        val forwarded = String(HttpProxyProtocol.forwardHeader(req), Charsets.ISO_8859_1)
        assertTrue(forwarded.startsWith("GET /a?q=1 HTTP/1.1\r\n"))
        assertTrue(forwarded.contains("Host: example.org:8081\r\n"))
        assertTrue(forwarded.contains("X-Test: yes\r\n"))
        assertTrue(forwarded.endsWith("Connection: close\r\n\r\n"))
        assertFalse(forwarded.contains("Proxy-Authorization"))
        assertFalse(forwarded.contains("Proxy-Connection"))
    }

    @Test fun rejectsHttpsAbsoluteForm() {
        assertBad(400) { parse("GET https://example.org/ HTTP/1.1") }
    }

    @Test fun rejectsBadAuthorityAndHeaders() {
        assertBad(400) { parse("CONNECT example.org:99999 HTTP/1.1") }
        assertBad(400) { parse("CONNECT example.org HTTP/1.1") }
        assertBad(400) { parse("GET http://example.org/ HTTP/1.1", "WrongHeader\r\n") }
        assertBad(400) { parse("POST http://example.org/ HTTP/1.1",
            "Content-Length: 3\r\nTransfer-Encoding: chunked\r\n") }
    }

    @Test fun enforcesHeaderSize() {
        assertBad(431) {
            parse("GET http://example.org/ HTTP/1.1", "X-Test: " + "a".repeat(25000) + "\r\n")
        }
    }

    @Test fun basicProxyAuthentication() {
        val cfg = AuthConfig(enabled = true, username = "user", password = "pw")
        val basic = Base64.getEncoder().encodeToString("user:pw".toByteArray())
        val yes = listOf("Proxy-Authorization" to ("Basic " + basic))
        assertTrue(HttpProxyProtocol.authenticated(yes, cfg))
        assertFalse(HttpProxyProtocol.authenticated(emptyList(), cfg))
        assertFalse(HttpProxyProtocol.authenticated(listOf("Proxy-Authorization" to "Basic !!!!"), cfg))
        assertFalse(HttpProxyProtocol.authenticated(
            listOf("Proxy-Authorization" to "Basic " +
                Base64.getEncoder().encodeToString("user:bad".toByteArray())), cfg))
        assertTrue(HttpProxyProtocol.authenticated(emptyList(), AuthConfig.Disabled))
    }

    @Test fun validatesTransferEncoding() {
        val req = parse("POST http://example.org/upload HTTP/1.1", "Transfer-Encoding: chunked\r\n")
        assertTrue(req.chunked)
        assertNull(req.contentLength)
        assertTrue(String(HttpProxyProtocol.forwardHeader(req)).contains("Transfer-Encoding: chunked"))
    }

    private fun assertBad(status: Int, block: () -> Unit) {
        try {
            block()
            fail("Expected HTTP " + status)
        } catch (e: HttpProxyProtocol.BadRequest) {
            assertEquals(status, e.status)
        }
    }
}
