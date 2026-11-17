package tube.three06.cast

interface ScreenStore {
    suspend fun load(): List<Screen>
    suspend fun save(screens: List<Screen>)
    suspend fun loadSenderId(): String? = null
    suspend fun saveSenderId(id: String) {}
}
