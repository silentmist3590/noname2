package com.lagradost

import com.lagradost.cloudstream3.app
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Proxy cục bộ 127.0.0.1 cho nguồn trả playlist HLS dưới dạng VĂN BẢN (loadvid resolve-token):
 *  - /inline/<id>.m3u8 : trả playlist đã lưu, viết lại URL phân đoạn để đi qua proxy.
 *  - /seg?u=.. : tải phân đoạn bằng OkHttp của CloudStream với Referer/UA đúng, cắt phần đầu ngụy trang (PNG...) để còn MPEG-TS (byte 0x47).
 *  - /p.m3u8?u=.. : playlist con.
 */
object PlaylistProxy {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var logged = 0
    private val store = ConcurrentHashMap<String, Triple<String, String, String>>() // id -> (text, base, referer)
    @Volatile private var ua: String = ""
    @Volatile private var referer: String = ""

    private fun log(msg: String) {
        if (logged++ < 40) ClipboardLogger.logAndCopy("ProxyReq", msg)
    }

    private fun port(): Int = synchronized(this) {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                } catch (_: Throwable) {
                }
            }
        }.apply { isDaemon = true }.start()
        s.localPort
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun register(text: String, base: String, ref: String, userAgent: String): String {
        ua = userAgent
        referer = ref
        val id = java.lang.Long.toHexString(System.nanoTime())
        store[id] = Triple(text, base, ref)
        return "http://127.0.0.1:${port()}/inline/$id.m3u8"
    }

    private fun wrap(url: String): String =
        "http://127.0.0.1:${port()}/" + (if (url.contains(".m3u8", true)) "p.m3u8" else "seg") + "?u=" + enc(url)

    private fun abs(base: String, u: String): String = try { URI(base).resolve(u).toString() } catch (_: Throwable) { u }

    private fun rewrite(text: String, base: String): String =
        text.lines().joinToString("\n") { l ->
            when {
                l.isBlank() -> l
                l.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(l) { m -> "URI=\"" + wrap(abs(base, m.groupValues[1])) + "\"" }
                else -> wrap(abs(base, l.trim()))
            }
        }

    private fun tsOffset(b: ByteArray): Int {
        if (b.isNotEmpty() && b[0] == 0x47.toByte()) return 0
        val lim = minOf(b.size - 376, 8192)
        for (i in 0 until lim) {
            if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) return i
        }
        return -1
    }

    private class R(val code: Int, val ct: String, val url: String, val bytes: ByteArray)

    private fun fetch(url: String): R {
        val rb = okhttp3.Request.Builder().url(url)
        rb.header("User-Agent", ua)
        rb.header("Referer", referer)
        app.baseClient.newCall(rb.build()).execute().use { r ->
            return R(r.code, r.header("Content-Type") ?: "application/octet-stream", r.request.url.toString(), r.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun params(raw: String): Map<String, String> =
        raw.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60_000
            val inp = BufferedReader(InputStreamReader(c.getInputStream()))
            val first = inp.readLine() ?: return
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
            }
            val raw = first.split(" ").getOrNull(1) ?: return
            val out = c.getOutputStream()
            if (raw.startsWith("/inline/")) {
                val id = raw.removePrefix("/inline/").substringBefore(".m3u8")
                val e = store[id]
                if (e == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                }
                val body = rewrite(e.first, e.second).toByteArray()
                log("inline playlist ${body.size}B")
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body); out.flush(); return
            }
            val target = params(raw)["u"] ?: run {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            val r = fetch(target)
            log("${if (raw.startsWith("/p.m3u8")) "pl" else "seg"}: code=${r.code} ct=${r.ct} len=${r.bytes.size} url=${target.take(90)}")
            if (r.code >= 400) {
                out.write("HTTP/1.1 ${r.code} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            if (raw.startsWith("/p.m3u8")) {
                val txt = String(r.bytes, Charsets.UTF_8)
                val body = if (txt.trimStart().startsWith("#EXTM3U")) rewrite(txt, r.url).toByteArray() else r.bytes
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body); out.flush(); return
            }
            val bytes = r.bytes
            val off = tsOffset(bytes)
            val isMp4 = bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            val data = if (off > 0) bytes.copyOfRange(off, bytes.size) else bytes
            val type = if (off >= 0 && !isMp4) "video/mp2t" else r.ct
            if (off != 0) log("seg strip off=$off size=${bytes.size} -> ${data.size} type=$type")
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(data); out.flush()
        } catch (e: Throwable) {
            log("ERR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }
}
