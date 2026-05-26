package com.dataproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.components.Pill
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun ListenAddressScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val candidates by viewModel.interfaces.collectAsStateWithLifecycle()
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()

    val canEdit = when (serviceState) {
        is ProxyService.State.Stopped, is ProxyService.State.Error -> true
        else -> false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        TopBar(
            title = "Listen address",
            onBack = onBack,
            action = {
                IconButton(onClick = { viewModel.refreshInterfaces() }, enabled = canEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh",
                        tint = if (canEdit) TextSecondary else TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        if (!canEdit) {
            HintBanner(
                "Stop the proxy to change the listen address or port."
            )
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            candidates.forEach { cand ->
                AddressRow(
                    candidate = cand,
                    selected = cand.address == bindAddress,
                    enabled = canEdit,
                    onClick = { if (canEdit) viewModel.selectBindAddress(cand.address) },
                )
            }
            if (candidates.isEmpty()) {
                Text(
                    text = "No interfaces enumerated",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        PortField(
            port = port,
            enabled = canEdit,
            onChange = viewModel::selectPort,
        )
    }
}

@Composable
internal fun TopBar(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            ),
            modifier = Modifier.weight(1f),
        )
        if (action != null) action()
    }
}

@Composable
internal fun HintBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AddressRow(
    candidate: NetworkInterfaceLister.Candidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = 0.08f) else SurfaceMid)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
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
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.address,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
            )
            Text(
                text = candidate.label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (selected) Pill(text = "active", color = Accent)
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
