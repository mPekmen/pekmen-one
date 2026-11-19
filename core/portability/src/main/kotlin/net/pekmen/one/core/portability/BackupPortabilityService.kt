package net.pekmen.one.core.portability

import net.pekmen.one.core.model.BackupImportResult

interface BackupPortabilityService {
    suspend fun exportJsonArchive(): Result<String>
    suspend fun importJsonArchive(rawJson: String): BackupImportResult
    suspend fun exportSessionsCsv(): Result<String>
    suspend fun exportLearningCsv(): Result<String>
    suspend fun exportTasksCsv(): Result<String>
}
