package com.dataproxy.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.network.CellularTechMonitor
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.proxy.ConnectionRegistry
import com.dataproxy.proxy.SpeedSampler
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)

    private val _bindAddress = MutableStateFlow(
        prefs.getString(KEY_BIND_ADDRESS, "0.0.0.0") ?: "0.0.0.0"
    )
    val bindAddress: StateFlow<String> = _bindAddress.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, ProxyService.DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _interfaces = MutableStateFlow<List<NetworkInterfaceLister.Candidate>>(emptyList())
    val interfaces: StateFlow<List<NetworkInterfaceLister.Candidate>> = _interfaces.asStateFlow()

    private val _serviceState = MutableStateFlow<ProxyService.State>(ProxyService.State.Stopped)
    val serviceState: StateFlow<ProxyService.State> = _serviceState.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectionRegistry.DeviceSummary>>(emptyList())
    val devices: StateFlow<List<ConnectionRegistry.DeviceSummary>> = _devices.asStateFlow()

    private val _totals = MutableStateFlow(ConnectionRegistry.Totals(0L, 0L, 0))
    val totals: StateFlow<ConnectionRegistry.Totals> = _totals.asStateFlow()

    private val _rates = MutableStateFlow(SpeedSampler.Rates(0L, 0L))
    val rates: StateFlow<SpeedSampler.Rates> = _rates.asStateFlow()

    private val _cellular =
        MutableStateFlow<CellularNetworkProvider.State>(CellularNetworkProvider.State.Idle)
    val cellular: StateFlow<CellularNetworkProvider.State> = _cellular.asStateFlow()

    private val techMonitor = CellularTechMonitor(app).also { it.start(viewModelScope) }
    val cellularTech: StateFlow<CellularTechMonitor.TechState> = techMonitor.state

    private val _authEnabled = MutableStateFlow(
        prefs.getBoolean(ProxyService.PREF_AUTH_ENABLED, false)
    )
    val authEnabled: StateFlow<Boolean> = _authEnabled.asStateFlow()

    private val _authUsername = MutableStateFlow(
        prefs.getString(ProxyService.PREF_AUTH_USERNAME, "") ?: ""
    )
    val authUsername: StateFlow<String> = _authUsername.asStateFlow()

    private val _authPassword = MutableStateFlow(
        prefs.getString(ProxyService.PREF_AUTH_PASSWORD, "") ?: ""
    )
    val authPassword: StateFlow<String> = _authPassword.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private var bound: ProxyService? = null
    private val collectors = mutableListOf<Job>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? ProxyService.LocalBinder)?.service ?: return
            bound = service
            mirror(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = null
            collectors.forEach { it.cancel() }
            collectors.clear()
        }
    }

    private fun mirror(service: ProxyService) {
        collectors.forEach { it.cancel() }
        collectors.clear()
        collectors += service.state.onEach { _serviceState.value = it }.launchIn(viewModelScope)
        collectors += service.devices.onEach { _devices.value = it }.launchIn(viewModelScope)
        collectors += service.totals.onEach { _totals.value = it }.launchIn(viewModelScope)
        collectors += service.rates.onEach { _rates.value = it }.launchIn(viewModelScope)
        collectors += service.cellularState.onEach { _cellular.value = it }.launchIn(viewModelScope)
    }

    fun bind() {
        val ctx = getApplication<Application>()
        ctx.bindService(
            Intent(ctx, ProxyService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        val ctx = getApplication<Application>()
        runCatching { ctx.unbindService(connection) }
        collectors.forEach { it.cancel() }
        collectors.clear()
        bound = null
    }

    fun refreshInterfaces() {
        _interfaces.value = NetworkInterfaceLister.list()
    }

    fun selectBindAddress(addr: String) {
        _bindAddress.value = addr
        prefs.edit().putString(KEY_BIND_ADDRESS, addr).apply()
    }

    fun selectPort(p: Int) {
        _port.value = p
        prefs.edit().putInt(KEY_PORT, p).apply()
    }

    fun setAuthEnabled(enabled: Boolean) {
        _authEnabled.value = enabled
        prefs.edit().putBoolean(ProxyService.PREF_AUTH_ENABLED, enabled).apply()
    }

    fun setAuthUsername(value: String) {
        _authUsername.value = value
        prefs.edit().putString(ProxyService.PREF_AUTH_USERNAME, value).apply()
    }

    fun setAuthPassword(value: String) {
        _authPassword.value = value
        prefs.edit().putString(ProxyService.PREF_AUTH_PASSWORD, value).apply()
    }

    fun cycleThemeMode() {
        val next = when (_themeMode.value) {
            ThemeMode.System -> ThemeMode.Light
            ThemeMode.Light -> ThemeMode.Dark
            ThemeMode.Dark -> ThemeMode.System
        }
        _themeMode.value = next
        prefs.edit().putString(KEY_THEME_MODE, next.key).apply()
    }

    fun start() {
        runCatching {
            val ctx = getApplication<Application>()
            val intent = ProxyService.startIntent(ctx, _bindAddress.value, _port.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    fun stop() {
        val ctx = getApplication<Application>()
        ctx.startService(ProxyService.stopIntent(ctx))
    }

    override fun onCleared() {
        super.onCleared()
        techMonitor.stop()
        unbind()
    }

    companion object {
        private const val KEY_BIND_ADDRESS = "bind_address"
        private const val KEY_PORT = "port"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
