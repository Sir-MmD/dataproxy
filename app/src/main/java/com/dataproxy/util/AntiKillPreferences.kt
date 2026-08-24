package com.dataproxy.util

import android.content.Context
import com.dataproxy.service.ProxyService

/**
 * Anti-Kill persistence, kept in the same [ProxyService.PREFS_NAME] file the
 * rest of the app uses.
 *
 * - [autoStartOnBoot] gates whether [com.dataproxy.service.BootReceiver]
 *   relaunches the proxy after a reboot.
 * - the per-[AntiKillStep] flags only cover the manual OEM steps, the user
 *   ticks "I've done this" because the system can't report those settings.
 *   Auto-detectable steps are read live and never persisted here.
 */
object AntiKillPreferences {
    private const val KEY_AUTOSTART = "autostart_on_boot"
    private fun stepKey(step: AntiKillStep) = "antikill_step_${step.name}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)

    fun autoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    fun stepDone(context: Context, step: AntiKillStep): Boolean =
        prefs(context).getBoolean(stepKey(step), false)

    fun setStepDone(context: Context, step: AntiKillStep, done: Boolean) {
        prefs(context).edit().putBoolean(stepKey(step), done).apply()
    }
}
