package tube.three06.innertube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Credentials(
    val cookie: String? = null,
    val visitorData: String? = null,
    val authUser: Int = 0,
    val dataSyncId: String? = null,
    val accountDataSyncId: String? = null,
) {
    val signedIn: Boolean
        get() = !cookie.isNullOrBlank() && cookie.contains("SAPISID")

    val identity: String?
        get() = if (!signedIn) null else "$authUser:${dataSyncId.orEmpty()}:${cookie.hashCode()}"

    val poTokenIdentifier: String?
        get() = accountDataSyncId?.split("||")?.firstOrNull { it.isNotBlank() } ?: dataSyncId ?: visitorData
}

data class Locale(val hl: String = "en", val gl: String = "US")

class Session(initial: Credentials = Credentials(), var locale: Locale = Locale()) {
    private val _credentials = MutableStateFlow(initial)
    val credentials: StateFlow<Credentials> get() = _credentials

    val current: Credentials get() = _credentials.value

    fun update(transform: (Credentials) -> Credentials) {
        _credentials.value = transform(_credentials.value)
    }

    fun signOut() = update { Credentials(visitorData = null) }
}
