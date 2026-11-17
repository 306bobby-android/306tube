package tube.three06.innertube.internal

import java.security.MessageDigest

internal const val YOUTUBE_ORIGIN = "https://www.youtube.com"

internal fun parseCookies(cookie: String?): Map<String, String> =
    (cookie ?: "").split(';').mapNotNull {
        val eq = it.indexOf('=')
        if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
    }.toMap()

internal fun sapisidHash(
    cookie: String,
    origin: String = YOUTUBE_ORIGIN,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): String? {
    val cookies = parseCookies(cookie)
    val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"] ?: return null
    return "SAPISIDHASH ${nowSeconds}_${sha1Hex("$nowSeconds $sapisid $origin")}"
}

internal fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

internal fun generateCpn(): String = buildString {
    repeat(16) { append(CPN_ALPHABET[(Math.random() * 64).toInt() and 63]) }
}
