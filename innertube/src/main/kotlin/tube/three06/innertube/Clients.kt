package tube.three06.innertube

import java.util.concurrent.atomic.AtomicReference

data class ClientInfo(
    val kind: ClientKind,
    val clientName: String,
    val clientVersion: String,
    val clientId: Int,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val platform: String? = null,
    val supportsCookies: Boolean = true,
    val wantsSignatureTimestamp: Boolean = false,
)

object Clients {
    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val WEB_VERSION_FALLBACK = "2.20261105.05.01"

    private val webVersion = AtomicReference(WEB_VERSION_FALLBACK)

    fun updateWebVersion(version: String) {
        if (Regex("""2\.\d{8}\.\d{2}\.\d{2}""").matches(version)) webVersion.set(version)
    }

    val WEB: ClientInfo
        get() = ClientInfo(
            kind = ClientKind.WEB,
            clientName = "WEB",
            clientVersion = webVersion.get(),
            clientId = 1,
            userAgent = WEB_USER_AGENT,
            platform = "DESKTOP",
            wantsSignatureTimestamp = true,
        )

    val WEB_EMBEDDED: ClientInfo
        get() = ClientInfo(
            kind = ClientKind.WEB_EMBEDDED,
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = webVersion.get(),
            clientId = 56,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
            platform = "DESKTOP",
            wantsSignatureTimestamp = true,
        )

    val MWEB = ClientInfo(
        kind = ClientKind.MWEB,
        clientName = "MWEB",
        clientVersion = "2.20260708.05.00",
        clientId = 2,
        userAgent = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
        platform = "MOBILE",
        wantsSignatureTimestamp = true,
    )

    val ANDROID = ClientInfo(
        kind = ClientKind.ANDROID,
        clientName = "ANDROID",
        clientVersion = "21.26.364",
        clientId = 3,
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        osName = "Android",
        osVersion = "11",
        androidSdkVersion = 30,
        platform = "MOBILE",
        supportsCookies = false,
    )

    val ANDROID_VR = ClientInfo(
        kind = ClientKind.ANDROID_VR,
        clientName = "ANDROID_VR",
        clientVersion = "1.65.10",
        clientId = 28,
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        osName = "Android",
        osVersion = "12L",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = 32,
        platform = "MOBILE",
        supportsCookies = false,
    )

    val IOS = ClientInfo(
        kind = ClientKind.IOS,
        clientName = "IOS",
        clientVersion = "21.26.4",
        clientId = 5,
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        platform = "MOBILE",
        supportsCookies = false,
    )

    val TVHTML5 = ClientInfo(
        kind = ClientKind.TVHTML5,
        clientName = "TVHTML5",
        clientVersion = "7.20260707.07.00",
        clientId = 7,
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        platform = "TV",
        wantsSignatureTimestamp = true,
    )

    val TVHTML5_EMBEDDED = ClientInfo(
        kind = ClientKind.TVHTML5_EMBEDDED,
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        clientId = 85,
        userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
        platform = "TV",
        wantsSignatureTimestamp = true,
    )

    operator fun get(kind: ClientKind): ClientInfo = when (kind) {
        ClientKind.WEB -> WEB
        ClientKind.WEB_EMBEDDED -> WEB_EMBEDDED
        ClientKind.MWEB -> MWEB
        ClientKind.ANDROID -> ANDROID
        ClientKind.ANDROID_VR -> ANDROID_VR
        ClientKind.IOS -> IOS
        ClientKind.TVHTML5 -> TVHTML5
        ClientKind.TVHTML5_EMBEDDED -> TVHTML5_EMBEDDED
    }
}
