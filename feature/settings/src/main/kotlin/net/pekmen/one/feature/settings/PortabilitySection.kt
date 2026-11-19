package net.pekmen.one.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.model.BackupImportReport
import net.pekmen.one.core.model.BackupValidationError
import net.pekmen.one.core.model.ExportFormat

enum class PortabilityCsvTarget {
    SESSIONS,
    LEARNING,
    TASKS
}

sealed interface PortabilityStatus {
    data object Idle : PortabilityStatus
    data object InProgress : PortabilityStatus
    data class ExportSuccess(val format: ExportFormat) : PortabilityStatus
    data class RestoreSuccess(val report: BackupImportReport) : PortabilityStatus
    data class Error(val error: BackupValidationError, val details: String) : PortabilityStatus
}

data class PortabilityUiState(
    val isBusy: Boolean = false,
    val status: PortabilityStatus = PortabilityStatus.Idle
)

@Composable
fun PortabilitySection(
    state: PortabilityUiState,
    onExportBackupJson: (Uri) -> Unit,
    onRestoreBackupJson: (Uri) -> Unit,
    onExportCsv: (PortabilityCsvTarget, Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_JSON)
    ) { uri -> uri?.let(onExportBackupJson) }

    val restoreJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onRestoreBackupJson) }

    val sessionsCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_CSV)
    ) { uri -> uri?.let { onExportCsv(PortabilityCsvTarget.SESSIONS, it) } }

    val learningCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_CSV)
    ) { uri -> uri?.let { onExportCsv(PortabilityCsvTarget.LEARNING, it) } }

    val tasksCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_CSV)
    ) { uri -> uri?.let { onExportCsv(PortabilityCsvTarget.TASKS, it) } }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.portability_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.portability_section_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { exportJsonLauncher.launch(DEFAULT_BACKUP_JSON_NAME) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(text = stringResource(R.string.portability_export_json_button))
            }

            OutlinedButton(
                onClick = { restoreJsonLauncher.launch(JSON_OPEN_MIME_TYPES) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(text = stringResource(R.string.portability_restore_json_button))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CsvExportButtons(
                enabled = !state.isBusy,
                onExportSessions = { sessionsCsvLauncher.launch(DEFAULT_SESSIONS_CSV_NAME) },
                onExportLearning = { learningCsvLauncher.launch(DEFAULT_LEARNING_CSV_NAME) },
                onExportTasks = { tasksCsvLauncher.launch(DEFAULT_TASKS_CSV_NAME) }
            )

            PortabilityStatusBanner(status = state.status)
        }
    }
}

@Composable
private fun CsvExportButtons(
    enabled: Boolean,
    onExportSessions: () -> Unit,
    onExportLearning: () -> Unit,
    onExportTasks: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.portability_csv_section_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedButton(
            onClick = onExportSessions,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.portability_export_sessions_csv))
        }
        OutlinedButton(
            onClick = onExportLearning,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.portability_export_learning_csv))
        }
        OutlinedButton(
            onClick = onExportTasks,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.portability_export_tasks_csv))
        }
    }
}

@Composable
private fun PortabilityStatusBanner(status: PortabilityStatus) {
    when (status) {
        PortabilityStatus.Idle -> Unit
        PortabilityStatus.InProgress -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.portability_status_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is PortabilityStatus.ExportSuccess -> ExportSuccessCard(format = status.format)
        is PortabilityStatus.RestoreSuccess -> RestoreSuccessCard(report = status.report)
        is PortabilityStatus.Error -> ErrorStatusCard(error = status.error, details = status.details)
    }
}

@Composable
private fun ExportSuccessCard(format: ExportFormat) {
    val messageRes = when (format) {
        ExportFormat.JSON -> R.string.portability_export_json_success
        ExportFormat.CSV -> R.string.portability_export_csv_success
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(PekmenIcons.Check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RestoreSuccessCard(report: BackupImportReport) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(PekmenIcons.Check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.portability_restore_success_title,
                        PekmenFormatters.formatInteger(report.sourceSchemaVersion)
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(
                    R.string.portability_restore_counts_primary,
                    PekmenFormatters.formatInteger(report.profilesRestored),
                    PekmenFormatters.formatInteger(report.sessionsRestored),
                    PekmenFormatters.formatInteger(report.segmentsRestored)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.portability_restore_counts_secondary,
                    PekmenFormatters.formatInteger(report.subjectsRestored),
                    PekmenFormatters.formatInteger(report.booksRestored),
                    PekmenFormatters.formatInteger(report.topicsRestored),
                    PekmenFormatters.formatInteger(report.tasksRestored)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorStatusCard(error: BackupValidationError, details: String) {
    val reasonRes = when (error) {
        BackupValidationError.MALFORMED_JSON -> R.string.portability_error_malformed_json
        BackupValidationError.UNSUPPORTED_SCHEMA_VERSION -> R.string.portability_error_unsupported_schema
        BackupValidationError.INVALID_IDENTIFIER -> R.string.portability_error_invalid_identifier
        BackupValidationError.INVALID_FOREIGN_KEY -> R.string.portability_error_invalid_foreign_key
        BackupValidationError.INVALID_FIELD_BOUNDS -> R.string.portability_error_invalid_field_bounds
        BackupValidationError.IO_FAILURE -> R.string.portability_error_io_failure
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(PekmenIcons.Warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.portability_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = stringResource(reasonRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (details.isNotBlank()) {
                Text(
                    text = stringResource(R.string.portability_error_detail_format, details),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

private const val MIME_JSON = "application/json"
private const val MIME_CSV = "text/csv"
private val JSON_OPEN_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")
private const val DEFAULT_BACKUP_JSON_NAME = "pekmen-one-backup.json"
private const val DEFAULT_SESSIONS_CSV_NAME = "pekmen-sessions.csv"
private const val DEFAULT_LEARNING_CSV_NAME = "pekmen-learning.csv"
private const val DEFAULT_TASKS_CSV_NAME = "pekmen-tasks.csv"
