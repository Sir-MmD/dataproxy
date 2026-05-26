package com.dataproxy.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.components.ConnectionsCard
import com.dataproxy.ui.components.ListenAddressCard
import com.dataproxy.ui.components.PowerButton
import com.dataproxy.ui.components.PowerState
import com.dataproxy.ui.components.SpeedometerCard
import com.dataproxy.ui.components.StatusDot
import com.dataproxy.ui.components.TrafficStatsCard
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.Ink
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    batteryOptimizationIgnored: Boolean,
    onRequestDisableBatteryOptimization: () -> Unit,
    onToggle: () -> Unit,
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val cellular by viewModel.cellular.collectAsStateWithLifecycle()
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val interfaces by viewModel.interfaces.collectAsStateWithLifecycle()

    val powerState = when (serviceState) {
        is ProxyService.State.Running -> PowerState.On
        is ProxyService.State.Starting -> PowerState.Starting
        is ProxyService.State.Error -> PowerState.Error
        else -> PowerState.Off
    }
    val statusLabel = when (val s = serviceState) {
        is ProxyService.State.Running -> "Proxy online · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Starting -> "Starting on ${s.bindAddress}:${s.port}"
        is ProxyService.State.Error -> s.message
        else -> "Proxy offline"
    }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Header(cellular = cellular)
            Spacer(Modifier.height(24.dp))
            PowerButton(
                state = powerState,
                statusLabel = statusLabel,
                onClick = onToggle,
            )
            Spacer(Modifier.height(24.dp))
            if (!batteryOptimizationIgnored) {
                BatteryOptBanner(onClick = onRequestDisableBatteryOptimization)
                Spacer(Modifier.height(12.dp))
            }
            CellularBanner(cellular)
            SpeedometerCard(upBps = rates.upBps, downBps = rates.downBps)
            Spacer(Modifier.height(12.dp))
            TrafficStatsCard(
                bytesUp = totals.bytesUp,
                bytesDown = totals.bytesDown,
                activeConnections = totals.active,
            )
            Spacer(Modifier.height(12.dp))
            ListenAddressCard(
                selected = bindAddress,
                port = port,
                candidates = interfaces,
                enabled = powerState == PowerState.Off || powerState == PowerState.Error,
                onSelect = viewModel::selectBindAddress,
                onPortChange = viewModel::selectPort,
                onRefresh = viewModel::refreshInterfaces,
            )
            Spacer(Modifier.height(12.dp))
            ConnectionsCard(devices = devices)
            Spacer(Modifier.height(20.dp))
            Footer()
        }
    }
}

@Composable
private fun Header(cellular: CellularNetworkProvider.State) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Accent, Color(0xFF1E9C70))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DataProxy",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                ),
            )
            Text(
                text = "socks5 over cellular",
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, label) = when (cellular) {
                is CellularNetworkProvider.State.Available -> Accent to "LTE"
                CellularNetworkProvider.State.Requesting -> Warning to "WAIT"
                CellularNetworkProvider.State.Lost,
                CellularNetworkProvider.State.Unavailable -> Danger to "NONE"
                CellularNetworkProvider.State.Idle -> TextMuted to "IDLE"
            }
            StatusDot(color = dotColor)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun BatteryOptBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Warning.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.BatteryAlert,
            contentDescription = null,
            tint = Warning,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Disable battery optimization",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Android may kill the proxy in the background otherwise",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) {
            Text("Allow", color = Warning, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CellularBanner(cellular: CellularNetworkProvider.State) {
    val message = when (cellular) {
        is CellularNetworkProvider.State.Unavailable ->
            "Mobile data is unavailable. Egress will fail until cellular is reachable."
        CellularNetworkProvider.State.Lost ->
            "Lost cellular network. Trying to reconnect…"
        else -> null
    } ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Danger.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = Danger,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Footer() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceLow)
                .padding(14.dp),
        ) {
            Text(
                text = "All outbound traffic is bound to the cellular network. " +
                    "Clients on Wi-Fi connect via SOCKS5; egress goes through mobile data only.",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "DataProxy",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(TextMuted),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v0.1",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
