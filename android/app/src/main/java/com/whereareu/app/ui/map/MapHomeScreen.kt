package com.whereareu.app.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.location.LocationTrackingService
import com.whereareu.app.core.location.TrackingManager
import com.whereareu.app.core.model.LocateUiState
import com.whereareu.app.core.permissions.OemHelper
import com.whereareu.app.core.permissions.PermissionChecker
import com.whereareu.app.core.util.TimeUtils
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.CircleIconButton
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.MemberAvatar
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.PastelTag
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.components.StatusPill
import com.whereareu.app.ui.follow.FollowSheet
import com.whereareu.app.ui.theme.BorderStrong
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.DangerSurface
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.OfflineGray
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SuccessGreen
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.WarningSurface
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.theme.memberColor
import com.whereareu.app.ui.viewModelFactoryFor

@Composable
fun MapHomeScreen(
    onOpenHistory: (String, String) -> Unit,
    onOpenInvite: () -> Unit,
    onOpenAlerts: () -> Unit,
    onSessionExpired: () -> Unit,
    active: Boolean = true,
    onFamilyRemoved: () -> Unit = {},
) {
    val viewModel: MapViewModel = viewModel(
        factory = viewModelFactoryFor {
            MapViewModel(
                familyRepository = AppGraph.familyRepository,
                sessionStore = AppGraph.sessionStore,
                addressResolver = AppGraph.addressResolver,
                authRepository = AppGraph.authRepository,
                sharingController = AppGraph.sharingController,
                familySessionManager = AppGraph.familySessionManager,
            )
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val unreadAlerts by AppGraph.unreadAlerts.collectAsStateWithLifecycle()
    val pendingFocus by AppGraph.pendingMemberFocus.collectAsStateWithLifecycle()

    var followMember by remember { mutableStateOf<MemberUi?>(null) }

    // Notification tap -> open that member's detail sheet.
    LaunchedEffect(pendingFocus) {
        val userId = pendingFocus ?: return@LaunchedEffect
        viewModel.selectMember(userId)
        AppGraph.pendingMemberFocus.value = null
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, active) {
        fun syncPolling() {
            viewModel.setPollingActive(
                active && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    permissionTick++
                    syncPolling()
                }

                Lifecycle.Event.ON_PAUSE -> syncPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        syncPolling()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setPollingActive(false)
        }
    }

    val systemLocationEnabled = remember(permissionTick) {
        PermissionChecker.isSystemLocationEnabled(context)
    }
    val fineGranted = remember(permissionTick) {
        PermissionChecker.isForegroundLocationGranted(context)
    }
    val backgroundGranted = remember(permissionTick) {
        PermissionChecker.isBackgroundLocationGranted(context)
    }
    val notificationsGranted = remember(permissionTick) {
        PermissionChecker.areNotificationsGranted(context)
    }
    val batteryExempt = remember(permissionTick) {
        PermissionChecker.isIgnoringBatteryOptimizations(context)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    LaunchedEffect(Unit) {
        if (AppGraph.sessionStore.current().deviceToken.isNullOrBlank()) {
            AppGraph.deviceManager.ensureRegistered()
        }
    }

    // Start (or resume) tracking when the screen is shown and after the user
    // returns from system permission settings. Foreground context makes the FGS
    // start reliable; boot/background paths remain best effort.
    LaunchedEffect(uiState.sharingEnabled, permissionTick) {
        if (uiState.sharingEnabled &&
            PermissionChecker.isForegroundLocationGranted(context) &&
            !LocationTrackingService.isRunning
        ) {
            TrackingManager.start(context)
        }
    }

    LaunchedEffect(uiState.sessionExpired) {
        if (uiState.sessionExpired) {
            TrackingManager.stop(context)
            AppGraph.offlineQueue.clear()
            onSessionExpired()
        }
    }

    LaunchedEffect(uiState.familyRemoved) {
        if (uiState.familyRemoved) {
            // FamilySessionManager already stopped tracking and cleared the
            // device token; drop the queue and return to create/join.
            AppGraph.offlineQueue.clear()
            onFamilyRemoved()
        }
    }

    // The map stays alive under the Family/Settings overlay, but its modal
    // detail sheet must not float above another tab.
    LaunchedEffect(active) {
        if (!active) viewModel.clearSelection()
    }

    LaunchedEffect(uiState.sharingActionError) {
        val error = uiState.sharingActionError ?: return@LaunchedEffect
        context.toast("切换共享失败：$error")
        viewModel.clearSharingActionError()
    }

    val permissionBanner = when {
        !systemLocationEnabled -> PermissionBannerSpec(
            text = "系统定位已关闭，位置可能不更新",
            tone = BannerTone.Error,
            actionLabel = "去开启",
            action = { OemHelper.openLocationSettings(context) },
        )

        !fineGranted -> PermissionBannerSpec(
            text = "定位权限未开启，无法共享位置",
            tone = BannerTone.Error,
            actionLabel = "去开启",
            action = { OemHelper.openAppSettings(context) },
        )

        !backgroundGranted -> PermissionBannerSpec(
            text = "后台定位未开启，位置可能不及时",
            tone = BannerTone.Warning,
            actionLabel = "去修复",
            action = { OemHelper.openAppSettings(context) },
        )

        !notificationsGranted -> PermissionBannerSpec(
            text = "通知未开启，常驻通知不可见",
            tone = BannerTone.Warning,
            actionLabel = "去授权",
            action = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    OemHelper.openNotificationSettings(context)
                }
            },
        )

        !batteryExempt -> PermissionBannerSpec(
            text = "系统可能清理后台，建议忽略电池优化",
            tone = BannerTone.Warning,
            actionLabel = "去设置",
            action = { OemHelper.requestIgnoreBatteryOptimizations(context) },
        )

        else -> null
    }

    val selected = uiState.selectedMember
    val myMember = uiState.myMember
    val myIndex = uiState.members.indexOfFirst { it.userId == uiState.myUserId }.coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceMuted),
    ) {
        FamilyMap(
            members = uiState.members,
            myUserId = uiState.myUserId,
            selectedUserId = uiState.selectedUserId,
            recenterNonce = uiState.recenterNonce,
            locatingUserId = (uiState.locateState as? LocateUiState.Requesting)?.let {
                uiState.selectedUserId
            },
            onSelectMember = viewModel::selectMember,
            modifier = Modifier.fillMaxSize(),
            active = active,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(White.copy(alpha = 0.94f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.familyName.ifBlank { "我的家庭" },
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("${uiState.onlineCount} 人在线")
                            append(" · ")
                            append(
                                when {
                                    uiState.refreshing -> "更新中…"
                                    uiState.lastSyncedAt != null ->
                                        "更新于 ${TimeUtils.formatClock(uiState.lastSyncedAt ?: 0L)}"
                                    else -> "等待同步"
                                },
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                        maxLines = 1,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        CircleIconButton(
                            icon = Icons.Filled.Notifications,
                            contentDescription = "消息",
                            onClick = onOpenAlerts,
                        )
                        if (unreadAlerts > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(DangerRed)
                                    .border(2.dp, White, CircleShape),
                            )
                        }
                    }
                    CircleIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        onClick = viewModel::refresh,
                    )
                }
            }

            if (uiState.members.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(uiState.members, key = { _, member -> member.userId }) { index, member ->
                        MemberChip(
                            member = member,
                            colorIndex = index,
                            selected = member.userId == uiState.selectedUserId,
                            onClick = { viewModel.selectMember(member.userId) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.connectionError != null) {
                    StatusBanner(
                        text = "连接中断，正在重试 · ${uiState.connectionError}",
                        tone = BannerTone.Neutral,
                        actionLabel = "重试",
                        onAction = viewModel::refresh,
                    )
                }
                if (permissionBanner != null) {
                    StatusBanner(
                        text = permissionBanner.text,
                        tone = permissionBanner.tone,
                        actionLabel = permissionBanner.actionLabel,
                        onAction = permissionBanner.action,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(White.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LegendDot(SuccessGreen, "在线")
                    LegendDot(OfflineGray, "离线")
                    LegendDot(MiroBlue, "我")
                }
                Spacer(Modifier.weight(1f))
                CircleIconButton(
                    icon = Icons.Filled.MyLocation,
                    contentDescription = "回到我的位置",
                    onClick = viewModel::recenter,
                )
            }
            Spacer(Modifier.height(8.dp))

            when {
                uiState.loading && uiState.members.isEmpty() -> Unit

                uiState.members.isNotEmpty() && uiState.members.size <= 1 ->
                    InviteFamilyCard(onOpenInvite = onOpenInvite)

                myMember != null -> MySharingCard(
                    member = myMember,
                    colorIndex = myIndex,
                    sharingEnabled = uiState.sharingEnabled,
                    busy = uiState.sharingBusy,
                    onToggleSharing = viewModel::setSharingEnabled,
                )
            }
        }

        if (uiState.loading && uiState.members.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(White.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(message = "正在加载家人位置…")
            }
        }
    }

    if (selected != null) {
        MemberDetailSheet(
            member = selected,
            colorIndex = uiState.members.indexOfFirst { it.userId == selected.userId }.coerceAtLeast(0),
            isMe = selected.userId == uiState.myUserId,
            locateState = uiState.locateState,
            onDismiss = viewModel::clearSelection,
            onLocate = { viewModel.locateNow(selected.userId) },
            onHistory = {
                val deviceId = selected.deviceId
                if (deviceId.isNullOrBlank()) {
                    context.toast("暂无设备信息，无法查看轨迹")
                } else {
                    onOpenHistory(deviceId, selected.nickname)
                }
            },
            onNavigate = { navigateToMember(context, selected) },
            onFollow = {
                followMember = selected
                viewModel.clearSelection()
            },
        )
    }

    followMember?.let { member ->
        FollowSheet(
            member = member,
            onDismiss = { followMember = null },
        )
    }
}

@Composable
private fun MemberChip(
    member: MemberUi,
    colorIndex: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFFF2F4FF) else White.copy(alpha = 0.92f))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MiroBlue else Color(0xFFE8E9EF),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            MemberAvatar(
                name = member.nickname,
                colorIndex = colorIndex,
                size = 44.dp,
                online = member.online,
                highlight = selected,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = member.nickname,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MiroBlue else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MySharingCard(
    member: MemberUi,
    colorIndex: Int,
    sharingEnabled: Boolean,
    busy: Boolean,
    onToggleSharing: (Boolean) -> Unit,
) {
    MiroCard(
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "我的共享",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateText,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sharingEnabled) {
                        StatusPill("共享中", SuccessGreen, Color(0xFFE3F8EF))
                    } else {
                        StatusPill("已暂停", Color(0xFF8A5A00), WarningSurface)
                    }
                    Text(
                        text = TimeUtils.relativeFromEpoch(member.recordedAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateText,
                    )
                }
            }
            MemberAvatar(
                name = member.nickname,
                colorIndex = colorIndex,
                size = 40.dp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                !member.hasLocation -> "还没有上报过位置"
                else -> member.address ?: "正在解析地址…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        if (sharingEnabled) {
            OutlinedButton(
                onClick = { onToggleSharing(false) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong),
            ) {
                Text(
                    text = if (busy) "正在同步…" else "暂停共享",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink,
                )
            }
        } else {
            Button(
                onClick = { onToggleSharing(true) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MiroBlue,
                    contentColor = White,
                ),
            ) {
                Text(
                    text = if (busy) "正在同步…" else "恢复共享",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun InviteFamilyCard(onOpenInvite: () -> Unit) {
    MiroCard(
        background = PinkPastel.copy(alpha = 0.4f),
        borderColor = PinkPastel,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        PastelTag("家庭里只有你", PinkPastel)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "邀请家人一起使用",
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
        )
        Text(
            text = "把 6 位邀请码发给家人，加入后就能在地图上互相看到。",
            style = MaterialTheme.typography.bodySmall,
            color = SlateText,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(text = "邀请家人", onClick = onOpenInvite)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SlateText)
    }
}

private data class PermissionBannerSpec(
    val text: String,
    val tone: BannerTone,
    val actionLabel: String,
    val action: () -> Unit,
)

private fun navigateToMember(context: Context, member: MemberUi) {
    val lat = member.lat ?: return
    val lng = member.lng ?: return
    val label = Uri.encode(member.nickname)
    val amapUri = Uri.parse(
        "androidamap://viewMap?sourceApplication=whereareu&poiname=$label&lat=$lat&lon=$lng&dev=0",
    )
    val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, amapUri),
        Intent(Intent.ACTION_VIEW, geoUri),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next map app.
        } catch (_: Exception) {
            // Try the next map app.
        }
    }
    context.toast("未找到可用的地图应用")
}
