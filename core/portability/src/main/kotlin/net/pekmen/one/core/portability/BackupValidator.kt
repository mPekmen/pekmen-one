package net.pekmen.one.core.portability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.ThemePreference
import net.pekmen.one.core.model.TopicStatus
import java.time.LocalDate
import java.util.UUID

internal sealed interface BackupValidationResult {
    data class Valid(val archive: PekmenBackupArchive) : BackupValidationResult
    data class Invalid(val error: BackupValidationError, val details: String) : BackupValidationResult
}

internal object BackupValidator {

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    private val canonicalUuidRegex = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    private val themeNames = ThemePreference.entries.map { it.name }.toSet()
    private val slugNames = BuiltInProfileSlug.entries.map { it.name }.toSet()
    private val modeNames = FocusSessionMode.entries.map { it.name }.toSet()
    private val statusNames = FocusSessionStatus.entries.map { it.name }.toSet()
    private val phaseNames = FocusPhase.entries.map { it.name }.toSet()
    private val syncStatusNames = SyncStatus.entries.map { it.name }.toSet()
    private val topicStatusNames = TopicStatus.entries.map { it.name }.toSet()
    private val recurrenceNames = TaskRecurrenceRule.entries.map { it.name }.toSet()
    private val taskStatusNames = TaskStatus.entries.map { it.name }.toSet()

    fun parseAndValidate(rawJson: String): BackupValidationResult {
        val rootObject = parseNonEmptyObject(rawJson)
            ?: return BackupValidationResult.Invalid(
                BackupValidationError.MALFORMED_JSON,
                "Backup payload is empty or not a JSON object"
            )
        val versionFailure = validateRawSchemaVersion(rootObject)
        if (versionFailure != null) return versionFailure

        val archive = decodeArchive(rootObject)
            ?: return BackupValidationResult.Invalid(
                BackupValidationError.MALFORMED_JSON,
                "Failed to decode backup archive JSON structure"
            )
        return validateArchive(archive)
    }

    private fun parseNonEmptyObject(rawJson: String): JsonObject? {
        if (rawJson.isBlank()) return null
        return runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
    }

    private fun decodeArchive(rootObject: JsonObject): PekmenBackupArchive? {
        return runCatching {
            json.decodeFromJsonElement(PekmenBackupArchive.serializer(), rootObject)
        }.getOrNull()
    }

    private fun validateRawSchemaVersion(rootObject: JsonObject): BackupValidationResult.Invalid? {
        val primitive = rootObject["schemaVersion"] as? JsonPrimitive
        val version = if (primitive != null && !primitive.isString) primitive.intOrNull else null
        if (version == null) {
            return BackupValidationResult.Invalid(
                BackupValidationError.MALFORMED_JSON,
                "Missing or non-integer schemaVersion field"
            )
        }
        val supportedRange = PekmenBackupArchive.MIN_SUPPORTED_SCHEMA_VERSION..
            PekmenBackupArchive.CURRENT_SCHEMA_VERSION
        if (version !in supportedRange) {
            return BackupValidationResult.Invalid(
                BackupValidationError.UNSUPPORTED_SCHEMA_VERSION,
                "Unsupported schemaVersion=$version; supported=${supportedRange.first}..${supportedRange.last}"
            )
        }
        return null
    }

    private fun validateArchive(archive: PekmenBackupArchive): BackupValidationResult {
        val failure = validateArchiveBounds(archive)
            ?: validateAllIdentifiers(archive)
            ?: validatePreferences(archive.preferences)
            ?: validateEntities(archive)
            ?: validateForeignKeys(archive)
        return failure ?: BackupValidationResult.Valid(archive)
    }

    private fun validateArchiveBounds(archive: PekmenBackupArchive): BackupValidationResult.Invalid? {
        if (archive.exportedAtWallMs < 0L) {
            return boundsError("exportedAtWallMs must be non-negative")
        }
        return null
    }

    private fun validateAllIdentifiers(archive: PekmenBackupArchive): BackupValidationResult.Invalid? {
        return validateCollectionIds(archive.profiles.map { it.id }, "profile.id")
            ?: validateCollectionIds(archive.subjects.map { it.id }, "subject.id")
            ?: validateCollectionIds(archive.books.map { it.id }, "book.id")
            ?: validateCollectionIds(archive.topics.map { it.id }, "topic.id")
            ?: validateCollectionIds(archive.sessions.map { it.id }, "session.id")
            ?: validateCollectionIds(archive.segments.map { it.id }, "segment.id")
            ?: validateCollectionIds(archive.tasks.map { it.id }, "task.id")
            ?: validateReferencedUuidFields(archive)
            ?: validateOwnerIds(archive)
    }

    private fun validateCollectionIds(ids: List<String>, fieldName: String): BackupValidationResult.Invalid? {
        val seen = HashSet<String>(ids.size)
        for (id in ids) {
            if (!isValidUuid(id)) {
                return idError("Invalid UUID for $fieldName: $id")
            }
            if (!seen.add(id)) {
                return idError("Duplicate UUID for $fieldName: $id")
            }
        }
        return null
    }

    private fun validateReferencedUuidFields(archive: PekmenBackupArchive): BackupValidationResult.Invalid? {
        val pairs = buildList {
            archive.preferences?.selectedProfileId?.let { add("preferences.selectedProfileId" to it) }
            archive.books.forEach { add("book.subjectId" to it.subjectId) }
            archive.topics.forEach { topic ->
                add("topic.subjectId" to topic.subjectId)
                topic.bookId?.let { add("topic.bookId" to it) }
            }
            archive.sessions.forEach { session ->
                add("session.profileId" to session.profileId)
                session.subjectId?.let { add("session.subjectId" to it) }
                session.bookId?.let { add("session.bookId" to it) }
                session.topicId?.let { add("session.topicId" to it) }
                session.taskId?.let { add("session.taskId" to it) }
            }
            archive.segments.forEach { add("segment.sessionId" to it.sessionId) }
            archive.tasks.forEach { task -> task.subjectId?.let { add("task.subjectId" to it) } }
        }
        for ((field, value) in pairs) {
            if (!isValidUuid(value)) {
                return idError("Invalid UUID for $field: $value")
            }
        }
        return null
    }

    private fun validateOwnerIds(archive: PekmenBackupArchive): BackupValidationResult.Invalid? {
        val allSyncs = buildList {
            archive.profiles.forEach { add(it.sync) }
            archive.subjects.forEach { add(it.sync) }
            archive.books.forEach { add(it.sync) }
            archive.topics.forEach { add(it.sync) }
            archive.sessions.forEach { add(it.sync) }
            archive.tasks.forEach { add(it.sync) }
        }
        for (sync in allSyncs) {
            val owner = sync.ownerUserId
            if (owner != null && owner.isBlank()) {
                return idError("ownerUserId must not be blank when present")
            }
        }
        return null
    }

    private fun validatePreferences(prefs: BackupPreferences?): BackupValidationResult.Invalid? {
        if (prefs == null) return null
        val rolloverRange = EffectiveDayCalculator.MIN_ROLLOVER_HOUR..EffectiveDayCalculator.MAX_ROLLOVER_HOUR
        val customRange = MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES
        return when {
            prefs.themePreference !in themeNames ->
                boundsError("Invalid themePreference: ${prefs.themePreference}")
            prefs.dayRolloverHour !in rolloverRange ->
                boundsError("dayRolloverHour out of bounds: ${prefs.dayRolloverHour}")
            prefs.defaultCustomMinutes !in customRange ->
                boundsError("defaultCustomMinutes out of bounds: ${prefs.defaultCustomMinutes}")
            else -> null
        }
    }

    private fun validateEntities(archive: PekmenBackupArchive): BackupValidationResult.Invalid? {
        return validateProfiles(archive.profiles)
            ?: validateSubjects(archive.subjects)
            ?: validateBooks(archive.books)
            ?: validateTopics(archive.topics)
            ?: validateSessions(archive.sessions)
            ?: validateSegments(archive.segments)
            ?: validateTasks(archive.tasks)
    }

    private fun validateProfiles(profiles: List<BackupProfile>): BackupValidationResult.Invalid? {
        for (profile in profiles) {
            validateSyncMetadata("profile", profile.id, profile.sync)?.let { return it }
            val invalid = profile.builtInSlug !in slugNames ||
                profile.defaultMode !in modeNames ||
                profile.iconKey.isBlank() ||
                profile.workMinutes <= 0 ||
                profile.shortBreakMinutes <= 0 ||
                profile.longBreakMinutes <= 0 ||
                profile.cyclesBeforeLongBreak < 1 ||
                profile.sortOrder < 0
            if (invalid) {
                return boundsError("Invalid bounds or enum in profile ${profile.id}")
            }
        }
        return null
    }

    private fun validateSubjects(subjects: List<BackupSubject>): BackupValidationResult.Invalid? {
        for (subject in subjects) {
            validateSyncMetadata("subject", subject.id, subject.sync)?.let { return it }
            if (subject.title.isBlank() || subject.weeklyGoalMinutes < 0 || subject.sortOrder < 0) {
                return boundsError("Invalid bounds in subject ${subject.id}")
            }
        }
        return null
    }

    private fun validateBooks(books: List<BackupBook>): BackupValidationResult.Invalid? {
        for (book in books) {
            validateSyncMetadata("book", book.id, book.sync)?.let { return it }
            val invalid = book.title.isBlank() ||
                book.totalPages < 0 ||
                book.currentPage < 0 ||
                book.totalChapters < 0 ||
                book.completedChapters < 0
            if (invalid) {
                return boundsError("Invalid page/chapter bounds in book ${book.id}")
            }
        }
        return null
    }

    private fun validateTopics(topics: List<BackupTopic>): BackupValidationResult.Invalid? {
        for (topic in topics) {
            validateSyncMetadata("topic", topic.id, topic.sync)?.let { return it }
            if (topic.title.isBlank() || topic.status !in topicStatusNames || topic.sortOrder < 0) {
                return boundsError("Invalid bounds or status in topic ${topic.id}")
            }
        }
        return null
    }

    private fun validateSessions(sessions: List<BackupSession>): BackupValidationResult.Invalid? {
        for (session in sessions) {
            validateSyncMetadata("session", session.id, session.sync)?.let { return it }
            if (!hasValidSessionEnums(session) || !hasValidSessionBounds(session)) {
                return boundsError("Invalid bounds, date, or enum in session ${session.id}")
            }
        }
        return null
    }

    private fun hasValidSessionEnums(session: BackupSession): Boolean {
        val validAwaiting = session.awaitingNextPhase == null || session.awaitingNextPhase in phaseNames
        val validDate = runCatching { LocalDate.parse(session.effectiveDayDate) }.isSuccess
        return session.sessionMode in modeNames &&
            session.status in statusNames &&
            session.currentPhase in phaseNames &&
            validAwaiting &&
            validDate
    }

    private fun hasValidSessionBounds(session: BackupSession): Boolean {
        val validRating = session.focusRating == null || session.focusRating in 1..5
        val validPlanned = session.plannedFocusSec == null || session.plannedFocusSec >= 0L
        val validEnded = session.endedAtWallMs == null || session.endedAtWallMs >= 0L
        val validStartPage = session.bookStartPage == null || session.bookStartPage >= 0
        val validEndPage = session.bookEndPage == null || session.bookEndPage >= 0
        return session.completedFocusCycles >= 0 &&
            session.startedAtWallMs >= 0L &&
            validEnded &&
            validPlanned &&
            session.actualFocusSec >= 0L &&
            session.actualBreakSec >= 0L &&
            session.pauseCount >= 0 &&
            validStartPage &&
            validEndPage &&
            validRating
    }

    private fun validateSegments(segments: List<BackupSegment>): BackupValidationResult.Invalid? {
        val seenSessionIndexes = HashSet<Pair<String, Int>>(segments.size)
        for (seg in segments) {
            val valid = seg.segmentIndex >= 0 &&
                seg.phase in phaseNames &&
                seg.startedAtWallMs >= 0L &&
                seg.startedAtElapsedMs >= 0L &&
                seg.bootCount >= -1 &&
                seg.lastCheckpointedDurationMs >= 0L &&
                (seg.plannedDurationMs == null || seg.plannedDurationMs >= 0L) &&
                (seg.endedAtWallMs == null || seg.endedAtWallMs >= 0L) &&
                (seg.durationMs == null || seg.durationMs >= 0L) &&
                seenSessionIndexes.add(seg.sessionId to seg.segmentIndex)
            if (!valid) {
                return boundsError("Invalid bounds or duplicate segmentIndex in segment ${seg.id}")
            }
        }
        return null
    }

    private fun validateTasks(tasks: List<BackupTask>): BackupValidationResult.Invalid? {
        for (task in tasks) {
            validateSyncMetadata("task", task.id, task.sync)?.let { return it }
            val valid = task.title.isNotBlank() &&
                (task.dueAtWallMs == null || task.dueAtWallMs >= 0L) &&
                (task.reminderAtWallMs == null || task.reminderAtWallMs >= 0L) &&
                (task.completedAtWallMs == null || task.completedAtWallMs >= 0L) &&
                task.recurrenceRule in recurrenceNames &&
                task.status in taskStatusNames &&
                task.priority >= 0
            if (!valid) {
                return boundsError("Invalid bounds or enum in task ${task.id}")
            }
        }
        return null
    }

    private fun validateSyncMetadata(
        entityLabel: String,
        entityId: String,
        sync: BackupSyncMetadata
    ): BackupValidationResult.Invalid? {
        val valid = sync.createdAtMs >= 0L &&
            sync.clientUpdatedAtMs >= 0L &&
            (sync.serverUpdatedAtMs == null || sync.serverUpdatedAtMs >= 0L) &&
            (sync.deletedAtMs == null || sync.deletedAtMs >= 0L) &&
            sync.version >= 1L &&
            sync.syncStatus in syncStatusNames
        if (!valid) {
            return boundsError("Invalid sync metadata for $entityLabel $entityId")
        }
        return null
    }

    private fun validateForeignKeys(archive: PekmenBackupArchive): BackupValidationResult.Invalid? {
        val profileIds = archive.profiles.mapTo(HashSet()) { it.id }
        val subjectIds = archive.subjects.mapTo(HashSet()) { it.id }
        val bookIds = archive.books.mapTo(HashSet()) { it.id }
        val topicIds = archive.topics.mapTo(HashSet()) { it.id }
        val sessionIds = archive.sessions.mapTo(HashSet()) { it.id }
        val taskIds = archive.tasks.mapTo(HashSet()) { it.id }

        val selectedProfileId = archive.preferences?.selectedProfileId
        if (selectedProfileId != null && selectedProfileId !in profileIds) {
            return fkError("preferences.selectedProfileId=$selectedProfileId missing from profiles")
        }
        return validateLearningForeignKeys(archive, subjectIds, bookIds)
            ?: validateSessionAndTaskForeignKeys(
                archive = archive,
                profileIds = profileIds,
                subjectIds = subjectIds,
                bookIds = bookIds,
                topicIds = topicIds,
                sessionIds = sessionIds,
                taskIds = taskIds
            )
    }

    private fun validateLearningForeignKeys(
        archive: PekmenBackupArchive,
        subjectIds: Set<String>,
        bookIds: Set<String>
    ): BackupValidationResult.Invalid? {
        for (book in archive.books) {
            if (book.subjectId !in subjectIds) {
                return fkError("book ${book.id} references missing subjectId=${book.subjectId}")
            }
        }
        for (topic in archive.topics) {
            if (topic.subjectId !in subjectIds) {
                return fkError("topic ${topic.id} references missing subjectId=${topic.subjectId}")
            }
            if (topic.bookId != null && topic.bookId !in bookIds) {
                return fkError("topic ${topic.id} references missing bookId=${topic.bookId}")
            }
        }
        return null
    }

    private fun validateSessionAndTaskForeignKeys(
        archive: PekmenBackupArchive,
        profileIds: Set<String>,
        subjectIds: Set<String>,
        bookIds: Set<String>,
        topicIds: Set<String>,
        sessionIds: Set<String>,
        taskIds: Set<String>
    ): BackupValidationResult.Invalid? {
        for (session in archive.sessions) {
            val missing = findMissingSessionFk(session, profileIds, subjectIds, bookIds, topicIds, taskIds)
            if (missing != null) return missing
        }
        for (segment in archive.segments) {
            if (segment.sessionId !in sessionIds) {
                return fkError("segment ${segment.id} references missing sessionId=${segment.sessionId}")
            }
        }
        for (task in archive.tasks) {
            if (task.subjectId != null && task.subjectId !in subjectIds) {
                return fkError("task ${task.id} references missing subjectId=${task.subjectId}")
            }
        }
        return null
    }

    private fun findMissingSessionFk(
        session: BackupSession,
        profileIds: Set<String>,
        subjectIds: Set<String>,
        bookIds: Set<String>,
        topicIds: Set<String>,
        taskIds: Set<String>
    ): BackupValidationResult.Invalid? {
        val missingRef = when {
            session.profileId !in profileIds -> "profileId=${session.profileId}"
            session.subjectId != null && session.subjectId !in subjectIds -> "subjectId=${session.subjectId}"
            session.bookId != null && session.bookId !in bookIds -> "bookId=${session.bookId}"
            session.topicId != null && session.topicId !in topicIds -> "topicId=${session.topicId}"
            session.taskId != null && session.taskId !in taskIds -> "taskId=${session.taskId}"
            else -> null
        }
        return missingRef?.let { fkError("session ${session.id} references missing $it") }
    }

    private fun isValidUuid(value: String): Boolean {
        if (!canonicalUuidRegex.matches(value)) return false
        return runCatching { UUID.fromString(value) }.isSuccess
    }

    private fun idError(details: String): BackupValidationResult.Invalid =
        BackupValidationResult.Invalid(BackupValidationError.INVALID_IDENTIFIER, details)

    private fun fkError(details: String): BackupValidationResult.Invalid =
        BackupValidationResult.Invalid(BackupValidationError.INVALID_FOREIGN_KEY, details)

    private fun boundsError(details: String): BackupValidationResult.Invalid =
        BackupValidationResult.Invalid(BackupValidationError.INVALID_FIELD_BOUNDS, details)

    private const val MIN_CUSTOM_MINUTES = 1
    private const val MAX_CUSTOM_MINUTES = 480
}
