package tube.three06.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class StoryboardFrame(
    val url: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Select a deterministic frame from a YouTube storyboard specification. */
internal fun storyboardFrame(
    spec: String,
    videoId: String,
): StoryboardFrame? {
    val parts = spec.split('|')
    if (parts.size < 2) return null
    val fraction = (videoId.hashCode().toLong() and 0xFFFF) / 65536.0 * 0.8 + 0.1
    val levels =
        parts.drop(1).mapIndexedNotNull { index, level ->
            val fields = level.split('#')
            if (fields.size < 8) return@mapIndexedNotNull null
            val width = fields[0].toIntOrNull() ?: return@mapIndexedNotNull null
            val height = fields[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val count = fields[2].toIntOrNull() ?: return@mapIndexedNotNull null
            val columns = fields[3].toIntOrNull() ?: return@mapIndexedNotNull null
            val rows = fields[4].toIntOrNull() ?: return@mapIndexedNotNull null
            if (width !in 1..320 || height !in 1..180 || count !in 1..100000 ||
                columns !in 1..20 || rows !in 1..20 || width * columns > 4096 || height * rows > 4096 ||
                fields[6].isEmpty() || fields[7].isEmpty()
            ) {
                return@mapIndexedNotNull null
            }
            val frame = (count * fraction).toInt().coerceIn(0, count - 1)
            val perSheet = columns * rows
            val sheet = frame / perSheet
            val cell = frame % perSheet
            val name = fields[6].replace("\$M", sheet.toString())
            val address =
                parts[0].replace("\$L", index.toString()).replace("\$N", name).toHttpUrlOrNull()
                    ?: return@mapIndexedNotNull null
            if (address.scheme != "https" || !(address.host == "ytimg.com" || address.host.endsWith(".ytimg.com")) ||
                address.username.isNotEmpty() || address.password.isNotEmpty()
            ) {
                return@mapIndexedNotNull null
            }
            val url =
                address
                    .newBuilder()
                    .addQueryParameter("sigh", fields[7])
                    .build()
                    .toString()
            StoryboardFrame(url, cell % columns * width, cell / columns * height, width, height)
        }
    return levels.maxByOrNull { it.width * it.height }
}
