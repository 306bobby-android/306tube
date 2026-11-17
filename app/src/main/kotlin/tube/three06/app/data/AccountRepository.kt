package tube.three06.app.data

import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tube.three06.app.data.db.AccountDao
import tube.three06.app.data.db.AccountEntity
import tube.three06.app.data.security.SecretBox
import tube.three06.innertube.Credentials
import tube.three06.innertube.Session
import tube.three06.innertube.YouTube

class AccountRepository(
    private val dao: AccountDao,
    private val session: Session,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val secrets: SecretBox = SecretBox(),
) {
    private val _active = MutableStateFlow<AccountEntity?>(null)
    val active: StateFlow<AccountEntity?> = _active.asStateFlow()

    val accounts: Flow<List<AccountEntity>> = dao.observeAll()

    suspend fun restore() {
        sealLegacyRows()
        val account = dao.active()
        _active.value = account
        val visitorData = settings.current.visitorData
        session.update {
            Credentials(
                cookie = account?.let(::cookieOf),
                visitorData = visitorData,
                authUser = account?.authUser ?: 0,
                dataSyncId = account?.dataSyncId,
            )
        }
        scope.launch { persistSessionChanges() }
    }

    private fun cookieOf(account: AccountEntity): String? = secrets.open(account.cookie)

    private suspend fun sealLegacyRows() {
        dao.all()
            .filter { !secrets.isSealed(it.cookie) }
            .forEach { dao.update(it.copy(cookie = secrets.seal(it.cookie))) }
    }

    private suspend fun persistSessionChanges() {
        session.credentials
            .map { it.visitorData to it.dataSyncId }
            .distinctUntilChanged()
            .collect { (visitorData, dataSyncId) ->
                if (visitorData != settings.current.visitorData) {
                    settings.update { it.copy(visitorData = visitorData) }
                }
                val account = _active.value
                if (account != null && dataSyncId != null && dataSyncId != account.dataSyncId) {
                    val updated = account.copy(dataSyncId = dataSyncId)
                    dao.update(updated)
                    _active.value = updated
                }
            }
    }

    suspend fun addFromCookie(cookie: String, youTube: YouTube): AccountEntity {
        require(cookie.contains("SAPISID")) { "Cookie has no SAPISID; sign-in did not complete" }
        session.update { it.copy(cookie = cookie, authUser = 0, dataSyncId = null) }
        val (rows, selectedId) = syncIdentities(cookie, youTube)
        val chosen = rows.firstOrNull { it.id == selectedId } ?: rows.first()
        switchTo(chosen.id)
        return _active.value ?: chosen
    }

    suspend fun refreshIdentities(youTube: YouTube) {
        val account = _active.value ?: return
        cookieOf(account)?.let { cookie -> runCatching { syncIdentities(cookie, youTube) } }
        _active.value = dao.byId(account.id) ?: _active.value
    }

    private suspend fun syncIdentities(cookie: String, youTube: YouTube): Pair<List<AccountEntity>, Long?> {
        fun AccountEntity.sameCookie() = cookieOf(this) == cookie

        val existing = dao.all()
        val sealed = secrets.seal(cookie)
        val identities = runCatching { youTube.accounts() }.getOrDefault(emptyList())
        if (identities.isEmpty()) {
            val info = runCatching { youTube.accountInfo() }.getOrNull()
            val entity = AccountEntity(
                cookie = sealed,
                name = info?.name ?: "Google account",
                email = info?.email,
                avatarUrl = info?.avatar?.maxByOrNull { it.width ?: 0 }?.url,
            )
            val id = upsert(
                entity,
                existing.firstOrNull { it.dataSyncId == null && it.email != null && it.email == entity.email },
            )
            return listOf(dao.byId(id)!!) to id
        }

        val rows = ArrayList<AccountEntity>()
        var selectedId: Long? = null
        for (identity in identities) {
            val entity = AccountEntity(
                cookie = sealed,
                name = identity.name,
                email = identity.email,
                avatarUrl = identity.avatar.maxByOrNull { it.width ?: 0 }?.url,
                authUser = identity.authUser,
                dataSyncId = identity.pageId,
            )
            val match = if (identity.pageId != null) {
                existing.firstOrNull { it.dataSyncId == identity.pageId }
            } else {
                existing.firstOrNull { it.dataSyncId == null && it.sameCookie() && it.id !in rows.map { r -> r.id } }
                    ?: existing.firstOrNull {
                        it.dataSyncId == null && it.email != null && it.email == identity.email
                    }
            }
            val id = upsert(entity, match)
            if (identity.selected) selectedId = id
            rows += dao.byId(id)!!
        }

        val stale = existing.filter { it.sameCookie() && rows.none { r -> r.id == it.id } }
        if (stale.any { it.active }) {
            val keep = rows.firstOrNull { it.id == selectedId } ?: rows.first()
            dao.setActive(keep.id)
            if (_active.value?.id in stale.map { it.id }) _active.value = dao.byId(keep.id)
        }
        stale.forEach { dao.delete(it.id) }
        return rows.map { dao.byId(it.id)!! } to selectedId
    }

    private suspend fun upsert(entity: AccountEntity, match: AccountEntity?): Long {
        if (match == null) return dao.insert(entity)
        dao.update(entity.copy(id = match.id, active = match.active, addedAt = match.addedAt))
        return match.id
    }

    suspend fun switchTo(id: Long) {
        val account = dao.byId(id) ?: return
        dao.setActive(id)
        _active.value = account
        session.update {
            it.copy(cookie = cookieOf(account), authUser = account.authUser, dataSyncId = account.dataSyncId)
        }
    }

    suspend fun signOut(id: Long) {
        val account = dao.byId(id) ?: return
        val siblings = dao.all().filter { it.cookie == account.cookie || cookieOf(it) == cookieOf(account) }
        val wasActive = siblings.any { it.id == _active.value?.id }
        siblings.forEach { dao.delete(it.id) }
        if (!wasActive) return
        _active.value = null
        dao.clearActive()
        session.update { it.copy(cookie = null, authUser = 0, dataSyncId = null) }
        withContext(Dispatchers.Main) { clearWebViewCookies() }
    }

    fun clearWebViewCookies() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
