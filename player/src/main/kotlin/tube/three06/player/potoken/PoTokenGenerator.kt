package tube.three06.player.potoken

import java.io.Closeable

internal interface PoTokenGenerator : Closeable {
    suspend fun generatePoToken(identifier: String): String

    fun isExpired(): Boolean
}
