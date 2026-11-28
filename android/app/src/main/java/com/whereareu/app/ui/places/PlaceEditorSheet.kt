package com.whereareu.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.PlaceDto
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.ChoiceOption
import com.whereareu.app.ui.components.ChoicePills
import com.whereareu.app.ui.components.MiroTextField
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.SlateText
import kotlinx.coroutines.launch

private val PLACE_TYPES = listOf(
    "home" to "家",
    "school" to "学校",
    "company" to "公司",
    "other" to "其他",
)

private val PLACE_RADII = listOf(100, 200, 500, 1000)

/**
 * Create or edit a family place. Used from the map (long-press) and from the
 * settings list. Coordinates are GCJ-02, matching the map and location ingest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceEditorSheet(
    place: PlaceDto?,
    draft: Pair<Double, Double>?,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onSaved: (PlaceDto) -> Unit,
    onDeleted: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repository = AppGraph.placesRepository

    val lat = place?.lat ?: draft?.first ?: 0.0
    val lng = place?.lng ?: draft?.second ?: 0.0

    var name by remember { mutableStateOf(place?.name ?: "") }
    var type by remember { mutableStateOf(place?.type ?: "other") }
    var radius by remember { mutableIntStateOf(place?.radiusMeters ?: 200) }
    var address by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(place?.id, draft) {
        if (lat == 0.0 && lng == 0.0) return@LaunchedEffect
        val resolved = AppGraph.addressResolver.resolve(lat, lng)
        address = resolved
        if (place == null && name.isBlank()) {
            name = resolved.take(20)
        }
    }

    fun save() {
        if (busy || !canManage) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = "请填写地点名称"
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = if (place == null) {
                repository.create(
                    name = trimmed,
                    type = type,
                    lat = lat,
                    lng = lng,
                    radiusMeters = radius,
                )
            } else {
                repository.update(
                    placeId = place.id,
                    name = trimmed,
                    type = type,
                    radiusMeters = radius,
                )
            }
            result
                .onSuccess {
                    context.toast(if (place == null) "地点已添加" else "地点已更新")
                    onSaved(it)
                    onDismiss()
                }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    fun remove() {
        val current = place ?: return
        if (busy || !canManage) return
        busy = true
        error = null
        scope.launch {
            repository.delete(current.id)
                .onSuccess {
                    context.toast(
                        if (it.removedRules > 0) {
                            "地点已删除，同时移除 ${it.removedRules} 条围栏提醒"
                        } else {
                            "地点已删除"
                        },
                    )
                    onDeleted(it.removedRules)
                    onDismiss()
                }
                .onFailure { error = it.toUserMessage() }
            busy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.whereareu.app.ui.theme.White,
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
                text = if (place == null) "添加地点" else "编辑地点",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = address ?: "正在解析地址…",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
            )
            Spacer(Modifier.height(16.dp))

            MiroTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                label = "名称",
                placeholder = "例如：学校、外婆家",
                error = error,
            )
            Spacer(Modifier.height(16.dp))

            Text("类型", style = MaterialTheme.typography.labelMedium, color = SlateText)
            Spacer(Modifier.height(6.dp))
            ChoicePills(
                options = PLACE_TYPES.map { (key, label) ->
                    ChoiceOption(key = key, title = label)
                },
                selectedKey = type,
                onSelect = { type = it },
            )
            Spacer(Modifier.height(16.dp))

            Text("半径", style = MaterialTheme.typography.labelMedium, color = SlateText)
            Spacer(Modifier.height(6.dp))
            ChoicePills(
                options = PLACE_RADII.map { ChoiceOption(key = it.toString(), title = "$it 米") },
                selectedKey = radius.toString(),
                onSelect = { radius = it.toIntOrNull() ?: radius },
            )

            if (!canManage) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "只有地点的创建者或家庭管理员可以修改这个地点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                )
            }

            Spacer(Modifier.height(20.dp))
            if (canManage) {
                PrimaryButton(
                    text = if (place == null) "添加地点" else "保存修改",
                    loading = busy,
                    onClick = ::save,
                )
            } else {
                PrimaryButton(text = "关闭", onClick = onDismiss)
            }

            if (place != null && canManage) {
                Spacer(Modifier.height(10.dp))
                if (confirmingDelete) {
                    Text(
                        text = "删除后，引用它的 ${place.usedByRules} 条围栏提醒也会移除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = ::remove, enabled = !busy) {
                            Text("确认删除", color = DangerRed)
                        }
                        TextButton(onClick = { confirmingDelete = false }) {
                            Text("再想想", color = SlateText)
                        }
                    }
                } else {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text("删除地点", color = DangerRed)
                    }
                }
            }
        }
    }
}
