package com.dataproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dataproxy.util.AntiKillPreferences

/**
 * Relaunches the proxy after a reboot (or after the app is updated) when the
 * user has enabled "Start after reboot" on the Anti-Kill screen.
 *
 * The proxy's foreground service is declared `specialUse`, which Android 15+
 * still permits starting from a BOOT_COMPLETED receiver — `dataSync` is not.
 * That's the whole reason [ProxyService] uses the specialUse type.
 *
 * Cellular may be cold at boot; [ProxyService.startProxy] already waits up to
 * 15 s for the link and surfaces an error / stops itself if mobile data never
 * comes up. We deliberately don't add our own retry here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return
        if (!AntiKillPreferences.autoStartOnBoot(context)) return

        val prefs = context.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)
        val addr = prefs.getString(ProxyService.PREF_BIND_ADDRESS, "0.0.0.0") ?: "0.0.0.0"
        val port = prefs.getInt(ProxyService.PREF_PORT, ProxyService.DEFAULT_PORT)

        // startForegroundService is required (we're a background context here);
        // the service calls startForeground() synchronously in startProxy.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                ProxyService.startIntent(context, addr, port),
            )
        }
    }
}
