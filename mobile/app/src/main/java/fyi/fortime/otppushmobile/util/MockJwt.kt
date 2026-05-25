package fyi.fortime.otppushmobile.util

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object MockJwt {
    fun createToken(userId: String, secret: String): String {
        val iat = System.currentTimeMillis() / 1000
        val exp = iat + 7 * 24 * 60 * 60 // 7 days

        val header = JSONObject().apply {
            put("alg", "HS256")
            put("typ", "JWT")
        }.toString()

        val payload = JSONObject().apply {
            put("sub", userId)
            put("iat", iat)
            put("exp", exp)
        }.toString()

        val headerEncoded = base64UrlEncode(header.toByteArray())
        val payloadEncoded = base64UrlEncode(payload.toByteArray())

        val data = "$headerEncoded.$payloadEncoded"
        val signature = hmacSha256(data, secret)
        val signatureEncoded = base64UrlEncode(signature)

        return "$data.$signatureEncoded"
    }

    private fun base64UrlEncode(input: ByteArray): String {
        return Base64.encodeToString(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hmacSha256(data: String, secret: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretBytes = secret.toByteArray()
        val secretKey = if (secretBytes.isEmpty()) {
            object : SecretKey {
                override fun getAlgorithm() = "HmacSHA256"
                override fun getFormat() = "RAW"
                override fun getEncoded() = secretBytes
            }
        } else {
            SecretKeySpec(secretBytes, "HmacSHA256")
        }
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray())
    }
}
