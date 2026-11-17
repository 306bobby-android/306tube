package tube.three06.player.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

class RangeChunkDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var open = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.path?.startsWith("/videoplayback") == true
        if (!chunked) {
            open = true
            return upstream.open(dataSpec)
        }
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.position + dataSpec.length
        } else {
            dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
        }
        openChunk()
        return if (end != C.LENGTH_UNSET.toLong()) end - position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk(): Boolean {
        val base = spec ?: return false
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return false
        val want = if (end != C.LENGTH_UNSET.toLong()) minOf(chunkBytes, end - position) else chunkBytes
        val opened = upstream.open(base.buildUpon().setPosition(position).setLength(want).build())
        open = true
        chunkRemaining = if (opened == C.LENGTH_UNSET.toLong()) want else opened
        if (end == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong() && opened < want) {
            end = position + opened
        }
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (chunkRemaining <= 0) {
            closeUpstream()
            if (!openChunk()) return C.RESULT_END_OF_INPUT
        }
        val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            end = position
            chunkRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        position += read
        chunkRemaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeUpstream()
        spec = null
    }

    private fun closeUpstream() {
        if (open) {
            open = false
            upstream.close()
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = 4L * 1024 * 1024,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RangeChunkDataSource(upstream.createDataSource(), chunkBytes)
    }
}
