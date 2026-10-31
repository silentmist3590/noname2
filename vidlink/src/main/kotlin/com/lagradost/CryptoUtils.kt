// CryptoUtils.kt
package com.example.vidlink

import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

object CryptoUtils {
    private const val KEY_HEX = "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"

    fun encryptToken(mediaId: String): String {
        val key = hexStringToByteArray(KEY_HEX)
        val nonce = ByteArray(24)

        val timestamp = System.currentTimeMillis() / 1000L + 480
        val message = ByteArrayOutputStream().apply {
            write(mediaId.toByteArray(Charsets.UTF_8))
            write(ByteBuffer.allocate(8).putLong(timestamp).array())
        }.toByteArray()

        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val ciphertext = ByteArray(message.size)
        engine.processBytes(message, 0, message.size, ciphertext, 0)

        val polyKey = ByteArray(32)
        val keystreamEngine = XSalsa20Engine()
        keystreamEngine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        keystreamEngine.processBytes(ByteArray(32), 0, 32, polyKey, 0)

        val poly1305 = Poly1305()
        poly1305.init(KeyParameter(polyKey))
        poly1305.update(ciphertext, 0, ciphertext.size)
        val mac = ByteArray(16)
        poly1305.doFinal(mac, 0)

        val fullPayload = nonce + mac + ciphertext
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fullPayload)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
