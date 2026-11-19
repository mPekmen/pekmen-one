package net.pekmen.one.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.designsystem.R
import net.pekmen.one.core.designsystem.component.PekmenAdaptiveScaffold
import net.pekmen.one.core.designsystem.component.PekmenNavDestination
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.icon.PekmenIcons
import net.pekmen.one.core.designsystem.theme.PekmenOneTheme
import net.pekmen.one.core.designsystem.theme.TabularNumberTextStyle

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "English", locale = "en", showBackground = true)
@Preview(name = "Turkish", locale = "tr", showBackground = true)
annotation class PekmenLocalePreviews

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
annotation class PekmenThemePreviews

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "Phone", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "Tablet Expanded", widthDp = 840, heightDp = 640, showBackground = true)
@Preview(name = "Large Font", widthDp = 360, fontScale = 1.5f, showBackground = true)
annotation class PekmenDevicePreviews

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "EN Light",
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    showBackground = true
)
@Preview(
    name = "EN Dark",
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true
)
@Preview(
    name = "TR Light",
    locale = "tr",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    showBackground = true
)
@Preview(
    name = "TR Dark",
    locale = "tr",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true
)
@Preview(
    name = "Tablet 840dp",
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 840,
    heightDp = 640,
    showBackground = true
)
@Preview(
    name = "Large Font 1.5x",
    locale = "tr",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 360,
    fontScale = 1.5f,
    showBackground = true
)
annotation class PekmenMultiConfigPreviews

@PekmenMultiConfigPreviews
@Suppress("UnusedPrivateMember")
@Composable
private fun PekmenAdaptiveScaffoldPreview() {
    val destinations = listOf(
        PekmenNavDestination(id = "focus", labelRes = R.string.nav_focus, iconRes = PekmenIcons.Focus),
        PekmenNavDestination(id = "learning", labelRes = R.string.nav_learning, iconRes = PekmenIcons.Book),
        PekmenNavDestination(id = "planner", labelRes = R.string.nav_planner, iconRes = PekmenIcons.Calendar),
        PekmenNavDestination(id = "insights", labelRes = R.string.nav_insights, iconRes = PekmenIcons.Insights),
        PekmenNavDestination(id = "settings", labelRes = R.string.nav_settings, iconRes = PekmenIcons.Settings)
    )
    val useNavigationRail = LocalConfiguration.current.screenWidthDp >= 600

    PekmenOneTheme {
        PekmenAdaptiveScaffold(
            destinations = destinations,
            selectedDestinationId = "focus",
            onSelectDestination = {},
            useNavigationRail = useNavigationRail
        ) {
            PekmenTokensSampleContent()
        }
    }
}

@PekmenThemePreviews
@PekmenLocalePreviews
@PekmenDevicePreviews
@Suppress("UnusedPrivateMember")
@Composable
private fun PekmenDesignTokensPreview() {
    PekmenOneTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            PekmenTokensSampleContent()
        }
    }
}

@Composable
private fun PekmenTokensSampleContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_focus),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(PekmenIcons.Shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.profile_study),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = PekmenFormatters.formatClock(totalSeconds = 1500L),
                    style = MaterialTheme.typography.displayMedium.merge(TabularNumberTextStyle),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.mode_pomodoro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}) {
                        Text(text = stringResource(R.string.action_save))
                    }
                    OutlinedButton(onClick = {}) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}
