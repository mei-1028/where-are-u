package com.whereareu.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.permissions.OemFamily
import com.whereareu.app.core.permissions.OemHelper
import com.whereareu.app.core.permissions.PermissionChecker
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.BrandMark
import com.whereareu.app.ui.components.CircleIconButton
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.PermissionBadge
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.OrangePastel
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SuccessGreen
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.White
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var permissionTick by remember { mutableIntStateOf(0) }
    var finishing by remember { mutableStateOf(false) }
    var selectedOemName by rememberSaveable { mutableStateOf(OemHelper.detect().name) }
    val selectedOem = OemFamily.entries.firstOrNull { it.name == selectedOemName } ?: OemHelper.detect()

    val scrollState = rememberScrollState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(step) {
        scrollState.scrollTo(0)
    }

    val fineGranted = remember(permissionTick, step) {
        PermissionChecker.isForegroundLocationGranted(context)
    }
    val backgroundGranted = remember(permissionTick, step) {
        PermissionChecker.isBackgroundLocationGranted(context)
    }
    val notificationsGranted = remember(permissionTick, step) {
        PermissionChecker.areNotificationsGranted(context)
    }
    val batteryWhitelisted = remember(permissionTick, step) {
        PermissionChecker.isIgnoringBatteryOptimizations(context)
    }
    val systemLocationEnabled = remember(permissionTick, step) {
        PermissionChecker.isSystemLocationEnabled(context)
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = MiroSpacing.lg),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "上一步",
                    onClick = { if (step > 0) step -= 1 },
                )
            } else {
                BrandMark(compact = true)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${step + 1} / 3",
                style = MaterialTheme.typography.labelMedium,
                color = SlateText,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (index <= step) MiroBlue else BorderHairline),
                )
            }
        }
        Spacer(Modifier.height(32.dp))

        when (step) {
            0 -> ForegroundLocationStep(
                granted = fineGranted,
                onRequest = {
                    foregroundLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                onNext = { step = 1 },
                onSkip = {
                    context.toast("不授权将无法共享位置，可稍后在设置中开启")
                    step = 1
                },
            )

            1 -> BackgroundAndNotificationStep(
                backgroundGranted = backgroundGranted,
                notificationsGranted = notificationsGranted,
                systemLocationEnabled = systemLocationEnabled,
                onOpenAppSettings = { OemHelper.openAppSettings(context) },
                onRequestNotification = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        OemHelper.openNotificationSettings(context)
                    }
                },
                onOpenLocationSettings = { OemHelper.openLocationSettings(context) },
                onNext = { step = 2 },
            )

            else -> KeepAliveStep(
                selectedOem = selectedOem,
                onSelectOem = { selectedOemName = it.name },
                batteryWhitelisted = batteryWhitelisted,
                onRequestBatteryExemption = { OemHelper.requestIgnoreBatteryOptimizations(context) },
                onOpenAutostart = { OemHelper.openAutostartSettings(context, selectedOem) },
                finishing = finishing,
                onFinish = {
                    if (!finishing) {
                        finishing = true
                        scope.launch {
                            AppGraph.sessionStore.setOnboardingDone(true)
                            // Local sharing state is owned by SharingController:
                            // it PATCHes the server, waits for the FGS receipt and
                            // rolls back on failure. Never write local "true" here.
                            AppGraph.sharingController.setSharingEnabled(true)
                                .onFailure { error ->
                                    context.toast(
                                        "共享暂未启动：${error.message ?: "请稍后重试"}",
                                    )
                                }
                            finishing = false
                            onFinished()
                        }
                    }
                },
                onOpenSettings = { OemHelper.openAppSettings(context) },
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "所有权限都可以随时在「设置 → 权限自检」中调整。",
            style = MaterialTheme.typography.labelSmall,
            color = SlateText,
            modifier = Modifier.padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun ForegroundLocationStep(
    granted: Boolean,
    onRequest: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    StepHero(
        icon = Icons.Filled.LocationOn,
        iconTint = MiroBlue,
        background = TealPastel,
        title = "允许获取你的位置",
        description = "用于把你的位置分享给家人，仅家庭成员可见。共享随时可以暂停。",
    )
    Spacer(Modifier.height(24.dp))
    MiroCard(cornerRadius = 20.dp) {
        FeatureLine("仅家庭成员可见")
        FeatureLine("常驻通知表示共享正在进行")
        FeatureLine("随时可在首页或设置中暂停")
    }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        text = if (granted) "下一步" else "允许",
        onClick = { if (granted) onNext() else onRequest() },
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        LinkText(text = if (granted) "稍后设置其他权限" else "暂不，稍后开启", onClick = onSkip, color = SlateText)
    }
}

@Composable
private fun BackgroundAndNotificationStep(
    backgroundGranted: Boolean,
    notificationsGranted: Boolean,
    systemLocationEnabled: Boolean,
    onOpenAppSettings: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onNext: () -> Unit,
) {
    StepHero(
        icon = Icons.Filled.Notifications,
        iconTint = Color(0xFF8A4C00),
        background = OrangePastel,
        title = "锁屏后也能更新位置",
        description = "后台定位和通知能让共享在锁屏时保持可靠。系统会要求先允许「使用期间」定位，再选择「始终允许」。",
    )
    Spacer(Modifier.height(20.dp))

    if (!systemLocationEnabled) {
        StatusBanner(
            text = "系统定位已关闭，位置将无法更新",
            tone = BannerTone.Error,
            actionLabel = "去开启",
            onAction = onOpenLocationSettings,
        )
        Spacer(Modifier.height(12.dp))
    }

    PermissionStepCard(
        title = "后台定位",
        description = "选择「始终允许」，家人才能在你锁屏时看到位置",
        granted = backgroundGranted,
        actionLabel = if (backgroundGranted) null else "去设置",
        onAction = onOpenAppSettings,
        footnote = if (backgroundGranted) null else "在应用权限 → 位置信息中选择「始终允许」",
    )
    Spacer(Modifier.height(12.dp))
    PermissionStepCard(
        title = "通知",
        description = "常驻通知是系统对前台服务的要求，也用于显示共享状态",
        granted = notificationsGranted,
        actionLabel = if (notificationsGranted) null else "去授权",
        onAction = onRequestNotification,
        footnote = if (notificationsGranted) null else "若没有弹出授权框，可在系统通知设置中开启",
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        text = if (backgroundGranted && notificationsGranted) "下一步" else "稍后再说",
        onClick = onNext,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "未完成两项也可以继续，但后台共享可能不稳定。",
        style = MaterialTheme.typography.labelSmall,
        color = SlateText,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeepAliveStep(
    selectedOem: OemFamily,
    onSelectOem: (OemFamily) -> Unit,
    batteryWhitelisted: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onOpenAutostart: () -> Unit,
    finishing: Boolean,
    onFinish: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StepHero(
        icon = Icons.Filled.BatteryFull,
        iconTint = Color(0xFF7A2E5A),
        background = PinkPastel,
        title = "让共享保持在线",
        description = "部分系统会清理后台应用。选对你的手机品牌，按提示完成自启动和电池白名单设置。",
    )
    Spacer(Modifier.height(20.dp))

    Text("手机品牌", style = MaterialTheme.typography.labelMedium, color = SlateText)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(OemFamily.entries) { oem ->
            val selected = oem == selectedOem
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) MiroBlueSoft else White)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MiroBlue else BorderHairline,
                        shape = CircleShape,
                    )
                    .clickable { onSelectOem(oem) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = oem.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MiroBlue else Ink,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    MiroCard(cornerRadius = 16.dp) {
        OemHelper.steps(selectedOem).forEachIndexed { index, text ->
            Row(
                modifier = Modifier.padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MiroBlueSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MiroBlue,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = Ink)
            }
        }
        Spacer(Modifier.height(8.dp))
        LinkText(
            text = "打不开？前往应用设置",
            onClick = onOpenSettings,
            modifier = Modifier.padding(start = 0.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    PermissionStepCard(
        title = "忽略电池优化",
        description = "减少系统在息屏后清理定位服务的概率",
        granted = batteryWhitelisted,
        actionLabel = if (batteryWhitelisted) null else "去授权",
        onAction = onRequestBatteryExemption,
        grantedLabel = "已豁免",
        deniedLabel = "未豁免",
    )
    Spacer(Modifier.height(12.dp))
    PermissionStepCard(
        title = "自启动 / 后台运行",
        description = "不同品牌的名称不同，通常在「${selectedOem.label}」的安全中心里",
        granted = null,
        actionLabel = "去设置",
        onAction = onOpenAutostart,
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        text = "完成，开始共享",
        loading = finishing,
        onClick = onFinish,
    )
}

@Composable
private fun StepHero(
    icon: ImageVector,
    iconTint: Color,
    background: Color,
    title: String,
    description: String,
) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(54.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink)
    Spacer(Modifier.height(8.dp))
    Text(description, style = MaterialTheme.typography.bodyMedium, color = SlateText)
}

@Composable
private fun FeatureLine(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

@Composable
private fun PermissionStepCard(
    title: String,
    description: String,
    granted: Boolean?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    footnote: String? = null,
    grantedLabel: String = "已授权",
    deniedLabel: String = "未授权",
) {
    MiroCard(cornerRadius = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            PermissionBadge(
                granted = granted,
                grantedLabel = grantedLabel,
                deniedLabel = deniedLabel,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            LinkText(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(start = 0.dp),
            )
        }
        if (!footnote.isNullOrBlank()) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                color = SlateText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
