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
import com.dataproxy.network.CellularTechMonitor
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.screens.AppNav
import com.dataproxy.ui.screens.Tab
import com.dataproxy.ui.theme.DataProxyTheme
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.AntiKillPreferences
import com.dataproxy.util.AntiKillStep
import com.dataproxy.util.BatteryOptimizationHelper
import com.dataproxy.util.OemHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Each launcher is fired independently from a per-item "Allow" button in
    // the perms dialog. No more auto-chaining — the user explicitly grants
    // (or skips) one at a time. The result callbacks just no-op; the Compose
    // state poller in setContent picks up the new permission state.
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* state poll picks it up */ }

    private val batteryOptResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* state poll picks it up */ }

    private val phonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* state poll picks it up */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.refreshInterfaces()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            DataProxyTheme(themeMode = themeMode) {
                var tab by rememberSaveable { mutableStateOf(Tab.Home) }
                var showPermsDialog by remember { mutableStateOf(false) }
                // When true, dismissing the perms dialog also kicks off the
                // proxy. Header re-grant clicks set this to false so the
                // dialog is purely informational in that context.
                var permsDialogStartsProxy by remember { mutableStateOf(false) }
                var showMobileDataDialog by remember { mutableStateOf(false) }

                val notifApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                val phoneApplicable = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                var notifGranted by remember { mutableStateOf(!needsNotifPermission()) }
                var battGranted by remember {
                    mutableStateOf(BatteryOptimizationHelper.isIgnoring(this))
                }
                var phoneGranted by remember { mutableStateOf(!needsPhonePermission()) }
                // OEM survival steps — not system-detectable, so we surface the
                // user's persisted "I've done this" flag (also editable on the
                // Anti-Kill screen). Shown in the perms dialog alongside the
                // auto-detectable ones.
                var autoStartDone by remember {
                    mutableStateOf(AntiKillPreferences.stepDone(this, AntiKillStep.AutoStart))
                }
                var backgroundDone by remember {
                    mutableStateOf(AntiKillPreferences.stepDone(this, AntiKillStep.BackgroundActivity))
                }
                var lockRecentsDone by remember {
                    mutableStateOf(AntiKillPreferences.stepDone(this, AntiKillStep.LockInRecents))
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        notifGranted = !needsNotifPermission()
                        battGranted = BatteryOptimizationHelper.isIgnoring(this@MainActivity)
                        phoneGranted = !needsPhonePermission()
                        autoStartDone = AntiKillPreferences.stepDone(this@MainActivity, AntiKillStep.AutoStart)
                        backgroundDone = AntiKillPreferences.stepDone(this@MainActivity, AntiKillStep.BackgroundActivity)
                        lockRecentsDone = AntiKillPreferences.stepDone(this@MainActivity, AntiKillStep.LockInRecents)
                        delay(1500)
                    }
                }

                // Safety net for the case where data toggles off between the
                // power-tap check and cellular handshake — the service emits
                // MobileDataUnavailable after the 15 s wait and we surface it
                // the same way as the pre-start check.
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
                    onToggle = {
                        onPowerToggle(
                            showPerms = {
                                permsDialogStartsProxy = true
                                showPermsDialog = true
                            },
                            showMobileData = { showMobileDataDialog = true },
                        )
                    },
                    onHeaderClick = {
                        // Re-prompt opportunity: only on pre-33 devices where
                        // the optional READ_PHONE_STATE may be missing. On
                        // 33+ the normal perm is always granted so nothing to
                        // do — clicks are silent.
                        if (phoneApplicable && !phoneGranted) {
                            permsDialogStartsProxy = false
                            showPermsDialog = true
                        }
                    },
                    themeMode = themeMode,
                    onCycleTheme = { viewModel.cycleThemeMode() },
                    showPermsDialog = showPermsDialog,
                    notifApplicable = notifApplicable,
                    notifGranted = notifGranted,
                    battGranted = battGranted,
                    phoneApplicable = phoneApplicable,
                    phoneGranted = phoneGranted,
                    onDismissPermsDialog = {
                        val wasForStart = permsDialogStartsProxy
                        showPermsDialog = false
                        permsDialogStartsProxy = false
                        if (wasForStart) actuallyStart()
                    },
                    onAllowNotif = { requestNotifPermission() },
                    onAllowBatt = { requestBatteryOptIgnore() },
                    onAllowPhone = { requestPhonePermission() },
                    autoStartDone = autoStartDone,
                    backgroundDone = backgroundDone,
                    lockRecentsDone = lockRecentsDone,
                    onOpenAutoStart = {
                        OemHelper.openAutoStart(this)
                        AntiKillPreferences.setStepDone(this, AntiKillStep.AutoStart, true)
                        autoStartDone = true
                    },
                    onOpenBackground = {
                        OemHelper.openBackgroundActivity(this)
                        AntiKillPreferences.setStepDone(this, AntiKillStep.BackgroundActivity, true)
                        backgroundDone = true
                    },
                    onOpenLockRecents = {
                        OemHelper.openLockInRecentsGuide(this)
                        AntiKillPreferences.setStepDone(this, AntiKillStep.LockInRecents, true)
                        lockRecentsDone = true
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
     * Power-button tap. Stops if running; otherwise gates start on mobile-data
     * availability first, then on perms. Mobile-data check happens *only* on
     * start (not as a passive banner) — that's where the user asked for it.
     */
    private fun onPowerToggle(showPerms: () -> Unit, showMobileData: () -> Unit) {
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
        if (viewModel.cellularTech.value is CellularTechMonitor.TechState.DataOff) {
            showMobileData()
            return
        }
        if (needsNotifPermission() ||
            !BatteryOptimizationHelper.isIgnoring(this) ||
            needsPhonePermission()
        ) {
            showPerms()
            return
        }
        actuallyStart()
    }

    private fun requestNotifPermission() {
        runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private fun requestBatteryOptIgnore() {
        val direct = BatteryOptimizationHelper.requestIntent(this)
        val intent = if (direct.resolveActivity(packageManager) != null) direct
        else BatteryOptimizationHelper.settingsIntent()
        runCatching { batteryOptResult.launch(intent) }
    }

    private fun requestPhonePermission() {
        runCatching { phonePermission.launch(Manifest.permission.READ_PHONE_STATE) }
    }

    private fun actuallyStart() {
        runCatching { viewModel.start() }
        lifecycleScope.launch {
            delay(150)
            runCatching { viewModel.bind() }
        }
    }

    private fun needsNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Only true on pre-33 devices: API 33+ uses READ_BASIC_PHONE_STATE (a
     * normal install-time perm that's auto-granted), so we never prompt
     * modern users. Below that, getDataNetworkType() needs the runtime
     * READ_PHONE_STATE — optional for the proxy itself, used by the header
     * to show the real radio tech (2G/3G/4G/5G + operator).
     */
    private fun needsPhonePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE,
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
