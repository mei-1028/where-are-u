package com.whereareu.app.ui.history

import android.app.DatePickerDialog
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.util.GeoUtils
import com.whereareu.app.core.util.TimeUtils
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.EmptyStateCard
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.MiroTopBar
import com.whereareu.app.ui.components.SecondaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.map.TrackMap
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HistoryScreen(
    deviceId: String,
    memberName: String,
    onBack: () -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactoryFor {
            HistoryViewModel(
                deviceId = deviceId,
                memberName = memberName,
                historyRepository = AppGraph.historyRepository,
            )
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun openDatePicker() {
        val maxDate = LocalDate.now()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .minusMillis(1)
            .toEpochMilli()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                viewModel.selectDate(LocalDate.of(year, month + 1, day))
            },
            uiState.selectedDate.year,
            uiState.selectedDate.monthValue - 1,
            uiState.selectedDate.dayOfMonth,
        ).apply {
            datePicker.maxDate = maxDate
        }.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .navigationBarsPadding(),
    ) {
        MiroTopBar(
            title = "${uiState.memberName}的轨迹",
            subtitle = dateLabel(uiState.selectedDate),
            onBack = onBack,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateChip(
                label = "今天",
                selected = uiState.selectedDate == LocalDate.now(),
                onClick = { viewModel.selectDaysAgo(0) },
            )
            DateChip(
                label = "昨天",
                selected = uiState.selectedDate == LocalDate.now().minusDays(1),
                onClick = { viewModel.selectDaysAgo(1) },
            )
            DateChip(
                label = "选择日期",
                selected = uiState.selectedDate != LocalDate.now() &&
                    uiState.selectedDate != LocalDate.now().minusDays(1),
                onClick = { openDatePicker() },
            )
        }

        Spacer(Modifier.height(12.dp))

        if (uiState.error != null) {
            StatusBanner(
                text = uiState.error.orEmpty(),
                tone = BannerTone.Error,
                actionLabel = "重试",
                onAction = viewModel::reload,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceMuted),
        ) {
            val points = uiState.points
            if (points.isNotEmpty()) {
                TrackMap(
                    points = points.map { it.lat to it.lng },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (uiState.loading) {
                LoadingState(message = "正在加载轨迹…")
            } else if (uiState.error == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateCard(
                        title = "这一天还没有轨迹",
                        message = "对方开启位置共享后，轨迹会在每天上报后出现在这里。",
                        icon = Icons.Filled.History,
                    )
                }
            }

            if (BuildConfig.AMAP_KEY.isBlank() && points.isNotEmpty()) {
                Text(
                    text = "示意轨迹",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .clip(CircleShape)
                        .background(White.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        if (pointsNotEmpty(uiState)) {
            TrackSummaryCard(
                summary = uiState.summary,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun pointsNotEmpty(state: HistoryUiState): Boolean =
    !state.loading && state.points.isNotEmpty()

@Composable
private fun DateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MiroBlueSoft else White)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MiroBlue else BorderHairline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MiroBlue else Ink,
        )
    }
}

@Composable
private fun TrackSummaryCard(
    summary: HistorySummary,
    modifier: Modifier = Modifier,
) {
    MiroCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryStat(
                label = "里程",
                value = GeoUtils.formatDistance(summary.distanceMeters),
                modifier = Modifier.weight(1f),
            )
            SummaryStat(
                label = "时长",
                value = TimeUtils.formatDuration(summary.durationMillis),
                modifier = Modifier.weight(1f),
            )
            SummaryStat(
                label = "轨迹点",
                value = "${summary.pointCount}",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = BorderHairline)
        Spacer(Modifier.height(10.dp))
        val firstAt = summary.firstAtMillis
        val lastAt = summary.lastAtMillis
        Text(
            text = if (firstAt != null && lastAt != null) {
                "${TimeUtils.formatDateTime(firstAt)} — ${TimeUtils.formatClock(lastAt)}"
            } else {
                "时间信息不可用"
            },
            style = MaterialTheme.typography.bodySmall,
            color = SlateText,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = "轨迹回放（M1 暂不支持）",
            onClick = {},
            enabled = false,
        )
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SlateText)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Ink)
    }
}

private fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天 · ${date.monthValue}月${date.dayOfMonth}日"
        today.minusDays(1) -> "昨天 · ${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}
