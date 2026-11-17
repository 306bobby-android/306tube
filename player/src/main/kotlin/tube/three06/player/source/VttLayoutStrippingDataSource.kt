package tube.three06.player.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream

class VttLayoutStrippingDataSource(
    private val upstream: DataSource,
    private val unroll: Boolean,
) : BaseDataSource(true) {

    private var data: ByteArray? = null
    private var position = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        upstream.open(dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = upstream.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        val text = String(out.toByteArray(), Charsets.UTF_8)
        val bytes = (if (unroll) unrollRolling(text) else stripLayout(text)).toByteArray(Charsets.UTF_8)
        data = bytes
        position = dataSpec.position.toInt().coerceIn(0, bytes.size)
        transferStarted(dataSpec)
        return (bytes.size - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = data ?: return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        val remaining = bytes.size - position
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val count = minOf(length, remaining)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        if (data != null) {
            data = null
            transferEnded()
        }
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val unroll: Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            VttLayoutStrippingDataSource(upstream.createDataSource(), unroll)
    }

    private class Cue(val start: String, val end: String, val lines: List<String>)

    companion object {
        private val TAGS = Regex("<[^>]*>")

        fun stripCueSettings(timingLine: String): String {
            val arrow = timingLine.indexOf("-->")
            val start = timingLine.substring(0, arrow).trim()
            val end = timingLine.substring(arrow + 3).trim().substringBefore(' ')
            return "$start --> $end"
        }

        fun stripLayout(vtt: String): String =
            vtt.lineSequence()
                .map { line -> if (line.contains("-->")) stripCueSettings(line) else line }
                .joinToString("\n")

        fun unrollRolling(vtt: String): String {
            val blocks = vtt.replace("\r\n", "\n").split(Regex("\n{2,}"))
            val header = blocks.firstOrNull()?.takeIf { it.startsWith("WEBVTT") } ?: "WEBVTT"
            val cues = blocks
                .drop(if (blocks.firstOrNull()?.startsWith("WEBVTT") == true) 1 else 0)
                .mapNotNull { block ->
                    val lines = block.split('\n')
                    val timing = lines.indexOfFirst { it.contains("-->") }
                    if (timing < 0) {
                        null
                    } else {
                        val stripped = stripCueSettings(lines[timing])
                        Cue(
                            start = stripped.substringBefore(" -->"),
                            end = stripped.substringAfter("--> "),
                            lines = lines.drop(timing + 1),
                        )
                    }
                }
            val rolling = cues.any { cue -> cue.lines.any { it.contains("<c>") || it.contains("<c.") } }
            if (!rolling) return stripLayout(vtt)

            val merged = mutableListOf<Cue>()
            for (cue in cues) {
                val text = cue.lines
                    .map { TAGS.replace(it, "").trim() }
                    .lastOrNull { it.isNotEmpty() }
                    ?: continue
                val last = merged.lastOrNull()
                if (last != null && last.lines.single() == text) {
                    merged[merged.lastIndex] = Cue(last.start, cue.end, last.lines)
                } else {
                    merged.add(Cue(cue.start, cue.end, listOf(text)))
                }
            }
            return buildString {
                append(header).append("\n\n")
                merged.forEachIndexed { index, cue ->
                    val body = if (index % 2 == 1) {
                        merged[index - 1].lines.single() + "\n" + cue.lines.single()
                    } else {
                        cue.lines.single()
                    }
                    append(cue.start).append(" --> ").append(cue.end).append('\n').append(body).append("\n\n")
                }
            }
        }
    }
}
