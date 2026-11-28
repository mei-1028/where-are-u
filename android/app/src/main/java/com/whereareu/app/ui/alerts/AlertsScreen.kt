package com.whereareu.app.ui.alerts

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.AlertDto
import com.whereareu.app.core.util.TimeUtils
import com.whereareu.app.ui.components.CircleIconButton
import com.whereareu.app.ui.components.EmptyStateCard
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.MiroTopBar
import com.whereareu.app.ui.components.SectionHeader
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.CoralPastel
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.OrangePastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor

private data class AlertTypeUi(
    val icon: ImageVector,
    val background: Color,
    val label: String,
)

private fun typeUi(type: String): AlertTypeUi = when (type) {
    "MOVEMENT" -> AlertTypeUi(Icons.AutoMirrored.Filled.TrendingUp, MiroBlueSoft, "位置变动")
    "STALE" -> AlertTypeUi(Icons.Filled.Schedule, OrangePastel, "长时间未更新")
    "LOW_BATTERY" -> AlertTypeUi(Icons.Filled.BatteryAlert, CoralPastel, "低电量")
    else -> AlertTypeUi(Icons.Filled.Notifications, SurfaceMuted, "提醒")
}

@Composable
fun AlertsScreen(
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
) {
    val viewModel: AlertsViewModel = viewModel(
        factory = viewModelFactoryFor { AlertsViewModel(AppGraph.alertsRepository) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(White)) {
        MiroTopBar(
            title = "消息",
            subtitle = if (state.unreadCount > 0) {
                "${state.unreadCount} 条未读"
            } else {
                "关注提醒"
            },
            onBack = onBack,
            actions = {
                TextButton(
                    onClick = viewModel::markAllRead,
                    enabled = state.unreadCount > 0,
                ) {
                    Text("全部已读", color = if (state.unreadCount > 0) MiroBlue else SlateText)
                }
                CircleIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新消息",
                    onClick = viewModel::refresh,
                )
            },
        )
        HorizontalDivider(color = BorderHairline, thickness = 1.dp)

        when {
            state.loading && state.alerts.isEmpty() -> LoadingState("正在加载消息…")

            state.message != null && state.alerts.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                EmptyStateCard(
                    title = "消息加载失败",
                    message = state.message ?: "请稍后重试",
                    actionLabel = "重试",
                    onAction = viewModel::refresh,
                    icon = Icons.Filled.Notifications,
                )
            }

            state.alerts.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                EmptyStateCard(
                    title = "还没有提醒",
                    message = "在地图上打开一位家人，点「关注」即可设置位置变动、失联和低电量提醒。",
                    icon = Icons.Filled.Notifications,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.alerts, key = { it.id }) { alert ->
                    AlertRow(
                        alert = alert,
                        onClick = {
                            viewModel.markRead(alert)
                            onOpenMember(alert.target.userId)
                        },
                    )
                }

                if (state.followers.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        SectionHeader("谁在关注我")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceMuted)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            state.followers.forEach { follower ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Notifications,
                                        contentDescription = null,
                                        tint = SlateText,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        follower.nickname,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        TimeUtils.relativeTime(follower.since),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: AlertDto, onClick: () -> Unit) {
    val ui = typeUi(alert.type)
    val unread = alert.readAt == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (unread) White else SurfaceMuted.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ui.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ui.icon, contentDescription = null, tint = Ink, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alert.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread) Ink else SlateText,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    TimeUtils.relativeTime(alert.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = alert.body,
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (alert.silent) "${ui.label} · 夜间免打扰" else ui.label,
                style = MaterialTheme.typography.labelSmall,
                color = SlateText,
            )
        }
        if (unread) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MiroBlue),
            )
        }
    }
}
