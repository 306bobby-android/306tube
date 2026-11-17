package tube.three06.player.internal

import tube.three06.player.AudioStream
import tube.three06.player.ResolvedStreams
import tube.three06.player.VideoStream
import java.util.Locale

internal object DashManifestGenerator {

    fun eligibleVideo(streams: ResolvedStreams): List<VideoStream> =
        streams.videoStreams.filter {
            !it.hasAudio && it.initRange != null && it.indexRange != null && it.height > 0
        }

    fun eligibleAudio(streams: ResolvedStreams): List<AudioStream> =
        streams.audioStreams.filter { it.initRange != null && it.indexRange != null }

    fun generate(streams: ResolvedStreams): String? {
        val video = eligibleVideo(streams)
        val audio = eligibleAudio(streams)
        if (video.isEmpty() && audio.isEmpty()) return null
        val durationMs = (streams.durationSeconds?.takeIf { it > 0 } ?: return null) * 1000

        val sb = StringBuilder(4096)
        sb.append(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<MPD xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xmlns=\"urn:mpeg:DASH:schema:MPD:2011\" " +
                "xsi:schemaLocation=\"urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd\" " +
                "minBufferTime=\"PT1.500S\" " +
                "profiles=\"urn:mpeg:dash:profile:full:2011\" " +
                "type=\"static\" " +
                "mediaPresentationDuration=\"",
        )
        sb.append(isoDuration(durationMs)).append("\">\n  <Period>\n")

        val usedIds = HashSet<String>()
        var setId = 0

        video.groupBy { it.mimeType }.forEach { (mime, group) ->
            sb.append("    <AdaptationSet id=\"").append(setId++)
                .append("\" mimeType=\"").append(esc(mime))
                .append("\" subsegmentAlignment=\"true\">\n")
            sb.append("      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\"/>\n")
            for (stream in group.sortedByDescending { it.bitrate }) {
                sb.append("      <Representation id=\"").append(uniqueId(usedIds, stream.itag))
                    .append("\" codecs=\"").append(esc(stream.codec))
                    .append("\" startWithSAP=\"1\" maxPlayoutRate=\"1\" bandwidth=\"")
                    .append(stream.bitrate.coerceAtLeast(1))
                    .append("\" height=\"").append(stream.height).append('"')
                if (stream.width > 0) sb.append(" width=\"").append(stream.width).append('"')
                if (stream.fps > 0) sb.append(" frameRate=\"").append(stream.fps).append('"')
                sb.append(">\n")
                appendSegmentInfo(sb, stream.url, stream.initRange!!, stream.indexRange!!)
                sb.append("      </Representation>\n")
            }
            sb.append("    </AdaptationSet>\n")
        }

        audio.groupBy { AudioSetKey(it.mimeType, it.languageCode, it.trackName) }.forEach { (key, group) ->
            sb.append("    <AdaptationSet id=\"").append(setId++)
                .append("\" mimeType=\"").append(esc(key.mime)).append('"')
            key.language?.takeIf { it.isNotBlank() }?.let {
                sb.append(" lang=\"").append(esc(it)).append('"')
            }
            sb.append(" subsegmentAlignment=\"true\">\n")
            val role = if (group.any { it.isDefaultTrack } || key.language == null) "main" else "alternate"
            sb.append("      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"").append(role).append("\"/>\n")
            key.trackName?.takeIf { it.isNotBlank() }?.let {
                sb.append("      <Label>").append(esc(it)).append("</Label>\n")
            }
            for (stream in group.sortedByDescending { it.bitrate }) {
                sb.append("      <Representation id=\"").append(uniqueId(usedIds, stream.itag))
                    .append("\" codecs=\"").append(esc(stream.codec))
                    .append("\" startWithSAP=\"1\" maxPlayoutRate=\"1\" bandwidth=\"")
                    .append(stream.bitrate.coerceAtLeast(1)).append("\">\n")
                sb.append(
                    "        <AudioChannelConfiguration " +
                        "schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n",
                )
                appendSegmentInfo(sb, stream.url, stream.initRange!!, stream.indexRange!!)
                sb.append("      </Representation>\n")
            }
            sb.append("    </AdaptationSet>\n")
        }

        sb.append("  </Period>\n</MPD>\n")
        return sb.toString()
    }

    private data class AudioSetKey(val mime: String, val language: String?, val trackName: String?)

    private fun appendSegmentInfo(sb: StringBuilder, url: String, init: LongRange, index: LongRange) {
        sb.append("        <BaseURL>").append(esc(url)).append("</BaseURL>\n")
        sb.append("        <SegmentBase indexRange=\"").append(index.first).append('-').append(index.last).append("\">\n")
        sb.append("          <Initialization range=\"").append(init.first).append('-').append(init.last).append("\"/>\n")
        sb.append("        </SegmentBase>\n")
    }

    private fun uniqueId(used: MutableSet<String>, itag: Int): String {
        var id = itag.toString()
        var n = 1
        while (!used.add(id)) id = "$itag-${n++}"
        return id
    }

    internal fun isoDuration(durationMs: Long): String =
        String.format(Locale.ENGLISH, "PT%d.%03dS", durationMs / 1000, durationMs % 1000)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
