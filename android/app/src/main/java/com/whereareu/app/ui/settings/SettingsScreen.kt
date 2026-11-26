package com.whereareu.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.model.ReportFrequency
import com.whereareu.app.core.permissions.OemHelper
import com.whereareu.app.core.permissions.PermissionChecker
import com.whereareu.app.core.permissions.PermissionKind
import com.whereareu.app.core.permissions.PermissionStatus
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.ChoiceOption
import com.whereareu.app.ui.components.ChoicePills
import com.whereareu.app.ui.components.ConfirmDialog
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.MemberAvatar
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.PermissionBadge
import com.whereareu.app.ui.components.SectionHeader
import com.whereareu.app.ui.components.SettingItem
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.components.StatusPill
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.BorderStrong
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor

@Composable
fun SettingsScreen(onLoggedOut: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactoryFor {
            SettingsViewModel(
                context = AppGraph.appContext,
                sessionStore = AppGraph.sessionStore,
                authRepository = AppGraph.authRepository,
                offlineQueue = AppGraph.offlineQueue,
                providerFactory = AppGraph.locationProviderFactory,
                sharingController = AppGraph.sharingController,
            )
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionTick by remember { mutableIntStateOf(0) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var showClearQueueConfirm by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = remember(permissionTick) { PermissionChecker.statuses(context) }
    val systemLocationEnabled = remember(permissionTick) {
        PermissionChecker.isSystemLocationEnabled(context)
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        context.toast(message)
        viewModel.clearMessage()
    }
    LaunchedEffect(uiState.logoutComplete) {
        if (uiState.logoutComplete) {
            viewModel.consumeLogout()
            onLoggedOut()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = MiroSpacing.lg),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "账号、位置共享、权限与隐私。",
            style = MaterialTheme.typography.bodySmall,
            color = SlateText,
        )
        Spacer(Modifier.height(20.dp))

        MiroCard(background = SurfaceMuted, borderColor = BorderHairline, cornerRadius = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(
                    name = uiState.displayName,
                    colorIndex = 0,
                    size = 52.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.displayName.ifBlank { "家人" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        text = "@${uiState.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                    )
                }
                StatusPill(
                    text = if (uiState.role == "admin") "管理员" else "成员",
                    foreground = MiroBlue,
                    background = MiroBlueSoft,
                )
            }
            if (uiState.familyName.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "家庭：${uiState.familyName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("位置共享")
        MiroCard(cornerRadius = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("共享我的位置", style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(
                        text = when {
                            uiState.sharingBusy -> "正在同步共享状态…"
                            uiState.sharingEnabled -> "家人可以看到我的实时位置"
                            else -> "已暂停，家人看不到我的位置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = uiState.sharingEnabled,
                    onCheckedChange = viewModel::setSharingEnabled,
                    enabled = !uiState.sharingBusy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = White,
                        checkedTrackColor = MiroBlue,
                        uncheckedThumbColor = White,
                        uncheckedTrackColor = BorderStrong,
                    ),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("上报频率", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(
                text = "静止时自动降频，移动时提高频率",
                style = MaterialTheme.typography.labelSmall,
                color = SlateText,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            ChoicePills(
                options = ReportFrequency.entries.map {
                    ChoiceOption(
                        key = it.name,
                        title = it.label,
                        subtitle = it.shortLabel,
                    )
                },
                selectedKey = uiState.reportFrequency.name,
                onSelect = { key ->
                    ReportFrequency.entries.firstOrNull { it.name == key }?.let {
                        viewModel.setReportFrequency(it)
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "后台运行状态：${if (uiState.sharingEnabled) "已请求前台服务，系统可能因省电策略延迟" else "已停止"}",
                style = MaterialTheme.typography.labelSmall,
                color = SlateText,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("权限自检")
        if (!systemLocationEnabled) {
            StatusBanner(
                text = "系统定位已关闭，位置无法更新",
                tone = BannerTone.Error,
                actionLabel = "去开启",
                onAction = { OemHelper.openLocationSettings(context) },
            )
            Spacer(Modifier.height(8.dp))
        }
        MiroCard(cornerRadius = 20.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            permissions.forEachIndexed { index, status ->
                PermissionRow(
                    status = status,
                    onAction = { openPermissionSettings(context, status.kind) },
                )
                if (index != permissions.lastIndex) {
                    HorizontalDivider(color = BorderHairline)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("隐私与安全")
        MiroCard(cornerRadius = 20.dp) {
            SettingItem(
                title = "隐私说明",
                subtitle = "数据存储与权限用途",
                onClick = { showPrivacy = true },
                showDivider = true,
            )
            SettingItem(
                title = "清除本地待上传位置",
                subtitle = "断网期间缓存的位置点",
                onClick = { showClearQueueConfirm = true },
                showDivider = true,
            )
            SettingItem(
                title = "清除云端历史位置",
                subtitle = "M1 暂未开放，请在服务端管理",
                value = "暂未开放",
            )
            HorizontalDivider(color = BorderHairline)
            SettingItem(
                title = "服务器地址",
                subtitle = uiState.serverUrl,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("关于")
        MiroCard(cornerRadius = 20.dp) {
            SettingItem(title = "版本", value = BuildConfig.VERSION_NAME, showDivider = true)
            SettingItem(
                title = "定位来源",
                value = if (uiState.usingFakeProvider) "模拟定位（未配置高德 Key）" else "高德定位",
                showDivider = true,
            )
            SettingItem(
                title = "家庭",
                value = uiState.familyName.ifBlank { "未加入" },
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { showLogoutConfirm = true },
            enabled = !uiState.loggingOut,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed),
        ) {
            Text(
                text = if (uiState.loggingOut) "正在退出…" else "退出登录",
                style = MaterialTheme.typography.labelLarge,
                color = DangerRed,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "退出登录会解绑本机设备并停止位置共享；服务端历史位置不会删除。",
            style = MaterialTheme.typography.labelSmall,
            color = SlateText,
        )
        Spacer(Modifier.height(32.dp))
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("隐私说明", style = MaterialTheme.typography.titleLarge, color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "位置数据只发送到你配置的自建服务器，仅同一家庭的成员可以查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                    )
                    Text(
                        text = "本机保存登录令牌、设备令牌、上报频率和断网时缓存的位置点。退出登录会清除令牌与待上传位置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                    )
                    Text(
                        text = "前台服务期间，系统会显示常驻通知，表明共享正在进行；你可以随时在首页或设置中暂停。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                    )
                    Text(
                        text = "M1 不包含第三方统计、广告或位置导出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) {
                    Text("知道了", style = MaterialTheme.typography.labelLarge, color = MiroBlue)
                }
            },
            containerColor = White,
            shape = RoundedCornerShape(20.dp),
        )
    }

    if (showClearQueueConfirm) {
        ConfirmDialog(
            title = "清除本地待上传位置？",
            message = "断网期间缓存的位置点会被丢弃，无法补传。",
            confirmLabel = "清除",
            destructive = true,
            onConfirm = {
                showClearQueueConfirm = false
                viewModel.clearPendingLocations()
            },
            onDismiss = { showClearQueueConfirm = false },
        )
    }

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "退出登录？",
            message = "位置共享会停止，服务器地址会保留，下次登录需要重新输入账号密码。",
            confirmLabel = "退出登录",
            destructive = true,
            onConfirm = {
                showLogoutConfirm = false
                viewModel.logout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}

@Composable
private fun PermissionRow(
    status: PermissionStatus,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(status.title, style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(
                text = status.description,
                style = MaterialTheme.typography.labelSmall,
                color = SlateText,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PermissionBadge(
                granted = status.granted,
                grantedLabel = if (status.kind == PermissionKind.BATTERY_OPTIMIZATION) "已豁免" else "已开启",
                deniedLabel = if (status.kind == PermissionKind.BATTERY_OPTIMIZATION) "未豁免" else "未开启",
            )
            Spacer(Modifier.height(2.dp))
            LinkText(text = "去设置", onClick = onAction)
        }
    }
}

private fun openPermissionSettings(
    context: android.content.Context,
    kind: PermissionKind,
) {
    when (kind) {
        PermissionKind.FOREGROUND_LOCATION, PermissionKind.BACKGROUND_LOCATION ->
            OemHelper.openAppSettings(context)

        PermissionKind.NOTIFICATIONS -> OemHelper.openNotificationSettings(context)
        PermissionKind.BATTERY_OPTIMIZATION -> OemHelper.requestIgnoreBatteryOptimizations(context)
        PermissionKind.AUTOSTART -> OemHelper.openAutostartSettings(
            context,
            OemHelper.detect(),
        )
    }
}
