package com.dataproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dataproxy.MainActivity
import com.dataproxy.R
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.proxy.AuthConfig
import com.dataproxy.proxy.ConnectionRegistry
import com.dataproxy.proxy.Socks5Server
import com.dataproxy.proxy.SpeedSampler
import com.dataproxy.util.ByteFormatter
import com.dataproxy.util.RateUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Long-lived host for the SOCKS5 listener.
 *
 * Foreground because:
 * 1. We need to keep TCP accept loops + worker threads alive when the UI
 *    goes away.
 * 2. We hold a cellular [android.net.Network] reference, that gets revoked
 *    if the process drops out of the foreground importance bucket.
 *
 * UI binds with [LocalBinder] for state and start/stop control; everything
 * survives unbind because we [startForeground].
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Incremented by every [fullCleanup], i.e. on every start AND every stop.
     * Bumping only on start would leave a stop-with-no-restart exposed: a
     * stale onFatal would still match the current cycle and flip the UI from
     * Stopped to Error after the user deliberately stopped.
     *
     * A Socks5Server's accept loop can be
     * descheduled mid-teardown and resume after a stop+restart has already
     * completed; its onFatal would then tear down the server that replaced it,
     * leaving the UI in Error with a healthy proxy killed underneath. The
     * server's own latch narrows that to a single statement, this closes it,
     * because the check is on live service state rather than on the dead
     * server's fields.
     */
    private val cycleId = java.util.concurrent.atomic.AtomicLong(0L)

    private val cellular by lazy { CellularNetworkProvider(applicationContext) }
    private val registry = ConnectionRegistry()
    private val sampler = SpeedSampler()

    private var server: Socks5Server? = null
    private var startJob: Job? = null
    private var publishJob: Job? = null
    private var cellularWatchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()
    val devices: StateFlow<List<ConnectionRegistry.DeviceSummary>> = registry.devices
    val totals: StateFlow<ConnectionRegistry.Totals> = registry.totals
    val rates: StateFlow<SpeedSampler.Rates> = sampler.rates
    // Computed: defers cellular's by-lazy init until first read (post-onCreate).
    val cellularState: StateFlow<CellularNetworkProvider.State> get() = cellular.state

    sealed interface State {
        data object Stopped : State
        data class Starting(val bindAddress: String, val port: Int) : State
        data class Running(val bindAddress: String, val port: Int) : State
        /** Listener still bound; cellular link is gone, so outbound connects fail. */
        data class Paused(val bindAddress: String, val port: Int, val reason: String) : State
        data class Error(val message: String, val kind: ErrorKind = ErrorKind.Generic) : State

        enum class ErrorKind { Generic, MobileDataUnavailable, BindFailed }
    }

    inner class LocalBinder : Binder() {
        val service: ProxyService get() = this@ProxyService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val addr = intent.getStringExtra(EXTRA_BIND_ADDRESS) ?: "0.0.0.0"
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startProxy(addr, port)
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ----------------------------------------------------------------- control

    fun startProxy(bindAddress: String, port: Int) {
        if (_state.value is State.Running || _state.value is State.Starting) return

        // Clear-state + kill: wipe everything from any previous cycle before
        // we touch cellular again. Idempotent on a clean slate.
        fullCleanup()
        val myCycle = cycleId.get()

        _state.value = State.Starting(bindAddress, port)
        startForegroundNow(bindAddress, port)

        cellular.start()
        startJob = scope.launch {
            val net = cellular.awaitAvailable(15_000L)
            if (_state.value !is State.Starting) return@launch
            if (net == null) {
                _state.value = State.Error(
                    message = "Mobile data is unavailable. Turn it on to start the proxy.",
                    kind = State.ErrorKind.MobileDataUnavailable,
                )
                fullCleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }
            val srv = Socks5Server(
                bindAddress = bindAddress,
                port = port,
                cellular = cellular,
                registry = registry,
                onFatal = { e ->
                    if (cycleId.get() != myCycle) return@Socks5Server
                    // The listener can die after a successful bind (the accept
                    // loop giving up), so only report BindFailed when the bind
                    // is actually what failed, otherwise the UI blames the
                    // listen address for something unrelated.
                    _state.value = State.Error(
                        message = e.message ?: "Listener failed",
                        // Keyed on Starting, not Running: a bind failure is only
                        // observable while starting. Testing for Running would
                        // send the Paused case (cellular dropped, listener
                        // still bound, accept loop then dies) down the
                        // BindFailed branch and blame an address that bound
                        // successfully long ago.
                        kind = if (_state.value is State.Starting) State.ErrorKind.BindFailed
                        else State.ErrorKind.Generic,
                    )
                    fullCleanup()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                },
                authProvider = ::currentAuthConfig,
            )
            server = srv
            srv.start()
            // Everything from the staleness check above to here is
            // non-suspending, so cancelling startJob cannot stop it. A Stop
            // landing in that window ran fullCleanup() while srv.running was
            // still false, so server?.stop() early-returned and then nulled
            // the field, leaving a bound, accepting listener on the port with
            // nothing holding a reference to it. Only a force-stop cleared it,
            // and tapping Stop during the 15s cellular wait is the ordinary
            // way to hit it. Re-check the cycle now that the outcome is known.
            if (cycleId.get() != myCycle) {
                // Deliberately does NOT null `server`: this coroutine is the
                // stale one, so a write here could land after a successor has
                // already stored its own instance and would orphan it,
                // exactly the bug being fixed. The next fullCleanup() stops
                // this (a no-op once stopped) and clears the field safely.
                srv.stop()
                return@launch
            }
            if (srv.running) {
                _state.value = State.Running(bindAddress, port)
                acquireWakeLock()
                startSampling()
                startCellularWatch(bindAddress, port)
                updateNotification(bindAddress, port, totals.value, rates.value)
            }
        }
    }

    fun stopProxy() {
        _state.value = State.Stopped
        fullCleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Cancel every coroutine, close the server, unregister the cellular
     * callback, drop the process network binding, and reset counters. Called
     * on both start (to wipe leftover state) and stop. Idempotent.
     */
    private fun fullCleanup() {
        cycleId.incrementAndGet()
        startJob?.cancel(); startJob = null
        publishJob?.cancel(); publishJob = null
        cellularWatchJob?.cancel(); cellularWatchJob = null
        server?.stop(); server = null
        cellular.stop()
        registry.reset()
        sampler.reset()
        releaseWakeLock()
    }

    /**
     * Listen for cellular drops while the proxy is up.
     * Drop → Paused (listener stays, new connects fail until data is back).
     * Recovery → back to Running.
     */
    private fun startCellularWatch(addr: String, port: Int) {
        cellularWatchJob?.cancel()
        cellularWatchJob = scope.launch {
            cellular.state.collect { cs ->
                val cur = _state.value
                when (cs) {
                    is CellularNetworkProvider.State.Available -> {
                        if (cur is State.Paused) {
                            _state.value = State.Running(addr, port)
                            updateNotification(addr, port, totals.value, rates.value)
                        }
                    }
                    is CellularNetworkProvider.State.Lost,
                    is CellularNetworkProvider.State.Unavailable -> {
                        if (cur is State.Running) {
                            _state.value = State.Paused(
                                addr, port,
                                "Waiting for mobile data",
                            )
                            updateNotification(addr, port, totals.value, rates.value)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------- foreground

    private fun startForegroundNow(addr: String, port: Int) {
        val notif = buildNotification(addr, port, totals.value, rates.value)
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse (not dataSync): dataSync foreground services can't be
            // started from a BOOT_COMPLETED receiver on Android 15+, which would
            // break auto-start-on-boot. specialUse is exempt from that rule and
            // works for both UI-initiated and boot-initiated starts. DataProxy
            // ships via GitHub, not Play, so the Play specialUse review gate
            // (which doesn't affect runtime) is a non-issue. See BootReceiver.
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startSampling() {
        publishJob = scope.launch {
            while (true) {
                val (up, down) = registry.snapshotBytes()
                sampler.sample(up, down)
                registry.publish()
                when (val s = _state.value) {
                    is State.Running -> updateNotification(s.bindAddress, s.port, totals.value, rates.value)
                    is State.Paused -> updateNotification(s.bindAddress, s.port, totals.value, rates.value)
                    else -> Unit
                }
                delay(1000L)
            }
        }
    }

    private fun updateNotification(
        addr: String, port: Int,
        totals: ConnectionRegistry.Totals,
        rates: SpeedSampler.Rates,
    ) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(addr, port, totals, rates))
    }

    private fun buildNotification(
        addr: String, port: Int,
        totals: ConnectionRegistry.Totals,
        rates: SpeedSampler.Rates,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val isPaused = _state.value is State.Paused
        val title = if (isPaused) "DataProxy · paused" else "DataProxy · $addr:$port"
        val rateUnit = RateUnit.fromKey(
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RATE_UNIT, null)
        )
        val sub = if (isPaused) "Waiting for mobile data"
        else "${totals.active} conn  ·  ↑${rateText(rates.upBps, rateUnit)}  ↓${rateText(rates.downBps, rateUnit)}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentTitle(title)
            .setContentText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_power, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "DataProxy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing proxy status"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun rateText(bytesPerSec: Long, unit: RateUnit): String {
        val (number, label) = ByteFormatter.rate(bytesPerSec, unit)
        return "$number$label"
    }

    /**
     * Read auth settings live from the same shared prefs the UI writes to.
     * Each new SOCKS5 connection re-reads, so toggling auth on the Auth
     * screen takes effect without restarting the proxy.
     */
    private fun currentAuthConfig(): AuthConfig {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return AuthConfig(
            enabled = prefs.getBoolean(PREF_AUTH_ENABLED, false),
            username = prefs.getString(PREF_AUTH_USERNAME, "") ?: "",
            password = prefs.getString(PREF_AUTH_PASSWORD, "") ?: "",
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DataProxy::ProxyWakeLock",
        ).apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.dataproxy.ACTION_START"
        const val ACTION_STOP = "com.dataproxy.ACTION_STOP"
        const val EXTRA_BIND_ADDRESS = "extra.bindAddress"
        const val EXTRA_PORT = "extra.port"
        const val DEFAULT_PORT = 1080

        private const val CHANNEL_ID = "dataproxy.status"
        private const val NOTIF_ID = 1001

        // Mirrored by [com.dataproxy.ui.viewmodel.MainViewModel], must match.
        const val PREFS_NAME = "dataproxy_prefs"
        const val PREF_AUTH_ENABLED = "auth_enabled"
        const val PREF_AUTH_USERNAME = "auth_username"
        const val PREF_AUTH_PASSWORD = "auth_password"
        // Last-chosen listen address + port. Written by MainViewModel; read by
        // BootReceiver so an auto-start uses the same endpoint as the UI.
        const val PREF_BIND_ADDRESS = "bind_address"
        const val PREF_PORT = "port"
        // Written by MainViewModel; read here fresh on every notification
        // rebuild so a toggle made while the proxy is running takes effect
        // on the next update without a restart.
        const val PREF_RATE_UNIT = "rate_unit"

        fun startIntent(ctx: Context, addr: String, port: Int) =
            Intent(ctx, ProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BIND_ADDRESS, addr)
                .putExtra(EXTRA_PORT, port)

        fun stopIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP)
    }
}
