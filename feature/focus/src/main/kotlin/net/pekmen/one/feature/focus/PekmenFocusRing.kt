package net.pekmen.one.feature.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.pekmen.one.core.designsystem.format.PekmenFormatters
import net.pekmen.one.core.designsystem.theme.TabularNumberTextStyle
import net.pekmen.one.core.model.FocusPhase

@Composable
fun PekmenFocusRing(
    displaySeconds: Long,
    progressFraction: Float,
    phase: FocusPhase,
    isTargetReached: Boolean,
    isPomodoro: Boolean,
    completedCycles: Int,
    totalCycles: Int,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val secondaryAccent = MaterialTheme.colorScheme.secondary
    val ringColor = if (phase == FocusPhase.FOCUS) accentColor else secondaryAccent
    val ringBrush = Brush.sweepGradient(
        colors = listOf(ringColor.copy(alpha = 0.7f), ringColor, ringColor.copy(alpha = 0.9f))
    )

    val phaseText = when {
        isTargetReached -> stringResource(R.string.focus_phase_complete_ready)
        phase == FocusPhase.FOCUS -> stringResource(R.string.focus_phase_focus)
        phase == FocusPhase.SHORT_BREAK -> stringResource(R.string.focus_phase_short_break)
        else -> stringResource(R.string.focus_phase_long_break)
    }

    val clockText = PekmenFormatters.formatClock(displaySeconds)
    val cycleText = if (isPomodoro && totalCycles > 0) {
        stringResource(
            R.string.focus_cycle_progress,
            PekmenFormatters.formatInteger((completedCycles % totalCycles) + 1),
            PekmenFormatters.formatInteger(totalCycles)
        )
    } else {
        null
    }

    Box(
        modifier = modifier
            .size(228.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$phaseText $clockText"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(216.dp)) {
            val strokeWidth = 14.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            val sweep = (progressFraction.coerceIn(0f, 1f)) * 360f
            if (sweep > 0f) {
                drawArc(
                    brush = ringBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = phaseText,
                style = MaterialTheme.typography.labelLarge,
                color = ringColor
            )
            Text(
                text = clockText,
                style = TabularNumberTextStyle.copy(
                    fontSize = MaterialTheme.typography.displayLarge.fontSize,
                    lineHeight = MaterialTheme.typography.displayLarge.lineHeight
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            if (isPomodoro && totalCycles > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeDots = completedCycles % totalCycles
                    repeat(totalCycles) { index ->
                        val filled = index < activeDots
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = if (filled) ringColor else trackColor
                        ) {}
                    }
                }
                cycleText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
