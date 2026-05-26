package com.dataproxy

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.screens.HomeScreen
import com.dataproxy.ui.theme.DataProxyTheme
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.BatteryOptimizationHelper
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
                // Re-check periodically — settings can change while we're foregrounded.
                LaunchedEffect(Unit) {
                    while (true) {
                        battOk = BatteryOptimizationHelper.isIgnoring(this@MainActivity)
                        delay(2000)
                    }
                }
                HomeScreen(
                    viewModel = viewModel,
                    batteryOptimizationIgnored = battOk,
                    onRequestDisableBatteryOptimization = ::requestDisableBatteryOptimization,
                    onToggle = ::toggleProxy,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.bind()
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbind()
    }

    private fun toggleProxy() {
        val running = when (viewModel.serviceState.value) {
            is ProxyService.State.Running, is ProxyService.State.Starting -> true
            else -> false
        }
        if (running) {
            viewModel.stop()
        } else {
            viewModel.start()
            // Bind shortly after start so we mirror live state from the service.
            lifecycleScope.launch {
                delay(150)
                viewModel.bind()
            }
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
            // last-ditch: open generic settings
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }
}
