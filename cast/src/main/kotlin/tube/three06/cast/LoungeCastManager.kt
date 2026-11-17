package tube.three06.cast

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

class LoungeCastManager internal constructor(
    private val http: OkHttpClient,
    private val store: ScreenStore,
    private val deviceId: String?,
    private val deviceName: String,
    private val ssdp: SsdpTransport,
    private val loungeBaseUrl: String,
) : CastManager {

    constructor(
        http: OkHttpClient,
        store: ScreenStore,
        deviceId: String? = null,
        deviceName: String = "306Tube",
        ssdp: SsdpTransport = MulticastSsdpTransport(),
    ) : this(http, store, deviceId, deviceName, ssdp, LOUNGE_BASE_URL)

    private val api = LoungeApi(http, loungeBaseUrl)
    private val dial = DialClient(http)
    private val storeMutex = Mutex()

    @Volatile
    private var senderId: String? = deviceId

    internal var pairPollIntervalMillis: Long = 2_000
    internal var pairTimeoutMillis: Long = 60_000
    internal var discoverListenMillis: Long = 3_500

    internal suspend fun senderId(): String {
        senderId?.let { return it }
        return storeMutex.withLock {
            senderId ?: (store.loadSenderId() ?: UUID.randomUUID().toString().also { store.saveSenderId(it) })
                .also { senderId = it }
        }
    }

    override fun discover(): Flow<DialDevice> = channelFlow {
        val seen = HashSet<String>()
        ssdp.search(M_SEARCH_REQUEST, 3, 1_000, discoverListenMillis).collect { reply ->
            val response = parseSsdpResponse(reply.text) ?: return@collect
            if (response.st != null && response.st != DIAL_SEARCH_TARGET) return@collect
            if (!seen.add(response.usn ?: response.location)) return@collect
            launch {
                val device = try {
                    dial.describe(response.location, reply.address, udnFromUsn(response.usn))
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (device != null) send(device)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun pair(device: DialDevice): Screen {
        val pairingCode = UUID.randomUUID().toString()
        dial.launch(device.applicationUrl, pairingCode)
        val deadline = System.currentTimeMillis() + pairTimeoutMillis
        while (true) {
            val result = try {
                api.getScreen(pairingCode)
            } catch (e: IOException) {
                throw PairingException("get_screen failed: ${e.message}")
            }
            if (result.isSuccessful) {
                return remember(api.parseScreen(result.body, device.address))
            }
            if (System.currentTimeMillis() >= deadline) {
                throw PairingException(
                    "${device.friendlyName} did not register the pairing code (last HTTP ${result.code})",
                )
            }
            delay(pairPollIntervalMillis)
        }
    }

    override suspend fun pairWithCode(code: String): Screen {
        val digits = code.filter { it.isDigit() }
        if (digits.isEmpty()) throw PairingException("Not a TV code: '$code'")
        val result = try {
            api.getScreen(digits)
        } catch (e: IOException) {
            throw PairingException("get_screen failed: ${e.message}")
        }
        if (!result.isSuccessful) throw PairingException("Code not accepted: HTTP ${result.code}")
        return remember(api.parseScreen(result.body, null))
    }

    override suspend fun connect(screen: Screen): LoungeSession {
        val fresh = ensureFreshToken(screen)
        val session = LoungeSessionImpl(fresh, http, loungeBaseUrl, senderId(), deviceName) {
            remember(api.refreshToken(it))
        }
        session.start()
        return session
    }

    override suspend fun knownScreens(): List<Screen> = store.load()

    override suspend fun forget(screen: Screen) {
        storeMutex.withLock {
            store.save(store.load().filter { it.screenId != screen.screenId })
        }
    }

    private suspend fun ensureFreshToken(screen: Screen): Screen {
        val remaining = screen.tokenExpiresAtMillis - System.currentTimeMillis()
        if (screen.tokenExpiresAtMillis <= 0 || remaining <= 0) {
            return remember(api.refreshToken(screen))
        }
        if (remaining >= REFRESH_MARGIN_MILLIS) return screen
        // Token still works; a failed early refresh is not worth failing the connect over.
        return try {
            remember(api.refreshToken(screen))
        } catch (e: LoungeException) {
            screen
        }
    }

    private suspend fun remember(screen: Screen): Screen = storeMutex.withLock {
        val all = store.load()
        val updated = if (all.any { it.screenId == screen.screenId }) {
            all.map {
                if (it.screenId == screen.screenId) {
                    screen.copy(lastAddress = screen.lastAddress ?: it.lastAddress)
                } else {
                    it
                }
            }
        } else {
            all + screen
        }
        store.save(updated)
        updated.first { it.screenId == screen.screenId }
    }

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 86_400_000L
    }
}
