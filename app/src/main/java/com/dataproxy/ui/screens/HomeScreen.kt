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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.dataproxy.ui.components.PowerButton
import com.dataproxy.ui.components.PowerState
import com.dataproxy.ui.components.SpeedometerCard
import com.dataproxy.ui.components.StatusDot
import com.dataproxy.ui.components.TrafficStatsCard
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Danger
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onToggle: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val cellular by viewModel.cellular.collectAsStateWithLifecycle()
    val bindAddress by viewModel.bindAddress.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()

    val powerState = when (serviceState) {
        is ProxyService.State.Running -> PowerState.On
        is ProxyService.State.Starting -> PowerState.Starting
        is ProxyService.State.Paused -> PowerState.Paused
        is ProxyService.State.Error -> PowerState.Error
        else -> PowerState.Off
    }
    val statusLabel = when (val s = serviceState) {
        is ProxyService.State.Running -> "Online · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Starting -> "Starting on ${s.bindAddress}:${s.port}"
        is ProxyService.State.Paused -> "Paused · ${s.bindAddress}:${s.port}"
        is ProxyService.State.Error -> s.message.take(60)
        else -> "Proxy offline"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Header(cellular)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PowerButton(
                state = powerState,
                statusLabel = statusLabel,
                onClick = onToggle,
            )
        }
        Spacer(Modifier.height(14.dp))
        SpeedometerCard(upBps = rates.upBps, downBps = rates.downBps)
        Spacer(Modifier.height(10.dp))
        TrafficStatsCard(
            bytesUp = totals.bytesUp,
            bytesDown = totals.bytesDown,
            activeConnections = totals.active,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavTile(
                icon = Icons.Rounded.Router,
                title = "Listen",
                subtitle = "$bindAddress:$port",
                onClick = onOpenListen,
                modifier = Modifier.weight(1f),
            )
            NavTile(
                icon = Icons.Rounded.Devices,
                title = "Devices",
                subtitle = if (totals.active == 0) "no clients" else "${totals.active} active",
                onClick = onOpenDevices,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

@Composable
private fun Header(cellular: CellularNetworkProvider.State) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Accent, Color(0xFF1E9C70)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DataProxy",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            )
            Text(
                text = "socks5 over cellular",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
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

@Composable
private fun NavTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun Footer() {
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
                .clip(CircleShape)
                .background(TextMuted),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "v0.2.1",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}
