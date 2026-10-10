package com.svetlana.home.bridge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Tiny, bounded HTTP/1.1 request parser (one request per connection). */
object HttpRequestParser {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 256 * 1024

    private val SINGLE_HEADERS = setOf("content-length", "host", "authorization", "x-svetlana-token", "origin")

    class ParseException(val status: Int, message: String) : Exception(message)

    fun parse(input: InputStream): HttpRequest {
        val lines = readHead(input).split("\r\n")
        val requestLine = lines.first().split(" ")
        if (requestLine.size != 3 || requestLine[0].isEmpty() || !requestLine[1].startsWith("/")) {
            throw ParseException(400, "Bad request line")
        }
        if (requestLine[2] != "HTTP/1.1" && requestLine[2] != "HTTP/1.0") {
            throw ParseException(400, "Unsupported HTTP version")
        }
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw ParseException(400, "Bad header line")
            val name = line.substring(0, colon).trim().lowercase()
            if (name in SINGLE_HEADERS && headers.containsKey(name)) {
                throw ParseException(400, "Duplicate $name header")
            }
            headers[name] = line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw ParseException(400, "Transfer-Encoding is not supported")
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw ParseException(400, "Bad Content-Length") } ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw ParseException(413, "Body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw ParseException(400, "Truncated body")
            read += n
        }
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            headers = headers,
            body = String(body, Charsets.UTF_8),
            httpVersion = requestLine[2],
        )
    }

    private fun readHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var state = 0 // 0 none, 1 \r, 2 \r\n, 3 \r\n\r, 4 \r\n\r\n
        while (state != 4) {
            val b = input.read()
            if (b < 0) throw ParseException(400, "Connection closed before headers ended")
            buffer.write(b)
            if (buffer.size() > MAX_HEADER_BYTES) throw ParseException(431, "Headers too large")
            state = when {
                b == '\r'.code && state == 2 -> 3
                b == '\r'.code -> 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> 4
                else -> 0
            }
        }
        val bytes = buffer.toByteArray()
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }
}

object HttpResponseWriter {
    fun write(out: OutputStream, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Length" to body.size.toString(),
            "Connection" to "close",
            "Cache-Control" to "no-store",
        )
        if (body.isNotEmpty()) headers["Content-Type"] = "application/json; charset=utf-8"
        headers.putAll(response.headers)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }
}
