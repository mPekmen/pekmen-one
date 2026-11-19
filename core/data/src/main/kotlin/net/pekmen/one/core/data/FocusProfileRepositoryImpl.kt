package net.pekmen.one.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.database.dao.AccountSessionDao
import net.pekmen.one.core.database.dao.FocusProfileDao
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UuidV7

@OptIn(ExperimentalCoroutinesApi::class)
class FocusProfileRepositoryImpl(
    private val profileDao: FocusProfileDao,
    private val timeProvider: TimeProvider,
    private val accountSessionDao: AccountSessionDao? = null,
    private val preferencesDataStore: UserPreferencesDataStore? = null
) : FocusProfileRepository {

    override val profiles: Flow<List<FocusProfile>> =
        if (accountSessionDao != null && preferencesDataStore != null) {
            preferencesDataStore.preferences
                .map { LearningOutboxEncoder.normalizeOwner(it.activeOwnerUserId) }
                .distinctUntilChanged()
                .flatMapLatest { ownerId ->
                    accountSessionDao.observeActiveProfilesByOwner(ownerId)
                        .map { entities -> entities.map { it.toDomain() } }
                }
        } else {
            profileDao.observeActiveProfiles().map { entities -> entities.map { it.toDomain() } }
        }

    override suspend fun ensureSeeded() {
        if (profileDao.countProfiles() > 0) return
        val now = timeProvider.wallTimeMs()
        val seeds = BuiltInProfiles.defaults().map { profile ->
            FocusProfileEntity(
                id = profile.id,
                builtInSlug = profile.builtInSlug.name,
                customName = profile.customName,
                colorHex = profile.colorHex,
                iconKey = profile.iconKey,
                defaultMode = profile.defaultMode.name,
                workMinutes = profile.workMinutes,
                shortBreakMinutes = profile.shortBreakMinutes,
                longBreakMinutes = profile.longBreakMinutes,
                cyclesBeforeLongBreak = profile.cyclesBeforeLongBreak,
                dndEnabled = profile.dndEnabled,
                sortOrder = profile.sortOrder,
                isArchived = false,
                sync = SyncMetadataEntity(createdAtMs = now, clientUpdatedAtMs = now)
            )
        }
        profileDao.insertAllIgnore(seeds)
    }

    override suspend fun saveProfile(request: SaveProfileRequest): Result<FocusProfile> = runCatching {
        ensureSeeded()
        val now = timeProvider.wallTimeMs()
        val work = request.workMinutes.coerceIn(MIN_MINUTES, MAX_WORK_MINUTES)
        val shortBreak = request.shortBreakMinutes.coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES)
        val longBreak = request.longBreakMinutes.coerceIn(MIN_MINUTES, MAX_LONG_BREAK_MINUTES)
        val cycles = request.cyclesBeforeLongBreak.coerceIn(1, MAX_CYCLES)
        val cleanedName = request.customName.trim().take(MAX_NAME_LENGTH)
        val ownerId = preferencesDataStore?.let {
            LearningOutboxEncoder.normalizeOwner(it.preferences.first().activeOwnerUserId)
        }

        val existing = request.existingId?.let { profileDao.getById(it) }
        if (existing != null) {
            val isCustom = existing.builtInSlug == BuiltInProfileSlug.CUSTOM.name
            if (isCustom && preferencesDataStore != null) {
                require(existing.sync.ownerUserId == ownerId) {
                    "Profile belongs to a different account"
                }
            }
            val updated = existing.copy(
                customName = if (cleanedName.isEmpty() && !isCustom) {
                    null
                } else {
                    cleanedName.ifEmpty { existing.customName }
                },
                colorHex = request.colorHex,
                defaultMode = request.defaultMode.name,
                workMinutes = work,
                shortBreakMinutes = shortBreak,
                longBreakMinutes = longBreak,
                cyclesBeforeLongBreak = cycles,
                sync = existing.sync.copy(
                    clientUpdatedAtMs = now,
                    version = existing.sync.version + 1L,
                    syncStatus = if (isCustom) {
                        LearningOutboxEncoder.syncStatusFor(existing.sync.ownerUserId)
                    } else {
                        existing.sync.syncStatus
                    }
                )
            )
            profileDao.update(updated)
            return@runCatching updated.toDomain()
        }

        require(cleanedName.isNotEmpty()) { "Custom profile name must not be blank" }
        val nextSortOrder = profileDao.countProfiles()
        val created = FocusProfileEntity(
            id = UuidV7.generate(now),
            builtInSlug = BuiltInProfileSlug.CUSTOM.name,
            customName = cleanedName,
            colorHex = request.colorHex,
            iconKey = "custom",
            defaultMode = request.defaultMode.name,
            workMinutes = work,
            shortBreakMinutes = shortBreak,
            longBreakMinutes = longBreak,
            cyclesBeforeLongBreak = cycles,
            dndEnabled = false,
            sortOrder = nextSortOrder,
            isArchived = false,
            sync = SyncMetadataEntity(
                ownerUserId = ownerId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerId)
            )
        )
        profileDao.insert(created)
        created.toDomain()
    }

    private companion object {
        const val MIN_MINUTES = 1
        const val MAX_WORK_MINUTES = 180
        const val MAX_BREAK_MINUTES = 60
        const val MAX_LONG_BREAK_MINUTES = 90
        const val MAX_CYCLES = 10
        const val MAX_NAME_LENGTH = 40
    }
}
