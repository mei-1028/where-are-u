package com.whereareu.app.ui.family

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.ConfirmDialog
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.MemberAvatar
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.PastelTag
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.components.StatusPill
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.DangerSurface
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SuccessGreen
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor
import kotlinx.coroutines.launch

@Composable
fun FamilyScreen(
    onOpenInvite: () -> Unit,
    onLeftFamily: () -> Unit,
) {
    val viewModel: FamilyViewModel = viewModel(
        factory = viewModelFactoryFor {
            FamilyViewModel(
                familyRepository = AppGraph.familyRepository,
                sessionStore = AppGraph.sessionStore,
                familySessionManager = AppGraph.familySessionManager,
            )
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingRemove by remember { mutableStateOf<FamilyMemberUi?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        context.toast(message)
        viewModel.clearMessage()
    }
    LaunchedEffect(uiState.leftFamily) {
        if (uiState.leftFamily) {
            viewModel.consumeLeftFamily()
            // FamilySessionManager already stopped tracking and cleared the
            // device token; only local queue cleanup remains here.
            AppGraph.offlineQueue.clear()
            onLeftFamily()
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
        Text("家庭", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "管理家庭成员和邀请。位置只对同一家庭的成员可见。",
            style = MaterialTheme.typography.bodySmall,
            color = SlateText,
        )
        Spacer(Modifier.height(20.dp))

        if (uiState.loading && uiState.members.isEmpty()) {
            LoadingState(message = "正在加载家庭信息…", modifier = Modifier.height(240.dp))
        } else {
            if (uiState.error != null) {
                StatusBanner(
                    text = uiState.error.orEmpty(),
                    tone = BannerTone.Error,
                    actionLabel = "重试",
                    onAction = viewModel::load,
                )
                Spacer(Modifier.height(12.dp))
            }

            MiroCard(
                background = MiroBlueSoft,
                borderColor = Color(0xFFD6DDFF),
                cornerRadius = 20.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.familyName.ifBlank { "我的家庭" },
                            style = MaterialTheme.typography.titleLarge,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${uiState.members.size} 位成员",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    PastelTag(
                        text = if (uiState.isAdmin) "管理员" else "成员",
                        background = if (uiState.isAdmin) White else SurfaceMuted,
                        foreground = if (uiState.isAdmin) MiroBlue else SlateText,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "成员",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "共享状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                )
            }
            Spacer(Modifier.height(8.dp))

            MiroCard(cornerRadius = 20.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                uiState.members.forEachIndexed { index, member ->
                    FamilyMemberRow(
                        member = member,
                        colorIndex = index,
                        isMe = member.userId == uiState.myUserId,
                        canRemove = uiState.isAdmin && member.userId != uiState.myUserId && !uiState.busy,
                        onRemove = { pendingRemove = member },
                    )
                    if (index != uiState.members.lastIndex) {
                        HorizontalDivider(color = BorderHairline)
                    }
                }
                if (uiState.members.isEmpty()) {
                    Text(
                        text = "还没有成员信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (uiState.isAdmin) {
                PrimaryButton(
                    text = "邀请家人",
                    onClick = onOpenInvite,
                )
            } else {
                Text(
                    text = "仅管理员可以邀请家人。",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                )
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = if (uiState.isAdmin) {
                    "管理员不能直接退出家庭（M1 暂不支持转让或解散）。"
                } else {
                    "退出后，你的设备将停止上报位置，家人将看不到你的位置。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = SlateText,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showLeaveConfirm = true },
                enabled = !uiState.isAdmin && !uiState.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed),
            ) {
                Text(
                    text = if (uiState.isAdmin) "管理员暂不可退出" else "退出家庭",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (uiState.isAdmin) SlateText else DangerRed,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRemove?.let { member ->
        ConfirmDialog(
            title = "移除 ${member.nickname}？",
            message = "移除后，对方会立即停止上报，并可重新加入其他家庭。此操作无法撤销。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                pendingRemove = null
                viewModel.removeMember(member)
            },
            onDismiss = { pendingRemove = null },
        )
    }

    if (showLeaveConfirm) {
        ConfirmDialog(
            title = "退出家庭？",
            message = "退出后家人将看不到你的位置，你可以用新的邀请码重新加入。",
            confirmLabel = "退出",
            destructive = true,
            onConfirm = {
                showLeaveConfirm = false
                viewModel.leaveFamily()
            },
            onDismiss = { showLeaveConfirm = false },
        )
    }
}

@Composable
private fun FamilyMemberRow(
    member: FamilyMemberUi,
    colorIndex: Int,
    isMe: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(
            name = member.nickname,
            colorIndex = colorIndex,
            size = 42.dp,
            online = member.online,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = member.nickname,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isMe) {
                    StatusPill("我", MiroBlue, MiroBlueSoft)
                }
                if (member.isAdmin) {
                    StatusPill("管理员", Color(0xFF8A5A00), Color(0xFFFFF4E5))
                }
            }
            Text(
                text = if (member.online) "在线" else "离线",
                style = MaterialTheme.typography.labelSmall,
                color = if (member.online) SuccessGreen else SlateText,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!isMe) {
            StatusPill(
                text = if (member.sharingPaused) "已暂停" else "共享中",
                foreground = if (member.sharingPaused) Color(0xFF8A5A00) else SuccessGreen,
                background = if (member.sharingPaused) Color(0xFFFFF4E5) else Color(0xFFE3F8EF),
            )
        }
        if (canRemove) {
            Spacer(Modifier.width(6.dp))
            LinkText(
                text = "移除",
                onClick = onRemove,
                color = DangerRed,
            )
        }
    }
}
