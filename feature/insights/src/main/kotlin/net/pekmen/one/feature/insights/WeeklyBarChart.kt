package net.pekmen.one.feature.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.model.DailyFocusSummary
import kotlin.math.max

@Composable
fun WeeklyBarChart(
    days: List<DailyFocusSummary>,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) return

    val focusColor = MaterialTheme.colorScheme.primary
    val breakColor = MaterialTheme.colorScheme.secondary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val maxTotalSeconds = max(
        days.maxOf { it.netFocusSeconds + it.breakSeconds },
        3_600L
    )
    val context = LocalContext.current
    val description = stringResource(R.string.insights_chart_cd)
    val values = days.joinToString(". ") { day ->
        context.getString(
            R.string.insights_chart_day,
            PekmenFormatters.formatMediumDate(day.date),
            PekmenFormatters.formatHumanDuration(context, day.netFocusSeconds),
            PekmenFormatters.formatHumanDuration(context, day.breakSeconds)
        )
    }
    val chartCd = "$description. $values"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = chartCd },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
        ) {
            val count = days.size
            val slotWidth = size.width / count.toFloat()
            val barWidth = (slotWidth * 0.44f).coerceAtMost(24.dp.toPx())
            val radius = CornerRadius(6.dp.toPx(), 6.dp.toPx())

            days.forEachIndexed { index, summary ->
                val centerX = slotWidth * index + slotWidth / 2f
                val left = centerX - barWidth / 2f

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = radius
                )

                val focusRatio = summary.netFocusSeconds.toFloat() / maxTotalSeconds.toFloat()
                val breakRatio = summary.breakSeconds.toFloat() / maxTotalSeconds.toFloat()
                val focusHeight = (size.height * focusRatio).coerceAtLeast(
                    if (summary.netFocusSeconds > 0L) 4.dp.toPx() else 0f
                )
                val breakHeight = (size.height * breakRatio).coerceAtMost(size.height - focusHeight)

                if (breakHeight > 0f) {
                    drawRoundRect(
                        color = breakColor,
                        topLeft = Offset(left, size.height - focusHeight - breakHeight),
                        size = Size(barWidth, focusHeight + breakHeight),
                        cornerRadius = radius
                    )
                }
                if (focusHeight > 0f) {
                    drawRoundRect(
                        color = focusColor,
                        topLeft = Offset(left, size.height - focusHeight),
                        size = Size(barWidth, focusHeight),
                        cornerRadius = radius
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            days.forEach { day ->
                Text(
                    text = PekmenFormatters.formatShortWeekday(day.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
