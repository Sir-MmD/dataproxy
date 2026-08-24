package com.dataproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Info
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import java.util.Locale

@Composable
fun TrafficStatsCard(
    bytesUp: Long,
    bytesDown: Long,
    activeConnections: Int,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Traffic usage",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TotalTile(
                label = "Downloaded",
                bytes = bytesDown,
                color = Info,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(modifier = Modifier.height(48.dp))
            TotalTile(
                label = "Uploaded",
                bytes = bytesUp,
                color = Accent,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(modifier = Modifier.height(48.dp))
            CountTile(
                label = "Active",
                count = activeConnections,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TotalTile(label: String, bytes: Long, color: Color, modifier: Modifier = Modifier) {
    val parts = humanise(bytes)
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = parts.first,
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                ),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = parts.second,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun CountTile(label: String, count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = count.toString(),
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            ),
        )
    }
}

private fun humanise(bytes: Long): Pair<String, String> {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    if (bytes <= 0) return "0" to "B"
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    val n = when {
        i == 0 -> bytes.toString()
        v >= 100 -> String.format(Locale.ROOT, "%.0f", v)
        v >= 10 -> String.format(Locale.ROOT, "%.1f", v)
        else -> String.format(Locale.ROOT, "%.2f", v)
    }
    return n to units[i]
}
