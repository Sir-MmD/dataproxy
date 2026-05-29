package com.dataproxy.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.OutlineSoft
import com.dataproxy.ui.theme.OutlineStrong
import com.dataproxy.ui.theme.SurfaceHigh
import com.dataproxy.ui.theme.SurfaceLow
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.AntiKillPreferences
import com.dataproxy.util.AntiKillStep
import com.dataproxy.util.OemHelper

@Composable
fun AntiKillScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val autoStart by viewModel.autoStartOnBoot.collectAsStateWithLifecycle()
    var infoExpanded by rememberSaveable { mutableStateOf(false) }

    // Auto-detectable steps are queried live from the system on every resume.
    var notifGranted by remember { mutableStateOf(OemHelper.areNotificationsEnabled(context)) }
    var battGranted by remember {
        mutableStateOf(OemHelper.isIgnoringBatteryOptimizations(context))
    }
    // Manual OEM steps the user marked done — these can't be read back, so we
    // persist the "I've done this" flags.
    val manualSteps = remember {
        mutableStateMapOf<AntiKillStep, Boolean>().apply {
            AntiKillStep.entries.filterNot { it.autoDetectable }.forEach {
                put(it, AntiKillPreferences.stepDone(context, it))
            }
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    fun reSync() {
        notifGranted = OemHelper.areNotificationsEnabled(context)
        battGranted = OemHelper.isIgnoringBatteryOptimizations(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reSync()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun isGranted(step: AntiKillStep): Boolean = when (step) {
        AntiKillStep.Notifications -> notifGranted
        AntiKillStep.BatteryOptimization -> battGranted
        else -> manualSteps[step] == true
    }

    val grantedCount = AntiKillStep.entries.count { isGranted(it) }
    val total = AntiKillStep.entries.size
    val allDone = grantedCount == total
    val pct by animateFloatAsState(
        targetValue = if (total == 0) 0f else grantedCount / total.toFloat(),
        animationSpec = tween(400),
        label = "antikill-progress",
    )

    fun grantStep(step: AntiKillStep) {
        when (step) {
            AntiKillStep.Notifications ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    OemHelper.openNotificationSettings(context)
                }
            AntiKillStep.BatteryOptimization -> OemHelper.openBatteryOptimization(context)
            AntiKillStep.AutoStart -> OemHelper.openAutoStart(context)
            AntiKillStep.BackgroundActivity -> OemHelper.openBackgroundActivity(context)
            AntiKillStep.LockInRecents -> OemHelper.openLockInRecentsGuide(context)
        }
    }

    fun toggleManual(step: AntiKillStep) {
        val next = !(manualSteps[step] ?: false)
        manualSteps[step] = next
        AntiKillPreferences.setStepDone(context, step, next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        TopBar(
            title = "Anti-Kill",
            onBack = onBack,
            action = {
                IconButton(onClick = { infoExpanded = !infoExpanded }) {
                    Icon(
                        imageVector = Icons.Rounded.HelpOutline,
                        contentDescription = if (infoExpanded) "Hide explanation" else "What is this?",
                        tint = if (infoExpanded) Accent else TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WhatIsThisBanner(visible = infoExpanded, onClose = { infoExpanded = false })
            HeroCard(pct = pct, granted = grantedCount, total = total, allDone = allDone)
            AutoStartCard(enabled = autoStart, onToggle = viewModel::setAutoStartOnBoot)
            AntiKillStep.entries.forEach { step ->
                StepCard(
                    step = step,
                    granted = isGranted(step),
                    onGrant = { grantStep(step) },
                    onToggleManual = { toggleManual(step) },
                )
            }
        }
    }
}

@Composable
private fun WhatIsThisBanner(visible: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceLow)
                .border(1.dp, OutlineSoft, RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.08f), Color.Transparent)))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                IconBubble(Icons.Rounded.Lightbulb)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Why does DataProxy need these?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 6.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close explanation",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "The proxy runs entirely on your phone. To keep serving clients it has " +
                    "to stay awake in the background and hold the cellular connection — even " +
                    "with the screen off.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Android — and especially Samsung, Xiaomi, Huawei, and OnePlus — kill " +
                    "background apps to save battery. Each step below tells your phone " +
                    "\"don't kill DataProxy\" through a different channel: notifications, " +
                    "battery Doze, auto-launch, background activity, and the Recents lock.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "These are one-time settings that only affect DataProxy — every other " +
                    "app keeps its normal battery management.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun HeroCard(pct: Float, granted: Int, total: Int, allDone: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.10f), Color.Transparent)))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Accent.copy(alpha = 0.25f), Accent.copy(alpha = 0.10f)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (allDone) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (allDone) "You're set" else "Keep DataProxy alive",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (allDone) "Survival settings granted. The proxy will ride out Doze, swipe-away, and reboots."
                    else "Android will kill the proxy unless you grant these. Each is a one-time setting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Accent,
            trackColor = SurfaceHigh,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$granted of $total complete",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
        )
    }
}

/**
 * App-level "Start after reboot" toggle. Distinct from the OEM auto-launch step
 * below it: this flips the BootReceiver on/off, the OEM step is the
 * manufacturer's whitelist that lets that receiver actually fire.
 */
@Composable
private fun AutoStartCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable { onToggle(!enabled) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = if (enabled) 0.16f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.RestartAlt,
                contentDescription = null,
                tint = if (enabled) Accent else TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Start after reboot",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (enabled)
                    "DataProxy starts automatically when your phone restarts."
                else
                    "DataProxy stays off after a reboot until you open the app and tap power.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SurfaceLow,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceLow,
                uncheckedBorderColor = OutlineStrong,
            ),
        )
    }
}

@Composable
private fun StepCard(
    step: AntiKillStep,
    granted: Boolean,
    onGrant: () -> Unit,
    onToggleManual: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = if (granted) 0.18f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (granted) Icons.Rounded.CheckCircle else step.icon,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = TextMuted,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = 12.dp, start = 52.dp, end = 2.dp)) {
                Text(
                    step.rationale,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                if (step.autoDetectable) {
                    if (!granted) {
                        Button(
                            onClick = onGrant,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Color.Black,
                            ),
                        ) {
                            Text("Grant", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            "Granted.",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                        )
                    }
                } else {
                    // OEM step: open settings, then a manual "I've done this" switch —
                    // button on its own row so the label never wraps under the Switch.
                    OutlinedButton(
                        onClick = onGrant,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open settings")
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (granted) "Done" else "I've done this",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = granted,
                            onCheckedChange = { onToggleManual() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SurfaceLow,
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SurfaceLow,
                                uncheckedBorderColor = OutlineStrong,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBubble(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(22.dp),
        )
    }
}
