package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.app
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * mov18plus.cloud: video được chia thành các phần 2MB, mỗi phần tải từ  https://<host>/sora/<size>/<token>
 * token = base64(base64(AES-CTR("/mp4/<md5_id>/<res_id>/<size>/<chunk>/<n>")) bỏ '=') bỏ '='
 * key AES = md5 (chuỗi hex ASCII) của CÁC CHỮ SỐ của <size> (mỗi chữ số là một byte 0..9), counter = 16 byte đầu của key.
 * Proxy này ghép các phần lại thành một file MP4 duy nhất có hỗ trợ Range để ExoPlayer tua được.
 */
object Mov18Proxy {
    class Entry(val domain: String, val mid: Long, val res: Int, val size: Long, val referer: String, val ua: String) {
        val chunk: Long = if (size > 2097152L) 2097152L else size
        val parts: Int get() = ((size + chunk - 1) / chunk).toInt()
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var logged = 0
    private val store = ConcurrentHashMap<String, Entry>()
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    private fun log(msg: String) {
        if (logged++ < 40) ClipboardLogger.logAndCopy("Mov18Proxy", msg)
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

    fun register(e: Entry): String {
        val id = java.lang.Long.toHexString(System.nanoTime())
        store[id] = e
        return "http://127.0.0.1:${port()}/m/$id.mp4"
    }

    private fun token(path: String, size: Long): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(size.toString().map { (it - '0').toByte() }.toByteArray())
        val k = digest.joinToString("") { b -> "%02x".format(b) }.toByteArray(Charsets.ISO_8859_1)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(k.copyOfRange(0, 16)))
        val enc = c.doFinal(path.toByteArray(Charsets.ISO_8859_1))
        val b1 = Base64.encodeToString(enc, Base64.NO_WRAP).replace("=", "")
        return Base64.encodeToString(b1.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP).replace("=", "")
    }

    fun partUrl(e: Entry, n: Int): String {
        val path = "/mp4/${e.mid}/${e.res}/${e.size}/${e.chunk}/$n"
        return "https://${e.domain}/sora/${e.size}/${token(path, e.size)}"
    }

    class PartResult(val code: Int, val ct: String, val bytes: ByteArray, val url: String)

    fun fetchPart(e: Entry, n: Int): PartResult {
        val url = partUrl(e, n)
        val origin = Regex("""^(https?://[^/]+)""").find(e.referer)?.groupValues?.get(1) ?: e.referer.trimEnd('/')
        val rb = okhttp3.Request.Builder().url(url)
            .header("User-Agent", e.ua).header("Referer", e.referer).header("Origin", origin)
        app.baseClient.newCall(rb.build()).execute().use { r ->
            return PartResult(r.code, r.header("Content-Type") ?: "", r.body?.bytes() ?: ByteArray(0), url)
        }
    }

    private fun expectedLen(e: Entry, n: Int): Long = if (n == e.parts - 1) e.size - n * e.chunk else e.chunk

    private fun part(id: String, e: Entry, n: Int): ByteArray {
        val key = "$id/$n"
        cache[key]?.let { return it }
        var last: PartResult? = null
        for (attempt in 1..3) {
            try {
                val r = fetchPart(e, n)
                last = r
                if ((r.code == 200 || r.code == 206) && r.bytes.size.toLong() == expectedLen(e, n)) {
                    if (cache.size > 6) cache.keys.take(cache.size - 4).forEach { cache.remove(it) }
                    cache[key] = r.bytes
                    return r.bytes
                }
            } catch (_: Throwable) {
            }
            Thread.sleep(300L * attempt)
        }
        log("phần $n lỗi: HTTP ${last?.code} len=${last?.bytes?.size} cần=${expectedLen(e, n)}")
        throw java.io.IOException("part $n")
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60_000
            val inp = BufferedReader(InputStreamReader(c.getInputStream()))
            val first = inp.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim()
            }
            val parts = first.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val raw = parts.getOrNull(1) ?: return
            val out = c.getOutputStream()
            val id = raw.removePrefix("/m/").substringBefore(".mp4")
            val e = store[id]
            if (e == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            var start = 0L
            var end = e.size - 1
            val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
            var partial = false
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), e.size - 1)
                } else if (b.isNotEmpty()) {
                    start = maxOf(0L, e.size - b.toLong())
                }
                partial = true
            }
            if (start > end || start >= e.size) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${e.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            val len = end - start + 1
            val head = StringBuilder()
            head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/${e.size}\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method.equals("HEAD", true)) { out.flush(); return }
            var pos = start
            var idx = (start / e.chunk).toInt()
            fun prefetch(i: Int): java.util.concurrent.Future<ByteArray>? =
                if (i < e.parts) pool.submit(java.util.concurrent.Callable<ByteArray> { part(id, e, i) }) else null
            var next = prefetch(idx + 1)
            var cur = part(id, e, idx)
            while (pos <= end) {
                val partStart = idx * e.chunk
                val from = (pos - partStart).toInt()
                val to = (minOf(end, partStart + cur.size - 1) - partStart).toInt()
                out.write(cur, from, to - from + 1)
                pos += (to - from + 1)
                if (pos > end) break
                idx++
                cur = next?.get() ?: part(id, e, idx)
                next = prefetch(idx + 1)
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }
}
