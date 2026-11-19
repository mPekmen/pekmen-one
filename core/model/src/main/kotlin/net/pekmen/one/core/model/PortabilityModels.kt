package net.pekmen.one.core.model

enum class ExportFormat {
    JSON,
    CSV
}

data class BackupImportReport(
    val sourceSchemaVersion: Int,
    val profilesRestored: Int,
    val subjectsRestored: Int,
    val booksRestored: Int,
    val topicsRestored: Int,
    val sessionsRestored: Int,
    val segmentsRestored: Int,
    val tasksRestored: Int
)

sealed interface BackupImportResult {
    data class Success(val report: BackupImportReport) : BackupImportResult
    data class Failure(val reason: BackupValidationError, val details: String) : BackupImportResult
}

enum class BackupValidationError {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_IDENTIFIER,
    INVALID_FOREIGN_KEY,
    INVALID_FIELD_BOUNDS,
    IO_FAILURE
}
