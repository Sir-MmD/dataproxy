package com.dataproxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.Ink
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning
import com.dataproxy.ui.viewmodel.MainViewModel

enum class Tab { Home, ListenAddress, Devices }

@Composable
fun AppNav(
    viewModel: MainViewModel,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    onToggle: () -> Unit,
    showBattDialog: Boolean,
    onDismissBattDialog: () -> Unit,
    onAllowBatt: () -> Unit,
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
                )
                Tab.ListenAddress -> ListenAddressScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.Home) },
                )
                Tab.Devices -> DevicesScreen(
                    viewModel = viewModel,
                    onBack = { onTabChange(Tab.Home) },
                )
            }
        }
    }

    if (showBattDialog) {
        AlertDialog(
            onDismissRequest = onDismissBattDialog,
            containerColor = SurfaceLow,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Keep DataProxy alive", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Android can suspend background services to save battery. " +
                        "Allow DataProxy to run unrestricted so the proxy stays up " +
                        "with the screen off."
                )
            },
            confirmButton = {
                TextButton(onClick = onAllowBatt) {
                    Text("Allow", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBattDialog) {
                    Text("Later", color = TextSecondary)
                }
            },
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
                        "Turn on mobile data — you can keep Wi-Fi on too — and " +
                        "the proxy will start when you return."
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
