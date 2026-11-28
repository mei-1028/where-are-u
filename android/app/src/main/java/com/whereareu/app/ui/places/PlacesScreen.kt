package com.whereareu.app.ui.places

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.PlaceDto
import com.whereareu.app.ui.components.CircleIconButton
import com.whereareu.app.ui.components.EmptyStateCard
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.MiroTopBar
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.OrangePastel
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SurfaceMuted
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor

private data class PlaceTypeUi(
    val icon: ImageVector,
    val background: Color,
    val label: String,
)

private fun typeUi(type: String): PlaceTypeUi = when (type) {
    "home" -> PlaceTypeUi(Icons.Filled.Home, TealPastel, "家")
    "school" -> PlaceTypeUi(Icons.Filled.School, MiroBlueSoft, "学校")
    "company" -> PlaceTypeUi(Icons.Filled.Business, OrangePastel, "公司")
    else -> PlaceTypeUi(Icons.Filled.Place, PinkPastel, "其他")
}

@Composable
fun PlacesScreen(onBack: () -> Unit) {
    val viewModel: PlacesViewModel = viewModel(
        factory = viewModelFactoryFor { PlacesViewModel(AppGraph.placesRepository) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session by AppGraph.sessionStore.state.collectAsStateWithLifecycle(
        initialValue = null,
    )

    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaceDto?>(null) }

    fun canManage(place: PlaceDto): Boolean =
        place.createdBy == session?.userId || session?.familyRole == "admin"

    Column(modifier = Modifier.fillMaxSize().background(White)) {
        MiroTopBar(
            title = "家庭地点",
            subtitle = "${state.places.size} 个地点 · 在地图上长按添加",
            onBack = onBack,
            actions = {
                CircleIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新地点",
                    onClick = viewModel::refresh,
                )
            },
        )
        HorizontalDivider(color = BorderHairline, thickness = 1.dp)

        when {
            state.loading && state.places.isEmpty() -> LoadingState("正在加载地点…")

            state.error != null && state.places.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                EmptyStateCard(
                    title = "地点加载失败",
                    message = state.error ?: "请稍后重试",
                    actionLabel = "重试",
                    onAction = viewModel::refresh,
                    icon = Icons.Filled.Place,
                )
            }

            state.places.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                EmptyStateCard(
                    title = "还没有家庭地点",
                    message = "回到地图，长按任意位置即可添加家、学校或公司。",
                    icon = Icons.Filled.AddLocationAlt,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.places, key = { it.id }) { place ->
                    PlaceRow(
                        place = place,
                        onClick = {
                            editing = place
                            editorOpen = true
                        },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    MiroCard(background = SurfaceMuted, borderColor = BorderHairline) {
                        Text(
                            text = "在地图上长按（桌面端右键）可添加新地点；半径内的进出会触发围栏提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                        )
                    }
                }
            }
        }
    }

    if (editorOpen) {
        val target = editing
        PlaceEditorSheet(
            place = target,
            draft = null,
            canManage = target == null || canManage(target),
            onDismiss = { editorOpen = false },
            onSaved = viewModel::upsert,
            onDeleted = { placeId -> target?.let { viewModel.remove(it.id) } },
        )
    }
}

@Composable
private fun PlaceRow(place: PlaceDto, onClick: () -> Unit) {
    val ui = typeUi(place.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
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
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(
                text = buildString {
                    append(ui.label)
                    append(" · 半径 ")
                    append(place.radiusMeters)
                    append(" 米")
                    if (place.usedByRules > 0) {
                        append(" · ")
                        append(place.usedByRules)
                        append(" 条提醒")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
            )
        }
        Icon(
            Icons.Filled.Place,
            contentDescription = null,
            tint = MiroBlue,
            modifier = Modifier.size(16.dp),
        )
    }
}
