package net.pekmen.one.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.platform.samsung.SamsungRoutineBridgeHelper

@Composable
internal fun ZenAndSamsungBridgeCard(
    zenIntegrationEnabled: Boolean,
    routineKeywordEnabled: Boolean,
    hasPolicyAccess: Boolean,
    onToggleZenIntegration: (Boolean) -> Unit,
    onToggleRoutineKeyword: (Boolean) -> Unit,
    onOpenDndPolicySettings: () -> Unit
) {
    val context = LocalContext.current
    val setupSteps = remember(context) { SamsungRoutineBridgeHelper.oneUiSetupSteps(context) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_system_bridges_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SwitchSettingRow(
                label = stringResource(R.string.settings_zen_integration_label),
                checked = zenIntegrationEnabled,
                onCheckedChange = onToggleZenIntegration
            )
            DiagnosticRow(
                title = stringResource(R.string.settings_dnd_policy_access_title),
                status = if (hasPolicyAccess) {
                    stringResource(R.string.settings_status_granted)
                } else {
                    stringResource(R.string.settings_status_missing)
                },
                actionLabel = stringResource(R.string.settings_action_open_system),
                onAction = onOpenDndPolicySettings
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchSettingRow(
                label = stringResource(R.string.settings_routine_keyword_label),
                checked = routineKeywordEnabled,
                onCheckedChange = onToggleRoutineKeyword
            )
            setupSteps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun openDndPolicySettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    runCatching { context.startActivity(intent) }
}
