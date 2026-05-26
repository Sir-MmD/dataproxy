package com.dataproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary

@Composable
fun ListenAddressCard(
    selected: String,
    port: Int,
    candidates: List<NetworkInterfaceLister.Candidate>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onPortChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Listen address",
        modifier = modifier,
        trailing = {
            IconButton(onClick = onRefresh, enabled = enabled) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh interfaces",
                    tint = if (enabled) TextSecondary else TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { cand ->
                AddressRow(
                    candidate = cand,
                    selected = cand.address == selected,
                    enabled = enabled,
                    onClick = { if (enabled) onSelect(cand.address) },
                )
            }
            if (candidates.isEmpty()) {
                Text(
                    text = "No interfaces enumerated",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            PortField(
                port = port,
                enabled = enabled,
                onChange = onPortChange,
            )
        }
    }
}

@Composable
private fun AddressRow(
    candidate: NetworkInterfaceLister.Candidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) Accent else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.08f) else SurfaceMid)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            candidate.isWildcard -> Icons.Rounded.SettingsEthernet
            candidate.isWifi -> Icons.Rounded.Wifi
            else -> Icons.Rounded.Router
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Accent else TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.address,
                color = if (selected) TextPrimary else TextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
            )
            Text(
                text = candidate.label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (selected) {
            Pill(text = "active", color = Accent)
        }
    }
}

@Composable
private fun PortField(
    port: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(port.toString()) }
    LaunchedEffect(port) { text = port.toString() }

    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onChange)
        },
        label = { Text("Port") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = TextPrimary,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = OutlineStrong,
            disabledBorderColor = OutlineSoft,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextSecondary,
            cursorColor = Accent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextMuted,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
