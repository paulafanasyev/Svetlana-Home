package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpCodecTest {
    private fun parse(raw: String) = HttpRequestParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun parsesPostWithJsonBody() {
        val body = """{"params":{"query":"Анна"}}"""
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val request = parse("POST /api/contacts/list HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: $bytes\r\n\r\n$body")
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/contacts/list")
        assertThat(request.headers["content-type"]).isEqualTo("application/json")
        assertThat(request.body).isEqualTo(body)
    }

    @Test
    fun parsesGetWithoutBody() {
        val request = parse("GET /health HTTP/1.1\r\nOrigin: http://tauri.localhost\r\n\r\n")
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.headers["origin"]).isEqualTo("http://tauri.localhost")
        assertThat(request.body).isEmpty()
    }

    @Test
    fun rejectsOversizedBody() {
        val e = assertThrows(HttpRequestParser.ParseException::class.java) {
            parse("POST /api/x HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n")
        }
        assertThat(e.status).isEqualTo(413)
    }

    @Test
    fun rejectsGarbage() {
        val e = assertThrows(HttpRequestParser.ParseException::class.java) { parse("hello\r\n\r\n") }
        assertThat(e.status).isEqualTo(400)
    }

    @Test
    fun rejectsTruncatedBody() {
        val e = assertThrows(HttpRequestParser.ParseException::class.java) {
            parse("POST /api/x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")
        }
        assertThat(e.status).isEqualTo(400)
    }

    @Test
    fun writesResponseWithLengthAndHeaders() {
        val out = ByteArrayOutputStream()
        HttpResponseWriter.write(out, HttpResponse(200, """{"ok":true}""", mapOf("Vary" to "Origin")))
        val text = out.toString(Charsets.UTF_8.name())
        assertThat(text).startsWith("HTTP/1.1 200 OK\r\n")
        assertThat(text).contains("Content-Length: 11\r\n")
        assertThat(text).contains("Content-Type: application/json; charset=utf-8\r\n")
        assertThat(text).contains("Vary: Origin\r\n")
        assertThat(text).endsWith("\r\n\r\n{\"ok\":true}")
    }
}
