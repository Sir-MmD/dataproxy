package com.dataproxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Info
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.util.ByteFormatter
import com.dataproxy.util.RateUnit
import kotlin.math.ln

@Composable
fun SpeedometerCard(
    upBps: Long,
    downBps: Long,
    rateUnit: RateUnit,
    onCycleRateUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Proxy speed",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedTile(
                label = "Download",
                icon = Icons.Rounded.ArrowDownward,
                bps = downBps,
                color = Info,
                rateUnit = rateUnit,
                onUnitClick = onCycleRateUnit,
                modifier = Modifier.weight(1f),
            )
            SpeedTile(
                label = "Upload",
                icon = Icons.Rounded.ArrowUpward,
                bps = upBps,
                color = Accent,
                rateUnit = rateUnit,
                onUnitClick = onCycleRateUnit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SpeedTile(
    label: String,
    icon: ImageVector,
    bps: Long,
    color: Color,
    rateUnit: RateUnit,
    onUnitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayValue = remember(bps, rateUnit) { ByteFormatter.rate(bps, rateUnit) }
    val animated by animateFloatAsState(
        targetValue = normaliseBps(bps),
        animationSpec = tween(600),
        label = "speed-bar",
    )
    val outlineSoftColor = OutlineSoft
    Column(
        modifier = modifier.padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        // The whole number+unit row cycles the rate unit, not just the unit
        // glyph: a labelMedium "MB/s" is roughly 30x20dp, well under the 48dp
        // touch-target guideline, and Home's layout has no vertical room to
        // pad it up to size. Taking the row instead buys the 28sp number's
        // height for free and makes the obvious thing tappable, with no
        // change to layout or spacing.
        Row(
            verticalAlignment = Alignment.Bottom,
            // Clip before clickable so the ripple follows the rounded shape
            // instead of painting a hard rectangle over the digits. Clipping
            // does not affect measurement, so Home's height is unchanged.
            modifier = Modifier
                // Merge so TalkBack reads the rate itself as the button's
                // label; onClickLabel names the action, not the node.
                .semantics(mergeDescendants = true) {}
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    onClick = onUnitClick,
                    role = Role.Button,
                    onClickLabel = "Change rate unit",
                ),
        ) {
            Text(
                text = displayValue.first,
                color = TextPrimary,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    fontSize = 28.sp,
                    letterSpacing = (-0.5).sp,
                ),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = displayValue.second,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                val baseY = size.height / 2
                drawLine(
                    color = outlineSoftColor,
                    start = Offset(0f, baseY),
                    end = Offset(size.width, baseY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(0f, baseY),
                    end = Offset(size.width * animated, baseY),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

// Log-scale so a 1 MB/s peak doesn't squash all 100KB readings to 0.
private fun normaliseBps(bps: Long): Float {
    if (bps <= 0) return 0f
    // 100 B/s → ~0.1, 1 KB/s → ~0.25, 1 MB/s → ~0.66, 100 MB/s → 1.0
    val ceiling = 100.0 * 1024 * 1024
    val value = bps.toDouble().coerceAtMost(ceiling)
    val score = ln(value + 1) / ln(ceiling + 1)
    return score.toFloat().coerceIn(0f, 1f)
}
