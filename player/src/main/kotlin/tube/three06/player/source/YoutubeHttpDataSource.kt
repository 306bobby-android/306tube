package tube.three06.player.source

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import tube.three06.innertube.ClientKind
import tube.three06.innertube.Clients
import tube.three06.player.internal.OkHttpDownloader
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

@OptIn(UnstableApi::class)
class YoutubeHttpDataSource private constructor(
    private val client: OkHttpClient,
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean,
    private val defaultRequestProperties: HttpDataSource.RequestProperties?,
) : BaseDataSource(true), HttpDataSource {

    class Factory(private val client: OkHttpClient) : HttpDataSource.Factory {
        private val defaultRequestProperties = HttpDataSource.RequestProperties()
        private var transferListener: TransferListener? = null
        private var rangeParameterEnabled = false
        private var rnParameterEnabled = false

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        fun setTransferListener(listener: TransferListener?): Factory {
            transferListener = listener
            return this
        }

        fun setRangeParameterEnabled(enabled: Boolean): Factory {
            rangeParameterEnabled = enabled
            return this
        }

        fun setRnParameterEnabled(enabled: Boolean): Factory {
            rnParameterEnabled = enabled
            return this
        }

        override fun createDataSource(): YoutubeHttpDataSource {
            val source = YoutubeHttpDataSource(
                client,
                rangeParameterEnabled,
                rnParameterEnabled,
                defaultRequestProperties,
            )
            transferListener?.let { source.addTransferListener(it) }
            return source
        }
    }

    private val requestProperties = HttpDataSource.RequestProperties()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L
    private var requestNumber = 0L

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getResponseCode(): Int = response?.code ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun getUri(): Uri? =
        response?.request?.url?.toString()?.let(Uri::parse) ?: dataSpec?.uri

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val response = try {
            client.newCall(buildRequest(dataSpec)).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        this.response = response
        val code = response.code
        val body = response.body

        if (!response.isSuccessful) {
            if (code == 416) {
                val documentSize = HttpUtil.getDocumentSize(response.header("Content-Range"))
                if (dataSpec.position == documentSize) {
                    opened = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
                }
            }
            val errorBody = try {
                body?.bytes() ?: ByteArray(0)
            } catch (_: IOException) {
                ByteArray(0)
            }
            val headers = response.headers.toMultimap()
            closeConnectionQuietly()
            val cause = if (code == 416) {
                DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            } else {
                null
            }
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                response.message,
                cause,
                headers,
                dataSpec,
                errorBody,
            )
        }

        val bytesToSkip =
            if (!rangeParameterEnabled && code == 200 && dataSpec.position != 0L) dataSpec.position else 0L

        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            val contentLength = body?.contentLength() ?: -1L
            if (contentLength != -1L) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
        }

        inputStream = body?.byteStream()
        opened = true
        transferStarted(dataSpec)

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            closeConnectionQuietly()
            throw e
        }
        return bytesToRead
    }

    private fun buildRequest(dataSpec: DataSpec): Request {
        val position = dataSpec.position
        val length = dataSpec.length
        var url = dataSpec.uri.toString()
        val isVideoPlayback = dataSpec.uri.path?.startsWith("/videoplayback") == true

        if (isVideoPlayback && rnParameterEnabled && !url.contains(RN_PARAMETER)) {
            url += RN_PARAMETER + requestNumber
            requestNumber++
        }
        if (isVideoPlayback && rangeParameterEnabled) {
            buildRangeParameter(position, length)?.let { url += it }
        }

        val builder = Request.Builder().url(url)

        val headers = HashMap<String, String>()
        defaultRequestProperties?.let { headers.putAll(it.snapshot) }
        headers.putAll(requestProperties.snapshot)
        headers.putAll(dataSpec.httpRequestHeaders)
        for ((name, value) in headers) builder.header(name, value)

        if (!rangeParameterEnabled) {
            HttpUtil.buildRangeRequestHeader(position, length)?.let { builder.header("Range", it) }
        }

        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", YOUTUBE_BASE_URL)
            builder.header("Referer", YOUTUBE_BASE_URL)
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        }
        builder.header("TE", "trailers")
        if (isVideoPlayback) builder.header("Connection", "close")

        val clientName = CLIENT_PARAM.find(url)?.groupValues?.get(1)
        val userAgent = when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            clientName == null ||
                clientName.startsWith("WEB") ||
                clientName.startsWith("MWEB") ||
                clientName.startsWith("TV") -> headers["User-Agent"] ?: OkHttpDownloader.USER_AGENT
            else -> knownClientUserAgent(clientName) ?: headers["User-Agent"] ?: OkHttpDownloader.USER_AGENT
        }
        builder.header("User-Agent", userAgent)

        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.header("Accept-Encoding", "identity")
        }
        builder.post(POST_BODY.toRequestBody())
        return builder.build()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            readInternal(buffer, offset, length)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                checkNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }

    private fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        var toRead = readLength
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            toRead = minOf(toRead.toLong(), remaining).toInt()
        }
        val read = checkNotNull(inputStream).read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
        if (bytesToSkip == 0L) return
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(4096)
        val stream = checkNotNull(inputStream)
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) {
                throw HttpDataSource.HttpDataSourceException(
                    InterruptedIOException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            try {
                val read = stream.read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
                if (read == -1) {
                    throw HttpDataSource.HttpDataSourceException(
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                    )
                }
                remaining -= read
                bytesTransferred(read)
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
        }
    }

    override fun close() {
        try {
            closeConnectionQuietly()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeConnectionQuietly() {
        try {
            inputStream?.close()
        } catch (_: IOException) {
        }
        inputStream = null
        response?.close()
        response = null
    }

    private companion object {
        const val RN_PARAMETER = "&rn="
        const val YOUTUBE_BASE_URL = "https://www.youtube.com"

        // Protobuf field 15 = 0.
        val POST_BODY = byteArrayOf(0x78, 0)

        private val CLIENT_PARAM = Regex("[?&]c=([A-Z_]+)")

        private fun knownClientUserAgent(clientName: String): String? =
            ClientKind.entries
                .map { Clients.get(it) }
                .firstOrNull { it.clientName == clientName }
                ?.userAgent

        fun buildRangeParameter(position: Long, length: Long): String? {
            if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
            return buildString {
                append("&range=")
                append(position).append('-')
                if (length != C.LENGTH_UNSET.toLong()) append(position + length - 1)
            }
        }
    }
}
