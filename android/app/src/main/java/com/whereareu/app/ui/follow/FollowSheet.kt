package com.whereareu.app.ui.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.AlertRuleDto
import com.whereareu.app.core.net.FollowDto
import com.whereareu.app.core.util.toast
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.ui.components.ChoiceOption
import com.whereareu.app.ui.components.ChoicePills
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.map.MemberUi
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.White
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private data class RuleTypeUi(
    val type: String,
    val title: String,
    val desc: String,
)

private val RULE_TYPES = listOf(
    RuleTypeUi("MOVEMENT", "位置变动", "移动超过设定距离时提醒；冷却时间内不重复"),
    RuleTypeUi("STALE", "长时间未更新", "超过设定时间没有收到新位置时提醒"),
    RuleTypeUi("LOW_BATTERY", "低电量", "电量低于设定值时提醒（夜间只进消息中心）"),
)

private val MOVEMENT_THRESHOLDS = listOf(200, 500, 1000, 2000)
private val MOVEMENT_COOLDOWNS = listOf(15, 30, 60)
private val STALE_HOURS = listOf(6, 12, 24, 48)
private val BATTERY_PERCENTS = listOf(10, 20, 30)

private fun AlertRuleDto.intConfig(key: String): Int? =
    config?.get(key)?.let { element ->
        runCatching { element.jsonPrimitive.intOrNull }.getOrNull()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowSheet(
    member: MemberUi,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = AppGraph.alertsRepository
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var follow by remember { mutableStateOf<FollowDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var selectedTypes by remember { mutableStateOf(setOf("MOVEMENT", "STALE")) }
    var confirmingUnfollow by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            repository.follows()
                .onSuccess { list ->
                    follow = list.firstOrNull { it.target.userId == member.userId }
                }
                .onFailure { context.toast("关注信息加载失败：${it.toUserMessage()}") }
            loading = false
        }
    }

    fun replaceRule(updated: AlertRuleDto) {
        val current = follow ?: return
        val exists = current.rules.any { it.id == updated.id }
        follow = current.copy(
            rules = if (exists) {
                current.rules.map { if (it.id == updated.id) updated else it }
            } else {
                current.rules + updated
            },
        )
    }

    fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (error: Throwable) {
                context.toast(error.toUserMessage())
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(member.userId) { reload() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "关注 ${member.nickname}",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "关注后，对方的位置变化会出现在消息中心，并可弹出系统通知。对方可以看到谁在关注自己。",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading -> LoadingState("正在加载关注设置…")

                follow == null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RULE_TYPES.forEach { item ->
                            val checked = item.type in selectedTypes
                            RuleRow(
                                title = item.title,
                                desc = item.desc,
                                checked = checked,
                                enabled = !busy,
                                onCheckedChange = {
                                    selectedTypes = if (checked) {
                                        selectedTypes - item.type
                                    } else {
                                        selectedTypes + item.type
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = "开始关注",
                        loading = busy,
                        onClick = {
                            if (selectedTypes.isEmpty()) {
                                context.toast("至少选择一种提醒")
                                return@PrimaryButton
                            }
                            launchBusy {
                                repository.createFollow(member.userId, selectedTypes.toList())
                                    .onSuccess {
                                        follow = it
                                        context.toast("已关注${member.nickname}")
                                    }
                                    .onFailure { throw it }
                            }
                        },
                    )
                }

                else -> {
                    val current = follow!!
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RULE_TYPES.forEach { item ->
                            val rule = current.rules.firstOrNull { it.type == item.type }
                            val enabled = rule?.enabled == true
                            RuleRow(
                                title = item.title,
                                desc = item.desc,
                                checked = enabled,
                                enabled = !busy,
                                onCheckedChange = { wanted ->
                                    launchBusy {
                                        if (rule == null) {
                                            repository.createRule(current.id, item.type)
                                                .onSuccess(::replaceRule)
                                                .onFailure { throw it }
                                        } else {
                                            repository.updateRule(
                                                current.id,
                                                rule.id,
                                                enabled = wanted,
                                            ).onSuccess(::replaceRule).onFailure { throw it }
                                        }
                                    }
                                },
                            )
                            if (rule != null && enabled) {
                                RuleConfig(
                                    rule = rule,
                                    busy = busy,
                                    onChange = { config ->
                                        launchBusy {
                                            repository.updateRule(
                                                current.id,
                                                rule.id,
                                                config = config,
                                            ).onSuccess(::replaceRule).onFailure { throw it }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    if (confirmingUnfollow) {
                        Text(
                            text = "取消后不再收到 ${member.nickname} 的提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                onClick = {
                                    launchBusy {
                                        repository.deleteFollow(current.id)
                                            .onSuccess {
                                                follow = null
                                                confirmingUnfollow = false
                                                context.toast("已取消关注")
                                            }
                                            .onFailure { throw it }
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text("确认取消", color = DangerRed)
                            }
                            TextButton(onClick = { confirmingUnfollow = false }) {
                                Text("再想想", color = SlateText)
                            }
                        }
                    } else {
                        TextButton(onClick = { confirmingUnfollow = true }) {
                            Text("取消关注", color = DangerRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) MiroBlue.copy(alpha = 0.06f) else SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = SlateText)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = White,
                checkedTrackColor = MiroBlue,
            ),
        )
    }
}

@Composable
private fun RuleConfig(
    rule: AlertRuleDto,
    busy: Boolean,
    onChange: (kotlinx.serialization.json.JsonObject) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (rule.type) {
            "MOVEMENT" -> {
                ConfigRow(
                    label = "移动超过",
                    options = MOVEMENT_THRESHOLDS.map {
                        it.toString() to if (it >= 1000) "${it / 1000} 公里" else "$it 米"
                    },
                    selected = rule.intConfig("thresholdMeters") ?: 500,
                    busy = busy,
                    onSelect = { value ->
                        onChange(buildJsonObject { put("thresholdMeters", value) })
                    },
                )
                ConfigRow(
                    label = "提醒冷却",
                    options = MOVEMENT_COOLDOWNS.map { it.toString() to "$it 分钟" },
                    selected = rule.intConfig("cooldownMinutes") ?: 30,
                    busy = busy,
                    onSelect = { value ->
                        onChange(buildJsonObject { put("cooldownMinutes", value) })
                    },
                )
            }

            "STALE" -> ConfigRow(
                label = "超过",
                options = STALE_HOURS.map { it.toString() to "$it 小时" },
                selected = rule.intConfig("hours") ?: 12,
                busy = busy,
                onSelect = { value -> onChange(buildJsonObject { put("hours", value) }) },
            )

            else -> ConfigRow(
                label = "低于",
                options = BATTERY_PERCENTS.map { it.toString() to "$it%" },
                selected = rule.intConfig("percent") ?: 20,
                busy = busy,
                onSelect = { value -> onChange(buildJsonObject { put("percent", value) }) },
            )
        }
    }
}

@Composable
private fun ConfigRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: Int,
    busy: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SlateText,
            modifier = Modifier.width(64.dp),
        )
        ChoicePills(
            options = options.map { (key, title) -> ChoiceOption(key = key, title = title) },
            selectedKey = selected.toString(),
            onSelect = { key -> key.toIntOrNull()?.let(onSelect) },
            modifier = Modifier.weight(1f),
        )
    }
}
