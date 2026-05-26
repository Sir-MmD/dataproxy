package com.dataproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.screens.AppNav
import com.dataproxy.ui.screens.Tab
import com.dataproxy.ui.theme.DataProxyTheme
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.BatteryOptimizationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** True while walking through the system perm prompts triggered by the dialog. */
    private var awaitingPermsChain = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* grant or deny — either way, continue the chain */
        if (awaitingPermsChain) continuePermsChain()
    }

    private val batteryOptResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (awaitingPermsChain) continuePermsChain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.refreshInterfaces()

        setContent {
            DataProxyTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.Home) }
                var showPermsDialog by remember { mutableStateOf(false) }
                var showMobileDataDialog by remember { mutableStateOf(false) }

                // Recompute perm flags periodically so the dialog reflects truth
                // after the user comes back from system settings.
                var needNotif by remember { mutableStateOf(needsNotifPermission()) }
                var needBatt by remember {
                    mutableStateOf(!BatteryOptimizationHelper.isIgnoring(this))
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        needNotif = needsNotifPermission()
                        needBatt = !BatteryOptimizationHelper.isIgnoring(this@MainActivity)
                        delay(1500)
                    }
                }

                // Surface mobile-data dialog when the service hits the matching
                // error kind. One-shot per error transition.
                val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
                LaunchedEffect(serviceState) {
                    val s = serviceState
                    if (s is ProxyService.State.Error &&
                        s.kind == ProxyService.State.ErrorKind.MobileDataUnavailable
                    ) {
                        showMobileDataDialog = true
                    }
                }

                AppNav(
                    viewModel = viewModel,
                    tab = tab,
                    onTabChange = { tab = it },
                    onToggle = { onPowerToggle { showPermsDialog = true } },
                    showPermsDialog = showPermsDialog,
                    needNotif = needNotif,
                    needBatt = needBatt,
                    onDismissPermsDialog = { showPermsDialog = false },
                    onAllowPerms = {
                        showPermsDialog = false
                        startPermsChain()
                    },
                    showMobileDataDialog = showMobileDataDialog,
                    onDismissMobileDataDialog = { showMobileDataDialog = false },
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

    override fun onStop() {
        super.onStop()
        viewModel.unbind()
    }

    /**
     * Called whenever the user taps the power button. Either toggles the proxy
     * off, or — if perms are missing — defers to [showDialog] before starting.
     */
    private fun onPowerToggle(showDialog: () -> Unit) {
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
        if (needsNotifPermission() || !BatteryOptimizationHelper.isIgnoring(this)) {
            showDialog()
            return
        }
        actuallyStart()
    }

    private fun startPermsChain() {
        awaitingPermsChain = true
        continuePermsChain()
    }

    /**
     * Walks one system prompt at a time. Re-enters from the activity-result
     * callbacks until both perms have been either granted or declined, then
     * starts the proxy.
     */
    private fun continuePermsChain() {
        when {
            needsNotifPermission() -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            !BatteryOptimizationHelper.isIgnoring(this) -> {
                val direct = BatteryOptimizationHelper.requestIntent(this)
                val resolved = direct.resolveActivity(packageManager)
                val intent = if (resolved != null) direct
                else BatteryOptimizationHelper.settingsIntent()
                runCatching { batteryOptResult.launch(intent) }
                    .onFailure { awaitingPermsChain = false; actuallyStart() }
            }
            else -> {
                awaitingPermsChain = false
                actuallyStart()
            }
        }
    }

    private fun actuallyStart() {
        viewModel.start()
        lifecycleScope.launch {
            delay(150)
            viewModel.bind()
        }
    }

    private fun needsNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
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
