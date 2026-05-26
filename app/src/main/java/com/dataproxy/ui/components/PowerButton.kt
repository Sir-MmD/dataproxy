package com.dataproxy.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.AccentDim
import com.dataproxy.ui.theme.AccentGlow
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextSecondary

enum class PowerState { Off, Starting, On, Error }

@Composable
fun PowerButton(
    state: PowerState,
    statusLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (state) {
        PowerState.On -> Accent
        PowerState.Starting -> Accent.copy(alpha = 0.6f)
        PowerState.Error -> Danger
        PowerState.Off -> TextMuted
    }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )
    val spinPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin-phase",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (state == PowerState.On) AccentGlow else Color.Transparent,
                            Color.Transparent,
                        )
                    )
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(168.dp)) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2f
                val arcSize = androidx.compose.ui.geometry.Size(
                    size.width - inset * 2,
                    size.height - inset * 2,
                )
                val topLeft = Offset(inset, inset)
                // base ring
                drawArc(
                    color = OutlineSoft,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                when (state) {
                    PowerState.On -> {
                        drawArc(
                            color = accent.copy(alpha = pulse),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    PowerState.Starting -> {
                        drawArc(
                            color = accent,
                            startAngle = spinPhase,
                            sweepAngle = 110f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    PowerState.Error -> {
                        drawArc(
                            color = Danger,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(
                                width = stroke,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(
                                    intervals = floatArrayOf(20f, 14f),
                                ),
                            ),
                        )
                    }
                    PowerState.Off -> {
                        drawArc(
                            color = AccentDim.copy(alpha = 0.35f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(
                                width = stroke,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(
                                    intervals = floatArrayOf(4f, 14f),
                                ),
                            ),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(SurfaceLow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Power",
                    tint = accent,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = statusLabel.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (state) {
                PowerState.On -> "tap to disable"
                PowerState.Off -> "tap to enable"
                PowerState.Starting -> "establishing tunnel..."
                PowerState.Error -> "tap to retry"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}
