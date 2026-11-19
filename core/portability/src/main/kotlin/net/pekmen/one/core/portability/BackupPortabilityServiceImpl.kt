package net.pekmen.one.core.portability

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.BackupImportReport
import net.pekmen.one.core.model.BackupImportResult
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.model.UserPreferences
import java.io.IOException

class BackupPortabilityServiceImpl(
    private val database: PekmenDatabase,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val timeProvider: TimeProvider,
    private val onRestoreCompleted: (suspend () -> Unit)? = null
) : BackupPortabilityService {

    private val portabilityDao = database.portabilityDao()

    override suspend fun exportJsonArchive(): Result<String> = runSuspendCatching {
        val prefs = preferencesDataStore.preferences.first()
        val data = loadScopedExportData(prefs.activeOwnerUserId)

        val profileIds = data.profiles.mapTo(HashSet()) { it.id }
        val subjectIds = data.subjects.mapTo(HashSet()) { it.id }
        val bookIds = data.books.mapTo(HashSet()) { it.id }
        val topicIds = data.topics.mapTo(HashSet()) { it.id }
        val taskIds = data.tasks.mapTo(HashSet()) { it.id }

        val archive = PekmenBackupArchive(
            schemaVersion = PekmenBackupArchive.CURRENT_SCHEMA_VERSION,
            exportedAtWallMs = timeProvider.wallTimeMs(),
            appVersion = PekmenBackupArchive.CURRENT_APP_VERSION,
            preferences = prefs.toBackupPreferences(profileIds),
            profiles = data.profiles.map { it.toBackup() },
            subjects = data.subjects.map { it.toBackup() },
            books = data.books.map { it.toBackup() },
            topics = data.topics.map { it.toBackup(bookIds) },
            sessions = data.sessions.map { it.toBackup(subjectIds, bookIds, topicIds, taskIds) },
            segments = data.segments.map { it.toBackup() },
            tasks = data.tasks.map { it.toBackup(subjectIds) }
        )
        BackupValidator.json.encodeToString(PekmenBackupArchive.serializer(), archive)
    }

    override suspend fun importJsonArchive(rawJson: String): BackupImportResult {
        val archive = when (val validation = BackupValidator.parseAndValidate(rawJson)) {
            is BackupValidationResult.Invalid -> {
                return BackupImportResult.Failure(validation.error, validation.details)
            }
            is BackupValidationResult.Valid -> validation.archive
        }

        val activeOwner = BackupOwnershipHelper.normalizeOwner(
            preferencesDataStore.preferences.first().activeOwnerUserId
        )
        BackupOwnershipHelper.validateArchiveOwnership(archive, activeOwner)?.let { return it }
        val normalized = BackupOwnershipHelper.normalizeArchive(archive, activeOwner)

        return try {
            database.withTransaction {
                BackupOwnershipHelper.assertNoCrossOwnerCollisions(database, normalized, activeOwner)
                upsertAllEntities(normalized)
                BackupOwnershipHelper.enqueueClaimedGuestOutbox(
                    database = database,
                    originalArchive = archive,
                    activeOwner = activeOwner,
                    nowMs = timeProvider.wallTimeMs()
                )
                normalized.preferences?.let { restorePreferencesAtomically(it) }
            }
            onRestoreCompleted?.invoke()
            BackupImportResult.Success(normalized.toImportReport())
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: SQLiteConstraintException) {
            BackupImportResult.Failure(
                BackupValidationError.INVALID_FOREIGN_KEY,
                "Database constraint failure during restore: ${err.message ?: "constraint error"}"
            )
        } catch (err: SQLiteException) {
            BackupImportResult.Failure(
                BackupValidationError.IO_FAILURE,
                "Database error during restore: ${err.message ?: "sqlite error"}"
            )
        } catch (err: IOException) {
            BackupImportResult.Failure(
                BackupValidationError.IO_FAILURE,
                "I/O error while restoring preferences: ${err.message ?: "io failure"}"
            )
        } catch (err: IllegalStateException) {
            BackupImportResult.Failure(
                BackupValidationError.IO_FAILURE,
                "Atomic restore rolled back: ${err.message ?: "illegal state"}"
            )
        } catch (err: IllegalArgumentException) {
            BackupImportResult.Failure(
                BackupValidationError.INVALID_FIELD_BOUNDS,
                "Invalid field value during restore: ${err.message ?: "invalid argument"}"
            )
        }
    }

    override suspend fun exportSessionsCsv(): Result<String> = runSuspendCatching {
        val activeOwner = preferencesDataStore.preferences.first().activeOwnerUserId
        val profiles = portabilityDao.getProfilesForOwnerOnce(activeOwner).associateBy { it.id }
        val sessions = portabilityDao.getSessionsForOwnerOnce(activeOwner)
        CsvExporter.exportSessionsCsv(sessions, profiles)
    }

    override suspend fun exportLearningCsv(): Result<String> = runSuspendCatching {
        val activeOwner = preferencesDataStore.preferences.first().activeOwnerUserId
        val subjects = portabilityDao.getSubjectsForOwnerOnce(activeOwner)
        val subjectIds = subjects.mapTo(HashSet()) { it.id }
        val books = portabilityDao.getBooksForOwnerOnce(activeOwner)
            .filter { it.subjectId in subjectIds }
        val topics = portabilityDao.getTopicsForOwnerOnce(activeOwner)
            .filter { it.subjectId in subjectIds }
        CsvExporter.exportLearningCsv(subjects, books, topics)
    }

    override suspend fun exportTasksCsv(): Result<String> = runSuspendCatching {
        val activeOwner = preferencesDataStore.preferences.first().activeOwnerUserId
        val tasks = portabilityDao.getTasksForOwnerOnce(activeOwner)
            .sortedWith(compareBy<PlannerTaskEntity>({ it.dueAtWallMs ?: Long.MAX_VALUE }, { -it.priority }))
        CsvExporter.exportTasksCsv(tasks)
    }

    private suspend fun loadScopedExportData(activeOwner: String?): ScopedExportData {
        val profiles = portabilityDao.getProfilesForOwnerOnce(activeOwner)
        val subjects = portabilityDao.getSubjectsForOwnerOnce(activeOwner)
        val subjectIds = subjects.mapTo(HashSet()) { it.id }
        val books = portabilityDao.getBooksForOwnerOnce(activeOwner)
            .filter { it.subjectId in subjectIds }
        val topics = portabilityDao.getTopicsForOwnerOnce(activeOwner)
            .filter { it.subjectId in subjectIds }
        val sessions = portabilityDao.getSessionsForOwnerOnce(activeOwner)
        val sessionIds = sessions.mapTo(HashSet()) { it.id }
        val segments = portabilityDao.getSegmentsForOwnerOnce(activeOwner)
            .filter { it.sessionId in sessionIds }
        val tasks = portabilityDao.getTasksForOwnerOnce(activeOwner)
        return ScopedExportData(profiles, subjects, books, topics, sessions, segments, tasks)
    }

    private suspend fun upsertAllEntities(archive: PekmenBackupArchive) {
        if (archive.profiles.isNotEmpty()) {
            portabilityDao.upsertProfiles(archive.profiles.map { it.toEntity() })
        }
        if (archive.subjects.isNotEmpty()) {
            portabilityDao.upsertSubjects(archive.subjects.map { it.toEntity() })
        }
        if (archive.books.isNotEmpty()) {
            portabilityDao.upsertBooks(archive.books.map { it.toEntity() })
        }
        if (archive.topics.isNotEmpty()) {
            portabilityDao.upsertTopics(archive.topics.map { it.toEntity() })
        }
        if (archive.sessions.isNotEmpty()) {
            portabilityDao.upsertSessions(archive.sessions.map { it.toEntity() })
        }
        if (archive.segments.isNotEmpty()) {
            portabilityDao.upsertSegments(archive.segments.map { it.toEntity() })
        }
        if (archive.tasks.isNotEmpty()) {
            portabilityDao.upsertTasks(archive.tasks.map { it.toEntity() })
        }
    }

    private suspend fun restorePreferencesAtomically(target: BackupPreferences) {
        val previous = preferencesDataStore.preferences.first()
        var committed = false
        try {
            applyBackupPreferences(target)
            committed = true
        } finally {
            if (!committed) {
                runCatching { applyUserPreferences(previous) }
            }
        }
    }

    private suspend fun applyBackupPreferences(prefs: BackupPreferences) {
        preferencesDataStore.setThemePreference(ThemePreference.valueOf(prefs.themePreference))
        preferencesDataStore.setDayRolloverHour(prefs.dayRolloverHour)
        preferencesDataStore.setSelectedProfileId(prefs.selectedProfileId)
        preferencesDataStore.setAutoStartBreaks(prefs.autoStartBreaks)
        preferencesDataStore.setAutoStartNextFocus(prefs.autoStartNextFocus)
        preferencesDataStore.setDefaultCustomMinutes(prefs.defaultCustomMinutes)
        preferencesDataStore.setZenIntegrationEnabled(prefs.zenIntegrationEnabled)
        preferencesDataStore.setRoutineKeywordEnabled(prefs.routineKeywordEnabled)
    }

    private suspend fun applyUserPreferences(prefs: UserPreferences) {
        preferencesDataStore.setThemePreference(prefs.themePreference)
        preferencesDataStore.setDayRolloverHour(prefs.dayRolloverHour)
        preferencesDataStore.setSelectedProfileId(prefs.selectedProfileId)
        preferencesDataStore.setAutoStartBreaks(prefs.autoStartBreaks)
        preferencesDataStore.setAutoStartNextFocus(prefs.autoStartNextFocus)
        preferencesDataStore.setDefaultCustomMinutes(prefs.defaultCustomMinutes)
        preferencesDataStore.setZenIntegrationEnabled(prefs.zenIntegrationEnabled)
        preferencesDataStore.setRoutineKeywordEnabled(prefs.routineKeywordEnabled)
    }

    private suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: SerializationException) {
            Result.failure(err)
        } catch (err: SQLiteException) {
            Result.failure(err)
        } catch (err: IOException) {
            Result.failure(err)
        } catch (err: IllegalStateException) {
            Result.failure(err)
        } catch (err: IllegalArgumentException) {
            Result.failure(err)
        }
    }

    private data class ScopedExportData(
        val profiles: List<FocusProfileEntity>,
        val subjects: List<LearningSubjectEntity>,
        val books: List<LearningBookEntity>,
        val topics: List<LearningTopicEntity>,
        val sessions: List<FocusSessionEntity>,
        val segments: List<FocusSessionSegmentEntity>,
        val tasks: List<PlannerTaskEntity>
    )
}

private fun UserPreferences.toBackupPreferences(exportedProfileIds: Set<String>): BackupPreferences {
    return BackupPreferences(
        themePreference = themePreference.name,
        dayRolloverHour = dayRolloverHour,
        selectedProfileId = selectedProfileId?.takeIf { it in exportedProfileIds },
        autoStartBreaks = autoStartBreaks,
        autoStartNextFocus = autoStartNextFocus,
        defaultCustomMinutes = defaultCustomMinutes,
        zenIntegrationEnabled = zenIntegrationEnabled,
        routineKeywordEnabled = routineKeywordEnabled
    )
}

private fun SyncMetadataEntity.toBackup(): BackupSyncMetadata = BackupSyncMetadata(
    ownerUserId = ownerUserId,
    createdAtMs = createdAtMs,
    clientUpdatedAtMs = clientUpdatedAtMs,
    serverUpdatedAtMs = serverUpdatedAtMs,
    deletedAtMs = deletedAtMs,
    version = version,
    syncStatus = syncStatus
)

private fun BackupSyncMetadata.toEntity(): SyncMetadataEntity = SyncMetadataEntity(
    ownerUserId = ownerUserId,
    createdAtMs = createdAtMs,
    clientUpdatedAtMs = clientUpdatedAtMs,
    serverUpdatedAtMs = serverUpdatedAtMs,
    deletedAtMs = deletedAtMs,
    version = version,
    syncStatus = syncStatus
)

private fun FocusProfileEntity.toBackup(): BackupProfile = BackupProfile(
    id = id,
    builtInSlug = builtInSlug,
    customName = customName,
    colorHex = colorHex,
    iconKey = iconKey,
    defaultMode = defaultMode,
    workMinutes = workMinutes,
    shortBreakMinutes = shortBreakMinutes,
    longBreakMinutes = longBreakMinutes,
    cyclesBeforeLongBreak = cyclesBeforeLongBreak,
    dndEnabled = dndEnabled,
    sortOrder = sortOrder,
    isArchived = isArchived,
    sync = sync.toBackup()
)

private fun BackupProfile.toEntity(): FocusProfileEntity = FocusProfileEntity(
    id = id,
    builtInSlug = builtInSlug,
    customName = customName,
    colorHex = colorHex,
    iconKey = iconKey,
    defaultMode = defaultMode,
    workMinutes = workMinutes,
    shortBreakMinutes = shortBreakMinutes,
    longBreakMinutes = longBreakMinutes,
    cyclesBeforeLongBreak = cyclesBeforeLongBreak,
    dndEnabled = dndEnabled,
    sortOrder = sortOrder,
    isArchived = isArchived,
    sync = sync.toEntity()
)

private fun LearningSubjectEntity.toBackup(): BackupSubject = BackupSubject(
    id = id,
    title = title,
    colorHex = colorHex,
    weeklyGoalMinutes = weeklyGoalMinutes,
    sortOrder = sortOrder,
    isArchived = isArchived,
    sync = sync.toBackup()
)

private fun BackupSubject.toEntity(): LearningSubjectEntity = LearningSubjectEntity(
    id = id,
    title = title,
    colorHex = colorHex,
    weeklyGoalMinutes = weeklyGoalMinutes,
    sortOrder = sortOrder,
    isArchived = isArchived,
    sync = sync.toEntity()
)

private fun LearningBookEntity.toBackup(): BackupBook = BackupBook(
    id = id,
    subjectId = subjectId,
    title = title,
    author = author,
    totalPages = totalPages,
    currentPage = currentPage,
    totalChapters = totalChapters,
    completedChapters = completedChapters,
    isCompleted = isCompleted,
    sync = sync.toBackup()
)

private fun BackupBook.toEntity(): LearningBookEntity = LearningBookEntity(
    id = id,
    subjectId = subjectId,
    title = title,
    author = author,
    totalPages = totalPages,
    currentPage = currentPage,
    totalChapters = totalChapters,
    completedChapters = completedChapters,
    isCompleted = isCompleted,
    sync = sync.toEntity()
)

private fun LearningTopicEntity.toBackup(exportedBookIds: Set<String>): BackupTopic = BackupTopic(
    id = id,
    subjectId = subjectId,
    bookId = bookId?.takeIf { it in exportedBookIds },
    title = title,
    status = status,
    sortOrder = sortOrder,
    sync = sync.toBackup()
)

private fun BackupTopic.toEntity(): LearningTopicEntity = LearningTopicEntity(
    id = id,
    subjectId = subjectId,
    bookId = bookId,
    title = title,
    status = status,
    sortOrder = sortOrder,
    sync = sync.toEntity()
)

private fun FocusSessionEntity.toBackup(
    exportedSubjectIds: Set<String>,
    exportedBookIds: Set<String>,
    exportedTopicIds: Set<String>,
    exportedTaskIds: Set<String>
): BackupSession = BackupSession(
    id = id,
    profileId = profileId,
    subjectId = subjectId?.takeIf { it in exportedSubjectIds },
    bookId = bookId?.takeIf { it in exportedBookIds },
    topicId = topicId?.takeIf { it in exportedTopicIds },
    taskId = taskId?.takeIf { it in exportedTaskIds },
    bookStartPage = bookStartPage,
    bookEndPage = bookEndPage,
    sessionMode = sessionMode,
    status = status,
    currentPhase = currentPhase,
    awaitingNextPhase = awaitingNextPhase,
    completedFocusCycles = completedFocusCycles,
    startedAtWallMs = startedAtWallMs,
    endedAtWallMs = endedAtWallMs,
    effectiveDayDate = effectiveDayDate,
    plannedFocusSec = plannedFocusSec,
    actualFocusSec = actualFocusSec,
    actualBreakSec = actualBreakSec,
    pauseCount = pauseCount,
    isManualEntry = isManualEntry,
    wasManuallyAdjusted = wasManuallyAdjusted,
    interruptedByReboot = interruptedByReboot,
    focusRating = focusRating,
    reflectionNote = reflectionNote,
    sync = sync.toBackup()
)

private fun BackupSession.toEntity(): FocusSessionEntity = FocusSessionEntity(
    id = id,
    profileId = profileId,
    subjectId = subjectId,
    bookId = bookId,
    topicId = topicId,
    taskId = taskId,
    bookStartPage = bookStartPage,
    bookEndPage = bookEndPage,
    sessionMode = sessionMode,
    status = status,
    currentPhase = currentPhase,
    awaitingNextPhase = awaitingNextPhase,
    completedFocusCycles = completedFocusCycles,
    startedAtWallMs = startedAtWallMs,
    endedAtWallMs = endedAtWallMs,
    effectiveDayDate = effectiveDayDate,
    plannedFocusSec = plannedFocusSec,
    actualFocusSec = actualFocusSec,
    actualBreakSec = actualBreakSec,
    pauseCount = pauseCount,
    isManualEntry = isManualEntry,
    wasManuallyAdjusted = wasManuallyAdjusted,
    interruptedByReboot = interruptedByReboot,
    focusRating = focusRating,
    reflectionNote = reflectionNote,
    sync = sync.toEntity()
)

private fun FocusSessionSegmentEntity.toBackup(): BackupSegment = BackupSegment(
    id = id,
    sessionId = sessionId,
    segmentIndex = segmentIndex,
    phase = phase,
    startedAtWallMs = startedAtWallMs,
    startedAtElapsedMs = startedAtElapsedMs,
    bootCount = bootCount,
    lastCheckpointedDurationMs = lastCheckpointedDurationMs,
    plannedDurationMs = plannedDurationMs,
    endedAtWallMs = endedAtWallMs,
    durationMs = durationMs
)

private fun BackupSegment.toEntity(): FocusSessionSegmentEntity = FocusSessionSegmentEntity(
    id = id,
    sessionId = sessionId,
    segmentIndex = segmentIndex,
    phase = phase,
    startedAtWallMs = startedAtWallMs,
    startedAtElapsedMs = startedAtElapsedMs,
    bootCount = bootCount,
    lastCheckpointedDurationMs = lastCheckpointedDurationMs,
    plannedDurationMs = plannedDurationMs,
    endedAtWallMs = endedAtWallMs,
    durationMs = durationMs
)

private fun PlannerTaskEntity.toBackup(exportedSubjectIds: Set<String>): BackupTask = BackupTask(
    id = id,
    title = title,
    notes = notes,
    subjectId = subjectId?.takeIf { it in exportedSubjectIds },
    dueAtWallMs = dueAtWallMs,
    reminderAtWallMs = reminderAtWallMs,
    recurrenceRule = recurrenceRule,
    status = status,
    completedAtWallMs = completedAtWallMs,
    priority = priority,
    sync = sync.toBackup()
)

private fun BackupTask.toEntity(): PlannerTaskEntity = PlannerTaskEntity(
    id = id,
    title = title,
    notes = notes,
    subjectId = subjectId,
    dueAtWallMs = dueAtWallMs,
    reminderAtWallMs = reminderAtWallMs,
    recurrenceRule = recurrenceRule,
    status = status,
    completedAtWallMs = completedAtWallMs,
    priority = priority,
    sync = sync.toEntity()
)

private fun PekmenBackupArchive.toImportReport(): BackupImportReport = BackupImportReport(
    sourceSchemaVersion = schemaVersion,
    profilesRestored = profiles.size,
    subjectsRestored = subjects.size,
    booksRestored = books.size,
    topicsRestored = topics.size,
    sessionsRestored = sessions.size,
    segmentsRestored = segments.size,
    tasksRestored = tasks.size
)
