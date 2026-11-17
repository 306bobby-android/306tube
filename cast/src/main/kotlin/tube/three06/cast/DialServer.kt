package tube.three06.cast

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Makes this device show up in the cast list of phones on the same network: answers SSDP
 * searches for DIAL devices and serves the DIAL REST endpoints for the "YouTube" app.
 */
class DialServer(
    private val friendlyName: String,
    private val uuid: String,
    private val screenId: () -> String?,
    private val onLaunch: suspend (pairingCode: String?) -> Unit,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        coroutineScope {
            // accept() and receive() block; short timeouts let both loops notice cancellation.
            val server = ServerSocket(0).apply { soTimeout = 1000 }
            val ssdp = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                soTimeout = 1000
                @Suppress("DEPRECATION")
                joinGroup(InetAddress.getByName(SSDP_ADDRESS))
            }
            try {
                launch { answerSearches(ssdp, server.localPort) }
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        break
                    }
                    launch { runCatching { socket.use { serve(it, server.localPort, this@coroutineScope) } } }
                }
            } finally {
                runCatching { server.close() }
                runCatching { ssdp.close() }
            }
        }
    }

    private fun CoroutineScope.answerSearches(socket: MulticastSocket, httpPort: Int) {
        val buffer = ByteArray(2048)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                return
            }
            val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
            if (!text.startsWith("M-SEARCH") || !text.contains(DIAL_SEARCH_TARGET)) continue
            runCatching {
                val reply = (
                    "HTTP/1.1 200 OK\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\n" +
                        "EXT:\r\n" +
                        "LOCATION: http://${localAddressFacing(packet.address)}:$httpPort/dd.xml\r\n" +
                        "SERVER: Linux/5.0 UPnP/1.1 306Tube/1.0\r\n" +
                        "ST: $DIAL_SEARCH_TARGET\r\n" +
                        "USN: uuid:$uuid::$DIAL_SEARCH_TARGET\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }
    }

    // The address a peer would reach us on: whichever interface routes back to it.
    private fun localAddressFacing(peer: InetAddress): String =
        DatagramSocket().use {
            it.connect(peer, SSDP_PORT)
            it.localAddress.hostAddress ?: "127.0.0.1"
        }

    private fun serve(socket: Socket, port: Int, scope: CoroutineScope) {
        socket.soTimeout = 5000
        val input = socket.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, rawPath) = lines.first().split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()
        val length = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 64 * 1024)
        val body = String(readBody(input, length), Charsets.UTF_8)
        val base = "http://${socket.localAddress.hostAddress}:$port"
        val path = rawPath.substringBefore('?').trimEnd('/')
        val cors = headers["origin"]?.let { "Access-Control-Allow-Origin: $it\r\n" }.orEmpty()

        fun respond(status: String, extraHeaders: String = "", content: String = "") {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 $status\r\n" + cors + extraHeaders +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            out.write(bytes)
            out.flush()
        }

        when {
            method == "OPTIONS" -> respond(
                "204 No Content",
                "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n",
            )
            method == "GET" && path == "/dd.xml" -> respond(
                "200 OK",
                "Content-Type: text/xml; charset=utf-8\r\nApplication-URL: $base/apps/\r\n",
                deviceDescription(),
            )
            method == "GET" && path == "/apps/$DIAL_APP_NAME" -> respond(
                "200 OK",
                "Content-Type: text/xml; charset=utf-8\r\n",
                appDescription(),
            )
            method == "POST" && path == "/apps/$DIAL_APP_NAME" -> {
                val code = body.split('&').firstOrNull { it.startsWith("pairingCode=") }
                    ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }
                scope.launch { runCatching { onLaunch(code) } }
                respond("201 Created", "Location: $base/apps/$DIAL_APP_NAME/run\r\n")
            }
            method == "DELETE" && path == "/apps/$DIAL_APP_NAME/run" -> respond("200 OK")
            else -> respond("404 Not Found")
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val bytes = out.toByteArray()
            val n = bytes.size
            if (n >= 4 && bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
                bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
            ) {
                return String(bytes, 0, n - 4, Charsets.US_ASCII)
            }
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return bytes.copyOf(read)
    }

    private fun deviceDescription(): String =
        """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
    <friendlyName>${friendlyName.xml()}</friendlyName>
    <manufacturer>306Tube</manufacturer>
    <modelName>306Tube</modelName>
    <UDN>uuid:$uuid</UDN>
  </device>
</root>"""

    private fun appDescription(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7">
  <name>$DIAL_APP_NAME</name>
  <options allowStop="false"/>
  <state>running</state>
  <link rel="run" href="run"/>
  <additionalData>
    <screenId>${screenId().orEmpty().xml()}</screenId>
  </additionalData>
</service>"""

    private fun String.xml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
