package com.whereareu.app.ui.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whereareu.app.core.model.LocateUiState
import com.whereareu.app.core.util.TimeUtils
import com.whereareu.app.core.util.formatCoordinates
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.CircleIconButton
import com.whereareu.app.ui.components.MemberAvatar
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.SecondaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.components.StatusPill
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.OfflineGray
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SuccessGreen
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.White
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailSheet(
    member: MemberUi,
    colorIndex: Int,
    isMe: Boolean,
    locateState: LocateUiState,
    onDismiss: () -> Unit,
    onLocate: () -> Unit,
    onHistory: () -> Unit,
    onNavigate: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coordinates = if (member.hasLocation) {
        formatCoordinates(member.lat ?: 0.0, member.lng ?: 0.0)
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(BorderHairline),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(
                    name = member.nickname,
                    colorIndex = colorIndex,
                    size = 54.dp,
                    online = member.online,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.nickname,
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            text = if (member.online) "在线" else "离线",
                            foreground = if (member.online) SuccessGreen else OfflineGray,
                            background = if (member.online) {
                                androidx.compose.ui.graphics.Color(0xFFE3F8EF)
                            } else {
                                SurfaceMuted
                            },
                        )
                        if (member.isAdmin) {
                            StatusPill(
                                text = "管理员",
                                foreground = MiroBlue,
                                background = com.whereareu.app.ui.theme.MiroBlueSoft,
                            )
                        }
                        if (member.sharingPaused) {
                            StatusPill(
                                text = "已暂停共享",
                                foreground = com.whereareu.app.ui.theme.WarningAmber,
                                background = com.whereareu.app.ui.theme.WarningSurface,
                            )
                        }
                    }
                }
                CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "关闭",
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = when {
                    !member.hasLocation -> "暂无位置"
                    else -> member.address ?: "正在解析地址…"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (member.isStale && member.hasLocation) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "位置可能已过期，可尝试立即定位",
                    style = MaterialTheme.typography.labelSmall,
                    color = DangerRed,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(
                    label = "最后更新",
                    value = TimeUtils.relativeFromEpoch(member.recordedAtMillis),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "精度",
                    value = member.accuracy?.let { "±${it.toInt()} m" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(
                    label = "电量",
                    value = member.battery?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = "速度",
                    value = member.speed?.let {
                        String.format(Locale.CHINA, "%.0f km/h", it * 3.6f)
                    } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }

            if (coordinates != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("坐标", style = MaterialTheme.typography.labelSmall, color = SlateText)
                        Text(coordinates, style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("位置坐标", coordinates))
                            context.toast("坐标已复制")
                        },
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelMedium, color = MiroBlue)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            when (locateState) {
                is LocateUiState.Requesting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MiroBlue,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在请求对方定位…",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                        )
                    }
                }

                is LocateUiState.Success -> {
                    Text(
                        "已获取最新位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen,
                    )
                }

                is LocateUiState.Failed -> {
                    StatusBanner(text = locateState.message, tone = BannerTone.Error)
                }

                LocateUiState.Idle -> {
                    if (!isMe && !member.online) {
                        Text(
                            "对方当前离线，定位可能失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (isMe) {
                Text(
                    text = "这是你自己的位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SecondaryButton(
                    text = "查看我的轨迹",
                    onClick = onHistory,
                    enabled = member.deviceId != null,
                )
            } else {
                PrimaryButton(
                    text = "立即定位",
                    loading = locateState is LocateUiState.Requesting,
                    loadingText = "正在请求对方定位…",
                    enabled = member.deviceId != null,
                    onClick = onLocate,
                )
                if (member.deviceId == null) {
                    Text(
                        "缺少设备信息，无法发起立即定位",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateText,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "历史轨迹",
                        onClick = onHistory,
                        enabled = member.deviceId != null,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "导航",
                        onClick = onNavigate,
                        enabled = member.hasLocation,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SlateText)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
