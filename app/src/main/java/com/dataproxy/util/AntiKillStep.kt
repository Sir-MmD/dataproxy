package com.dataproxy.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The survival settings that keep Android — and aggressive OEM skins — from
 * killing the proxy when the screen is off or the app is swiped away.
 *
 * [autoDetectable] steps ([Notifications], [BatteryOptimization]) report their
 * real granted state via the system; the rest are manufacturer settings that
 * can't be queried, so the user marks them done by hand.
 */
enum class AntiKillStep(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val autoDetectable: Boolean,
) {
    Notifications(
        title = "Notifications",
        description = "Required for the ongoing proxy-status notification.",
        icon = Icons.Rounded.NotificationsActive,
        autoDetectable = true,
    ),
    BatteryOptimization(
        title = "Disable battery optimization",
        description = "Stop Android Doze from suspending the proxy.",
        icon = Icons.Rounded.BatteryChargingFull,
        autoDetectable = true,
    ),
    AutoStart(
        title = "Allow auto-launch",
        description = "Let the system relaunch DataProxy after a reboot.",
        icon = Icons.Rounded.RestartAlt,
        autoDetectable = false,
    ),
    BackgroundActivity(
        title = "Allow background activity",
        description = "Some OEMs block background sockets by default.",
        icon = Icons.Rounded.Bolt,
        autoDetectable = false,
    ),
    LockInRecents(
        title = "Lock app in Recents",
        description = "Prevents swipe-to-kill on aggressive OEMs.",
        icon = Icons.Rounded.LayersClear,
        autoDetectable = false,
    );

    val rationale: String
        get() = when (this) {
            Notifications ->
                "Android 13+ requires explicit permission to post notifications. The foreground service won't run without it."
            BatteryOptimization ->
                "Doze mode pauses background apps after the screen turns off. Exempting DataProxy keeps the proxy listener and the cellular link up."
            AutoStart -> when (OemHelper.current) {
                OemHelper.Oem.Samsung ->
                    "Samsung doesn't expose Auto-start as a separate setting. On the Device Care → Battery page that opens, tap \"Background usage limits\" → \"Never sleeping apps\" → add DataProxy."
                OemHelper.Oem.Xiaomi ->
                    "MIUI kills apps that didn't auto-start. Find DataProxy in the list and toggle Autostart ON."
                OemHelper.Oem.Huawei ->
                    "EMUI's app launch manager controls auto-start. Switch DataProxy to manual, then enable Auto-launch and Run in background."
                OemHelper.Oem.Oppo, OemHelper.Oem.Vivo, OemHelper.Oem.OnePlus ->
                    "Find DataProxy in the auto-start list and enable it. Names vary: 'Startup manager', 'Auto-launch', etc."
                else ->
                    "If your phone reboots, DataProxy should come back up on its own — otherwise the proxy silently stays off until you reopen the app."
            }
            BackgroundActivity -> when (OemHelper.current) {
                OemHelper.Oem.Samsung ->
                    "On the page that opens, tap Battery → set background usage to Unrestricted. Anything less will eventually pause the proxy."
                OemHelper.Oem.Xiaomi ->
                    "Open Battery saver settings for DataProxy and set to No restrictions."
                else ->
                    "Some OEMs aggressively restrict background networking. This unlocks the cellular sockets the proxy relies on."
            }
            LockInRecents ->
                "Open Recents, find DataProxy, and tap the lock/pin icon. This is per-device and only you can do it."
        }
}
