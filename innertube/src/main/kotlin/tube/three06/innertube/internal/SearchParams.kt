package tube.three06.innertube.internal

import tube.three06.innertube.model.SearchFilter
import java.io.ByteArrayOutputStream
import java.util.Base64

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    val isEmpty: Boolean get() = out.size() == 0

    fun varint(field: Int, value: Int): ProtoWriter {
        writeVarint(field shl 3)
        writeVarint(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray())

    fun message(field: Int, inner: ProtoWriter): ProtoWriter = bytes(field, inner.bytes())

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeVarint((field shl 3) or 2)
        writeVarint(value.size)
        out.write(value)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()

    private fun writeVarint(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
}

internal fun encodeSearchParams(filter: SearchFilter): String? {
    val filters = ProtoWriter()
    filter.uploadDate?.let {
        filters.varint(
            1,
            when (it) {
                SearchFilter.UploadDate.HOUR -> 1
                SearchFilter.UploadDate.TODAY -> 2
                SearchFilter.UploadDate.WEEK -> 3
                SearchFilter.UploadDate.MONTH -> 4
                SearchFilter.UploadDate.YEAR -> 5
            },
        )
    }
    filter.type?.let {
        filters.varint(
            2,
            when (it) {
                SearchFilter.Type.VIDEO -> 1
                SearchFilter.Type.CHANNEL -> 2
                SearchFilter.Type.PLAYLIST -> 3
                SearchFilter.Type.MOVIE -> 4
            },
        )
    }
    filter.duration?.let {
        filters.varint(
            3,
            when (it) {
                SearchFilter.Duration.SHORT -> 1
                SearchFilter.Duration.LONG -> 2
                SearchFilter.Duration.MEDIUM -> 3
            },
        )
    }
    for (f in filter.features.sortedBy { featureField(it) }) filters.varint(featureField(f), 1)

    val root = ProtoWriter()
    filter.sort?.let {
        root.varint(
            1,
            when (it) {
                SearchFilter.Sort.RELEVANCE -> 0
                SearchFilter.Sort.RATING -> 1
                SearchFilter.Sort.UPLOAD_DATE -> 2
                SearchFilter.Sort.VIEW_COUNT -> 3
            },
        )
    }
    if (!filters.isEmpty) root.message(2, filters)
    if (root.isEmpty) return null
    return Base64.getEncoder().encodeToString(root.bytes()).replace("=", "%3D")
}

private fun featureField(f: SearchFilter.Feature): Int = when (f) {
    SearchFilter.Feature.HD -> 4
    SearchFilter.Feature.SUBTITLES -> 5
    SearchFilter.Feature.CREATIVE_COMMONS -> 6
    SearchFilter.Feature.THREE_D -> 7
    SearchFilter.Feature.LIVE -> 8
    SearchFilter.Feature.PURCHASED -> 9
    SearchFilter.Feature.FOUR_K -> 14
    SearchFilter.Feature.THREE_SIXTY -> 15
    SearchFilter.Feature.LOCATION -> 23
    SearchFilter.Feature.HDR -> 25
    SearchFilter.Feature.VR180 -> 26
}
