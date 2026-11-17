package tube.three06.cast

import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.xml.sax.InputSource

internal const val SSDP_ADDRESS = "239.255.255.250"
internal const val SSDP_PORT = 1900
internal const val DIAL_SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1"
internal const val DIAL_APP_NAME = "YouTube"

internal val M_SEARCH_REQUEST =
    "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $DIAL_SEARCH_TARGET\r\n" +
        "\r\n"

data class SsdpReply(val text: String, val address: String)

interface SsdpTransport {
    fun search(request: String, repeat: Int, intervalMillis: Long, listenMillis: Long): Flow<SsdpReply>
}

class MulticastSsdpTransport : SsdpTransport {
    override fun search(request: String, repeat: Int, intervalMillis: Long, listenMillis: Long): Flow<SsdpReply> =
        flow {
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 250
                socket.timeToLive = 4
                val group = InetSocketAddress(SSDP_ADDRESS, SSDP_PORT)
                val data = request.toByteArray(Charsets.US_ASCII)
                val buffer = ByteArray(4096)
                val start = System.currentTimeMillis()
                var sent = 0
                while (System.currentTimeMillis() - start < listenMillis) {
                    currentCoroutineContext().ensureActive()
                    if (sent < repeat && System.currentTimeMillis() - start >= sent * intervalMillis) {
                        socket.send(DatagramPacket(data, data.size, group))
                        sent++
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val address = packet.address?.hostAddress ?: continue
                    emit(SsdpReply(String(packet.data, 0, packet.length, Charsets.UTF_8), address))
                }
            }
        }.flowOn(Dispatchers.IO)
}

internal data class SsdpResponse(val location: String, val st: String?, val usn: String?)

internal fun parseSsdpResponse(text: String): SsdpResponse? {
    val lines = text.split("\r\n", "\n")
    val status = lines.firstOrNull()?.trim() ?: return null
    if (!status.startsWith("HTTP/", ignoreCase = true) || !status.contains(" 200")) return null
    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
    }
    val location = headers["location"]?.takeIf { it.isNotEmpty() } ?: return null
    return SsdpResponse(location, headers["st"], headers["usn"])
}

internal fun udnFromUsn(usn: String?): String? {
    if (usn == null) return null
    val head = usn.substringBefore("::").trim()
    return if (head.startsWith("uuid:", ignoreCase = true)) head else null
}

internal data class DeviceDescription(val friendlyName: String?, val udn: String?)

internal fun parseDeviceDescription(xml: String): DeviceDescription {
    val doc = parseXml(xml) ?: return DeviceDescription(null, null)
    return DeviceDescription(doc.firstText("friendlyName"), doc.firstText("UDN"))
}

internal fun parseAppState(xml: String): String? = parseXml(xml)?.firstText("state")

private fun parseXml(xml: String): Document? {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isExpandEntityReferences = false
    // Not every platform parser knows these features; the ones that do get them.
    runCatching { factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    return try {
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (e: Exception) {
        null
    }
}

private fun Document.firstText(localName: String): String? {
    val namespaced = getElementsByTagNameNS("*", localName)
    val node = if (namespaced.length > 0) namespaced.item(0) else getElementsByTagName(localName).item(0)
    return node?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

internal class DialClient(http: OkHttpClient) {
    private val http = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun describe(location: String, address: String, udnFromUsn: String?): DialDevice? {
        val response = http.fetch(Request.Builder().url(location).build())
        if (!response.isSuccessful) return null
        val applicationUrl = response.header("Application-URL")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val description = parseDeviceDescription(response.body)
        val base = if (applicationUrl.endsWith("/")) applicationUrl else "$applicationUrl/"
        return DialDevice(
            friendlyName = description.friendlyName ?: address,
            location = location,
            applicationUrl = base,
            address = address,
            udn = description.udn ?: udnFromUsn,
            youtubeState = appState(base),
        )
    }

    suspend fun appState(applicationUrl: String): String? =
        try {
            val response = http.fetch(Request.Builder().url(appUrl(applicationUrl)).build())
            if (response.isSuccessful) parseAppState(response.body) else null
        } catch (e: IOException) {
            null
        }

    suspend fun launch(applicationUrl: String, pairingCode: String) {
        val body = "pairingCode=$pairingCode&theme=cl&dialLaunch=watch"
        val request = Request.Builder()
            .url(appUrl(applicationUrl))
            .header("Origin", LOUNGE_ORIGIN)
            .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        val response = try {
            http.fetch(request)
        } catch (e: IOException) {
            throw PairingException("DIAL launch failed: ${e.message}")
        }
        if (response.code != 201 && response.code != 200) {
            throw PairingException("DIAL launch rejected: HTTP ${response.code}")
        }
    }

    private fun appUrl(applicationUrl: String): String =
        (if (applicationUrl.endsWith("/")) applicationUrl else "$applicationUrl/") + DIAL_APP_NAME
}
