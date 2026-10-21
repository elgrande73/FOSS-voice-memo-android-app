package io.github.elgrande73.voicememo

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 file server for download tests (GET only, one connection at a time, optional Range support and a
 * simulated dropped connection). Serves [body] for every path.
 */
class TestHttpServer(var body: () -> ByteArray) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    /** Range header of each request (null if none). */
    val ranges = mutableListOf<String?>()
    var honorRange = true
    /** Close the connection after this many body bytes of a full (non-range) response. */
    var cutAfter: Int? = null

    init {
        thread(isDaemon = true, name = "TestHttpServer") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                try { handle(client) } catch (_: Exception) { } finally { client.close() }
            }
        }
    }

    private fun readHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else {
                line.append(c.toChar())
            }
        }
        return lines
    }

    private fun handle(client: Socket) {
        val headers = readHeaders(client.getInputStream())
        val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(":")?.trim()
        synchronized(ranges) { ranges += range }
        val data = body()
        val start = if (honorRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        val out = client.getOutputStream()
        if (start >= data.size) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val status = if (start > 0) "206 Partial Content" else "200 OK"
        val length = data.size - start
        out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
        val limit = cutAfter
        if (limit != null && start == 0) {
            out.write(data, 0, limit)
            out.flush()
            return // connection dropped before the end
        }
        out.write(data, start, length)
        out.flush()
    }

    override fun close() = socket.close()
}
