package com.dataproxy.network

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Polls TelephonyManager to surface the current mobile-data state and the
 * actual radio technology in use (2G / 3G / 4G / 5G).
 *
 * Reads via `READ_BASIC_PHONE_STATE` — a *normal* (install-time, no prompt)
 * permission introduced in API 33. On older devices the permission has no
 * effect and `getDataNetworkType()` throws / returns UNKNOWN; the monitor
 * surfaces [TechState.Unknown] in that case.
 *
 * Independent of our cellular [CellularNetworkProvider]: we want the header
 * to show the device's real tech whether or not the proxy has requested a
 * network. Polling is fine — tech changes are rare and 2 s lag is invisible.
 */
class CellularTechMonitor(context: Context) {

    private val tm = context.applicationContext
        .getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _state = MutableStateFlow<TechState>(TechState.Unknown)
    val state: StateFlow<TechState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                _state.value = read()
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
    }

    private fun read(): TechState {
        val on = runCatching { tm.isDataEnabled }.getOrNull() ?: return TechState.Unknown
        if (!on) return TechState.DataOff
        val type = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val operator = runCatching { tm.networkOperatorName }.getOrDefault("").orEmpty().trim()
        val tech = when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G"

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"

            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"

            TelephonyManager.NETWORK_TYPE_NR -> "5G"

            else -> null
        } ?: return if (operator.isNotEmpty()) TechState.OperatorOnly(operator) else TechState.Unknown
        return TechState.Tech(label = tech, operator = operator)
    }

    sealed interface TechState {
        /** Mobile data is switched off on the device. */
        data object DataOff : TechState
        /** Permission missing or carrier state hasn't reported a known tech yet. */
        data object Unknown : TechState
        /** Operator known but tech not (e.g. READ_PHONE_STATE denied on pre-33). */
        data class OperatorOnly(val operator: String) : TechState
        /** Currently-active radio tech and operator name. */
        data class Tech(val label: String, val operator: String) : TechState
    }

    companion object {
        private const val POLL_MS = 2_000L
    }
}
