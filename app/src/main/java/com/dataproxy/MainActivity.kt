package com.dataproxy

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.screens.AppNav
import com.dataproxy.ui.screens.Tab
import com.dataproxy.ui.theme.DataProxyTheme
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.BatteryOptimizationHelper
import com.dataproxy.util.CellularAvailability
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op; service still runs without it */ }

    private val batteryOptResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* state is re-read each onResume */ }

    private var pendingStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        viewModel.refreshInterfaces()

        setContent {
            DataProxyTheme {
                var battOk by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoring(this)) }
                var showBattDialog by rememberSaveable { mutableStateOf(false) }
                var showMobileDataDialog by remember { mutableStateOf(false) }
                var tab by rememberSaveable { mutableStateOf(Tab.Home) }

                // Re-check battery opt periodically — user may change it while we're foregrounded.
                LaunchedEffect(Unit) {
                    while (true) {
                        val before = battOk
                        battOk = BatteryOptimizationHelper.isIgnoring(this@MainActivity)
                        // Prompt once per launch if still not granted (give user 2s to see UI).
                        if (!battOk && !showBattDialog && before == battOk) {
                            // first-tick: only prompt once
                        }
                        delay(2000)
                    }
                }
                // Show the battery prompt 1s after the UI lands on first launch.
                LaunchedEffect(battOk) {
                    if (!battOk) {
                        delay(800)
                        if (!BatteryOptimizationHelper.isIgnoring(this@MainActivity)) {
                            showBattDialog = true
                        }
                    }
                }

                AppNav(
                    viewModel = viewModel,
                    tab = tab,
                    onTabChange = { tab = it },
                    onToggle = { onPowerToggle { showMobileDataDialog = true } },
                    showBattDialog = showBattDialog,
                    onDismissBattDialog = { showBattDialog = false },
                    onAllowBatt = {
                        showBattDialog = false
                        requestDisableBatteryOptimization()
                    },
                    showMobileDataDialog = showMobileDataDialog,
                    onDismissMobileDataDialog = { showMobileDataDialog = false; pendingStart = false },
                    onOpenMobileDataSettings = {
                        showMobileDataDialog = false
                        openMobileDataSettings()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bind()
    }

    override fun onResume() {
        super.onResume()
        // User may have just enabled data via the settings shortcut.
        if (pendingStart && CellularAvailability.isReachable(this)) {
            pendingStart = false
            actuallyStart()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbind()
    }

    private fun onPowerToggle(onMobileDataMissing: () -> Unit) {
        val running = when (viewModel.serviceState.value) {
            is ProxyService.State.Running,
            is ProxyService.State.Starting,
            is ProxyService.State.Paused -> true
            else -> false
        }
        if (running) {
            viewModel.stop()
            return
        }
        // Pre-flight: is mobile data actually on?
        if (!CellularAvailability.isReachable(this)) {
            pendingStart = true
            onMobileDataMissing()
            return
        }
        actuallyStart()
    }

    private fun actuallyStart() {
        viewModel.start()
        lifecycleScope.launch {
            delay(150)
            viewModel.bind()
        }
    }

    private fun requestDisableBatteryOptimization() {
        val direct = BatteryOptimizationHelper.requestIntent(this)
        val resolved = direct.resolveActivity(packageManager)
        try {
            batteryOptResult.launch(
                if (resolved != null) direct else BatteryOptimizationHelper.settingsIntent()
            )
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun openMobileDataSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_DATA_USAGE_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) != null) {
                runCatching { startActivity(i) }
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
