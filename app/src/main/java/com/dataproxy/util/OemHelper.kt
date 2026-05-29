package com.dataproxy.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Opens the per-manufacturer settings screens that decide whether DataProxy is
 * allowed to keep running in the background. None of these can be toggled
 * programmatically — the OEM only exposes a settings activity the user has to
 * flip themselves. We do our best to deep-link to the right page and fall back
 * to the app-details screen when the vendor activity isn't resolvable.
 */
object OemHelper {

    enum class Oem { Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung, Asus, Letv, Other }

    val current: Oem by lazy {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        when {
            m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> Oem.Xiaomi
            m.contains("huawei") || b.contains("honor") -> Oem.Huawei
            m.contains("oppo") || b.contains("realme") -> Oem.Oppo
            m.contains("vivo") || b.contains("iqoo") -> Oem.Vivo
            m.contains("oneplus") -> Oem.OnePlus
            m.contains("samsung") -> Oem.Samsung
            m.contains("asus") -> Oem.Asus
            m.contains("letv") -> Oem.Letv
            else -> Oem.Other
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsIntent(context)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppSettings(context) }
    }

    @Suppress("BatteryLife")
    fun openBatteryOptimization(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { openAppSettings(context) }
            }
    }

    fun openAutoStart(context: Context) {
        if (tryStart(context, autoStartIntents(context))) return
        openAppSettings(context)
    }

    fun openBackgroundActivity(context: Context) {
        if (tryStart(context, backgroundActivityIntents(context))) return
        openAppSettings(context)
    }

    fun openLockInRecentsGuide(context: Context) {
        // open Recents directly so the user can pin the app
        runCatching {
            context.startActivity(
                Intent("com.android.systemui.recent.action.TOGGLE_RECENTS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { openAppSettings(context) }
    }

    fun openAppSettings(context: Context) {
        runCatching { context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    private fun tryStart(context: Context, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = context.packageManager.resolveActivity(intent, 0)
            if (resolved != null) {
                val ok = runCatching { context.startActivity(intent) }.isSuccess
                if (ok) return true
            }
        }
        return false
    }

    private fun autoStartIntents(context: Context): List<Intent> = when (current) {
        Oem.Xiaomi -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Oem.Huawei -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupAppControlActivity"),
        )
        Oem.Oppo -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        Oem.Vivo -> listOf(
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        Oem.OnePlus -> listOf(
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        Oem.Asus -> listOf(
            Intent("com.asus.mobilemanager.ACTION_BACKGROUND_MANAGER").setComponent(
                ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
            ),
            component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        )
        Oem.Letv -> listOf(
            component("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        )
        // Samsung has no exposed "auto-start" — best we can do is open the Battery section
        // of Device Care so the user can mark DataProxy as a "Never sleeping app".
        Oem.Samsung -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Oem.Other -> emptyList()
    }

    private fun backgroundActivityIntents(context: Context): List<Intent> = when (current) {
        Oem.Xiaomi -> listOf(
            component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
        )
        Oem.Vivo -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        Oem.Oppo -> listOf(
            component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        // On Samsung the per-app Battery page is reached via App info. Open that — user
        // taps Battery → Allow background activity (or set to Unrestricted).
        Oem.Samsung -> listOf(appDetailsIntent(context))
        else -> listOf(appDetailsIntent(context))
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
}
