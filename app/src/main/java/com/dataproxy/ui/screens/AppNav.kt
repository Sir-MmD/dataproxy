package com.dataproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Ink
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.ThemeMode
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel

enum class Tab { Home, ListenAddress, Devices, Auth }

@Composable
fun AppNav(
    viewModel: MainViewModel,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    onToggle: () -> Unit,
    onHeaderClick: () -> Unit,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    showPermsDialog: Boolean,
    notifApplicable: Boolean,
    notifGranted: Boolean,
    battGranted: Boolean,
    phoneApplicable: Boolean,
    phoneGranted: Boolean,
    onDismissPermsDialog: () -> Unit,
    onAllowNotif: () -> Unit,
    onAllowBatt: () -> Unit,
    onAllowPhone: () -> Unit,
    showMobileDataDialog: Boolean,
    onDismissMobileDataDialog: () -> Unit,
    onOpenMobileDataSettings: () -> Unit,
) {
    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Ink),
        ) {
            when (tab) {
                Tab.Home -> HomeScreen(
                    viewModel = viewModel,
                    onToggle = onToggle,
                    onOpenListen = { onTabChange(Tab.ListenAddress) },
                    onOpenDevices = { onTabChange(Tab.Devices) },
                    onOpenAuth = { onTabChange(Tab.Auth) },
                    themeMode = themeMode,
                    onCycleTheme = onCycleTheme,
                    onHeaderClick = onHeaderClick,
                )
                Tab.ListenAddress -> ListenAddressScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.Home) },
                )
                Tab.Devices -> DevicesScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.Home) },
                )
                Tab.Auth -> AuthScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.Home) },
                )
            }
        }
    }

    if (showPermsDialog) {
        PermissionsDialog(
            notifApplicable = notifApplicable,
            notifGranted = notifGranted,
            battGranted = battGranted,
            phoneApplicable = phoneApplicable,
            phoneGranted = phoneGranted,
            onAllowNotif = onAllowNotif,
            onAllowBatt = onAllowBatt,
            onAllowPhone = onAllowPhone,
            onDismiss = onDismissPermsDialog,
        )
    }

    if (showMobileDataDialog) {
        AlertDialog(
            onDismissRequest = onDismissMobileDataDialog,
            containerColor = SurfaceLow,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Mobile data is off", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "DataProxy routes traffic through cellular only. " +
                        "Turn on mobile data — Wi-Fi can stay on too — and tap the " +
                        "power button again."
                )
            },
            confirmButton = {
                TextButton(onClick = onOpenMobileDataSettings) {
                    Text("Open settings", color = Warning, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissMobileDataDialog) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun PermissionsDialog(
    notifApplicable: Boolean,
    notifGranted: Boolean,
    battGranted: Boolean,
    phoneApplicable: Boolean,
    phoneGranted: Boolean,
    onAllowNotif: () -> Unit,
    onAllowBatt: () -> Unit,
    onAllowPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Permissions", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    text = "Tap Allow next to each one. Granted permissions show a check.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                if (notifApplicable) {
                    PermItem(
                        icon = Icons.Rounded.Notifications,
                        title = "Notifications",
                        reason = "Shows the live proxy status. Required by Android so the " +
                            "system knows the service is doing real work and won't shut it down.",
                        granted = notifGranted,
                        onAllow = onAllowNotif,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                PermItem(
                    icon = Icons.Rounded.BatteryAlert,
                    title = "Ignore battery optimisation",
                    reason = "Keeps the proxy alive with the screen off. Without this, " +
                        "Android Doze suspends it after a few minutes and clients disconnect.",
                    granted = battGranted,
                    onAllow = onAllowBatt,
                )
                if (phoneApplicable) {
                    Spacer(Modifier.height(12.dp))
                    PermItem(
                        icon = Icons.Rounded.SignalCellularAlt,
                        title = "Phone state (optional)",
                        reason = "Lets the header show your live cellular tech (2G/3G/4G/5G) " +
                            "and operator. The proxy works fine without it.",
                        granted = phoneGranted,
                        onAllow = onAllowPhone,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun PermItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    reason: String,
    granted: Boolean,
    onAllow: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = reason,
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Granted",
                tint = Accent,
                modifier = Modifier.size(28.dp),
            )
        } else {
            TextButton(onClick = onAllow) {
                Text("Allow", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
