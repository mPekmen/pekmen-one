package net.pekmen.one.account

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.pekmen.one.AppContainer
import net.pekmen.one.core.cloud.auth.AuthLinkPurpose
import net.pekmen.one.core.cloud.auth.AuthSessionRecord
import net.pekmen.one.core.cloud.auth.IdentityError
import net.pekmen.one.core.cloud.auth.IdentityFailure
import net.pekmen.one.core.cloud.auth.KeystoreTokenVault
import net.pekmen.one.core.cloud.auth.PendingAuthLinkStore
import net.pekmen.one.core.cloud.auth.SupabaseAuthClient
import net.pekmen.one.core.cloud.auth.SupabaseAuthConfig
import net.pekmen.one.core.model.AccountId
import net.pekmen.one.core.model.PersonalSetup
import net.pekmen.one.core.model.UuidV7
import net.pekmen.one.core.platform.samsung.FocusQuickSettingsTileService
import java.net.URI

internal sealed interface AccountAccess {
    data object Restoring : AccountAccess
    data object SignedOut : AccountAccess
    data object Recovery : AccountAccess
    data class Personal(val account: ActiveAccount, val setup: PersonalSetup) : AccountAccess
}

internal class ActiveAccount(
    val id: AccountId,
    val email: String,
    val name: String?,
    val container: AppContainer
)

internal class PekmenAccounts(
    private val context: Context,
    private val scope: CoroutineScope,
    config: SupabaseAuthConfig?
) {
    private val client = config?.let(::SupabaseAuthClient)
    private val vault = config?.let { KeystoreTokenVault(context, "pekmen_v2_session", it.storageNamespace) }
    private val links = config?.let { PendingAuthLinkStore(context, it.storageNamespace) }
    private val accessState = MutableStateFlow<AccountAccess>(AccountAccess.Restoring)
    private val lock = Mutex()
    private val accountStorage = mutableMapOf<AccountId, PersonalAccountStorage>()
    private val legacy by lazy { LegacyAccountImport(context) }
    private var session: AuthSessionRecord? = null
    private var recovery: AuthSessionRecord? = null
    private var expiry: Job? = null

    val access = accessState.asStateFlow()
    val isConfigured: Boolean = client != null
    val hasVerificationLinks: Boolean = config?.redirect != null

    suspend fun restore() = lock.withLock {
        if (accessState.value != AccountAccess.Restoring) return@withLock
        try {
            val stored = vault?.readSession()
            if (stored == null || client == null) {
                accessState.value = AccountAccess.SignedOut
                return@withLock
            }
            // A cached verified account can open immediately, including on an airplane.
            if (localLeaseIsValid(stored, now())) {
                activate(stored)
            } else {
                val refreshed = client.refresh(stored.refreshToken)
                verifySameOwner(stored, refreshed)
                vault.saveSession(refreshed)
                activate(refreshed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            accessState.value = AccountAccess.SignedOut
        }
    }

    suspend fun signIn(email: String, password: String) = lock.withLock {
        requireSignedOut()
        val signedIn = auth().signIn(email.trim(), password)
        finishSignIn(signedIn)
    }

    suspend fun signUp(email: String, password: String) = lock.withLock {
        requireSignedOut()
        val auth = auth()
        val pending = auth.createLink(AuthLinkPurpose.SIGNUP)
        linkStore().write(pending)
        auth.signUp(email.trim(), password, pending)
    }

    suspend fun beginGoogle(): URI = lock.withLock {
        requireSignedOut()
        val auth = auth()
        val pending = auth.createLink(AuthLinkPurpose.GOOGLE)
        val url = auth.googleAuthorization(pending)
        linkStore().write(pending)
        url
    }

    suspend fun requestPasswordReset(email: String) = lock.withLock {
        requireSignedOut()
        val auth = auth()
        val pending = auth.createLink(AuthLinkPurpose.RECOVERY)
        linkStore().write(pending)
        auth.requestPasswordReset(email.trim(), pending)
    }

    suspend fun receiveLink(uri: URI) = lock.withLock {
        requireSignedOut()
        val pending = linkStore().read() ?: throw IdentityFailure(IdentityError.LINK)
        val verified = auth().exchange(pending, uri)
        linkStore().clear()
        if (pending.purpose == AuthLinkPurpose.RECOVERY) {
            recovery = verified
            accessState.value = AccountAccess.Recovery
        } else {
            finishSignIn(verified)
        }
    }

    suspend fun changePassword(password: String) = lock.withLock {
        val current = recovery ?: throw IdentityFailure(IdentityError.LINK)
        if (now() >= current.expiresAtWallMs) throw IdentityFailure(IdentityError.LINK)
        auth().changePassword(current.accessToken, password)
        recovery = null
        accessState.value = AccountAccess.SignedOut
        noThrowRevoke(current)
    }

    suspend fun cancelRecovery() = lock.withLock {
        val current = recovery
        recovery = null
        accessState.value = AccountAccess.SignedOut
        if (current != null) noThrowRevoke(current)
    }

    suspend fun refreshIfNeeded() = lock.withLock {
        val current = session ?: return@withLock
        val client = client ?: return@withLock
        val valid = localLeaseIsValid(current, now())
        if (valid && current.expiresAtWallMs - now() > REFRESH_MARGIN_MS) return@withLock
        try {
            val updated = client.refresh(current.refreshToken)
            verifySameOwner(current, updated)
            requireNotNull(vault).saveSession(updated)
            session = updated
            scheduleExpiry(updated)
        } catch (error: IdentityFailure) {
            if (error.reason == IdentityError.CREDENTIALS || error.reason == IdentityError.EMAIL_UNVERIFIED) {
                lockAccount(clearVault = true)
            } else if (!localLeaseIsValid(current, now())) {
                lockAccount(clearVault = false)
            }
        }
    }

    suspend fun signOut() {
        withContext(NonCancellable) {
            val previous = lock.withLock {
                val current = session
                lockAccount(clearVault = true)
                links?.clear()
                current
            }
            if (previous != null) scope.launch { noThrowRevoke(previous) }
        }
    }

    suspend fun saveDraft(account: ActiveAccount, incoming: PersonalSetup) = lock.withLock {
        val active = checkAccount(account)
        check(!active.setup.completed)
        val draft = incoming.copy(completed = false, firstLessonId = active.setup.firstLessonId)
        account.container.userPreferencesDataStore.savePersonalSetup(draft)
        accessState.value = active.copy(setup = draft)
    }

    suspend fun finishSetup(account: ActiveAccount, incoming: PersonalSetup) = lock.withLock {
        val active = checkAccount(account)
        if (active.setup.completed) return@withLock
        require(incoming.hasValidIdentity() && incoming.hasValidGoals())
        require(incoming.purpose.isNotBlank() && incoming.categories.isNotEmpty())
        val container = account.container
        require(container.profileRepository.profiles.first().any { it.id == incoming.profileId })
        val lessonId = incoming.firstLesson.trim().takeIf(String::isNotEmpty)?.let {
            active.setup.firstLessonId ?: UuidV7.generate()
        }
        val pending = incoming.copy(
            name = incoming.name.trim(),
            username = incoming.username.trim().lowercase(),
            firstLesson = incoming.firstLesson.trim(),
            firstLessonId = lessonId,
            completed = false
        )
        container.userPreferencesDataStore.savePersonalSetup(pending)
        accessState.value = active.copy(setup = pending)
        auth().updateProfile(freshAccessToken(), pending.name, pending.username)
        lessonId?.let { container.ensureFirstLesson(it, pending.firstLesson, pending.weeklyGoalMinutes) }
        val completed = pending.copy(completed = true)
        container.userPreferencesDataStore.savePersonalSetup(completed, applySelectedProfile = true)
        exposeAccount(active.copy(setup = completed))
    }

    suspend fun updateProfile(account: ActiveAccount, name: String, username: String) = lock.withLock {
        val active = checkAccount(account)
        val updated = active.setup.copy(name = name.trim(), username = username.trim().lowercase())
        require(updated.hasValidIdentity())
        auth().updateProfile(freshAccessToken(), updated.name, updated.username)
        account.container.userPreferencesDataStore.savePersonalSetup(updated)
        accessState.value = active.copy(setup = updated)
    }

    suspend fun updateFocusPreferences(account: ActiveAccount, draft: PersonalSetup) = lock.withLock {
        val active = checkAccount(account)
        check(active.setup.completed)
        require(draft.purpose.isNotBlank() && draft.categories.isNotEmpty() && draft.hasValidGoals())
        require(account.container.profileRepository.profiles.first().any { it.id == draft.profileId })
        val updated = active.setup.copy(
            purpose = draft.purpose.trim(),
            categories = draft.categories,
            dailyGoalMinutes = draft.dailyGoalMinutes,
            weeklyGoalMinutes = draft.weeklyGoalMinutes,
            profileId = draft.profileId
        )
        account.container.userPreferencesDataStore.savePersonalSetup(
            updated, applySelectedProfile = active.setup.profileId != updated.profileId
        )
        accessState.value = active.copy(setup = updated)
    }

    private suspend fun freshAccessToken(): String {
        val local = requireNotNull(session)
        return if (now() >= local.expiresAtWallMs - REFRESH_MARGIN_MS) {
            auth().refresh(local.refreshToken).also {
                verifySameOwner(local, it)
                requireNotNull(vault).saveSession(it)
                session = it
                scheduleExpiry(it)
            }.accessToken
        } else {
            local.accessToken
        }
    }

    suspend fun legacyCount(account: ActiveAccount): Int = lock.withLock {
        checkAccount(account)
        legacy.count(account.id)
    }

    suspend fun claimLegacy(account: ActiveAccount): Int = lock.withLock {
        val active = checkAccount(account)
        check(!active.setup.completed)
        legacy.claim(account.id, account.container.backupPortabilityService)
    }

    suspend fun <T> withReadyAccount(action: suspend (ActiveAccount) -> T): T? {
        val observed = accessState.value as? AccountAccess.Personal ?: return null
        if (!observed.setup.completed) return null
        return lock.withLock {
            val current = accessState.value as? AccountAccess.Personal
            if (current?.account !== observed.account || session?.let { localLeaseIsValid(it, now()) } != true) {
                return@withLock null
            }
            action(current.account)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failed local activation must revoke access to the stored session.
    private suspend fun finishSignIn(record: AuthSessionRecord) {
        // Publish access only after durable identity storage is available.
        requireNotNull(vault).saveSession(record)
        try {
            activate(record)
        } catch (error: Exception) {
            withContext(NonCancellable) { lockAccount(clearVault = true) }
            throw error
        }
    }

    private suspend fun activate(record: AuthSessionRecord) {
        val id = AccountId.parse(record.userId)
        val storage = accountStorage.getOrPut(id) { PersonalAccountStorage(context, id, scope) }
        val container = AppContainer(context, storage)
        container.profileRepository.ensureSeeded()
        val saved = container.userPreferencesDataStore.personalSetup.first()
        val publicAccount = ActiveAccount(id, record.email, record.displayName, container)
        session = record
        scheduleExpiry(record)
        exposeAccount(AccountAccess.Personal(publicAccount, saved))
    }

    private fun exposeAccount(active: AccountAccess.Personal) {
        if (active.setup.completed) active.account.container.authorizePlatformEffects()
        accessState.value = active
        if (!active.setup.completed) return
        scope.launch {
            withReadyAccount { account ->
                val container = account.container
                container.sessionRepository.reconcileActiveSessionAfterBootOrWake()
                container.plannerRepository.rescheduleAllPendingReminders()
                FocusQuickSettingsTileService.requestTileUpdate(context)
            }
        }
    }

    private suspend fun lockAccount(clearVault: Boolean) {
        val previous = (accessState.value as? AccountAccess.Personal)?.account
        accessState.value = AccountAccess.SignedOut
        session = null
        expiry?.cancel()
        withContext(NonCancellable) {
            try {
                if (clearVault) vault?.clearSession()
            } finally {
                if (previous != null) {
                    previous.container.retirePlatformEffects()
                    previous.container.sessionRepository.pauseActiveSession()
                }
            }
        }
    }

    private fun scheduleExpiry(record: AuthSessionRecord) {
        expiry?.cancel()
        expiry = scope.launch {
            delay((record.verifiedAtWallMs + OFFLINE_LEASE_MS - now()).coerceAtLeast(0))
            lock.withLock {
                if (session === record && !localLeaseIsValid(record, now())) {
                    // Don't cancel the running expiry job from inside lockAccount.
                    expiry = null
                    lockAccount(clearVault = false)
                }
            }
        }
    }

    private fun checkAccount(account: ActiveAccount): AccountAccess.Personal {
        val active = accessState.value as? AccountAccess.Personal
        check(active?.account === account && session?.let { localLeaseIsValid(it, now()) } == true)
        return active
    }

    private fun requireSignedOut() {
        if (accessState.value != AccountAccess.SignedOut) throw IdentityFailure(IdentityError.LINK)
    }

    private fun verifySameOwner(original: AuthSessionRecord, refreshed: AuthSessionRecord) {
        if (original.userId != refreshed.userId) throw IdentityFailure(IdentityError.CREDENTIALS)
    }

    private suspend fun noThrowRevoke(current: AuthSessionRecord) {
        try {
            client?.revoke(current.accessToken)
        } catch (_: IdentityFailure) {
            // Local sign-out has already committed. The upstream token expires independently.
        }
    }

    private fun auth(): SupabaseAuthClient = client ?: throw IdentityFailure(IdentityError.CONFIGURATION)
    private fun linkStore(): PendingAuthLinkStore = links ?: throw IdentityFailure(IdentityError.CONFIGURATION)
    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}

private const val OFFLINE_LEASE_MS = 7L * 24 * 60 * 60 * 1_000

internal fun localLeaseIsValid(record: AuthSessionRecord, now: Long): Boolean =
    record.verifiedAtWallMs > 0 && now - record.verifiedAtWallMs in 0 until OFFLINE_LEASE_MS
