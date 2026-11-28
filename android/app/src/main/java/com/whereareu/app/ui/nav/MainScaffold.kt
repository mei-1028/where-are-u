package com.whereareu.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whereareu.app.core.AppGraph
import com.whereareu.app.ui.alerts.AlertsScreen
import com.whereareu.app.ui.family.FamilyScreen
import com.whereareu.app.ui.family.InviteScreen
import com.whereareu.app.ui.history.HistoryScreen
import com.whereareu.app.ui.map.MapHomeScreen
import com.whereareu.app.ui.places.PlacesScreen
import com.whereareu.app.ui.settings.SettingsScreen
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.White

private enum class MainTab(val label: String, val icon: ImageVector) {
    MAP("地图", Icons.Filled.Map),
    FAMILY("家庭", Icons.Filled.Group),
    SETTINGS("设置", Icons.Filled.Settings),
}

private const val OVERLAY_HISTORY = "history"
private const val OVERLAY_INVITE = "invite"
private const val OVERLAY_ALERTS = "alerts"
private const val OVERLAY_PLACES = "places"

@Composable
fun MainScaffold(
    onLeftFamily: () -> Unit,
    onLoggedOut: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    var selectedTabName by rememberSaveable { mutableStateOf(MainTab.MAP.name) }
    val selectedTab = MainTab.entries.firstOrNull { it.name == selectedTabName } ?: MainTab.MAP

    // History/Invite are in-app overlays, not NavHost destinations, so the
    // persistent map host (and its MapView) survives while they are open.
    var overlayRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var historyDeviceId by rememberSaveable { mutableStateOf("") }
    var historyName by rememberSaveable { mutableStateOf("") }
    val overlayInteraction = remember { MutableInteractionSource() }

    val pendingAlerts by AppGraph.pendingAlertsOpen.collectAsStateWithLifecycle()
    val pendingFocus by AppGraph.pendingMemberFocus.collectAsStateWithLifecycle()

    // Notification taps: bring the map (and optionally the inbox) to the front.
    LaunchedEffect(pendingAlerts) {
        if (pendingAlerts) {
            selectedTabName = MainTab.MAP.name
            overlayRoute = OVERLAY_ALERTS
            AppGraph.pendingAlertsOpen.value = false
        }
    }
    LaunchedEffect(pendingFocus) {
        if (pendingFocus != null) {
            selectedTabName = MainTab.MAP.name
            overlayRoute = null
        }
    }

    BackHandler(enabled = overlayRoute != null) {
        overlayRoute = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = White,
        bottomBar = {
            Column {
                HorizontalDivider(color = BorderHairline, thickness = 1.dp)
                NavigationBar(
                    containerColor = White,
                    tonalElevation = 0.dp,
                ) {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { selectedTabName = tab.name },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            label = {
                                Text(tab.label, style = MaterialTheme.typography.labelSmall)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MiroBlue,
                                selectedTextColor = MiroBlue,
                                indicatorColor = MiroBlueSoft,
                                unselectedIconColor = SlateText,
                                unselectedTextColor = SlateText,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            // Persistent map host. It stays composed while other tabs or the
            // history/invite overlays are shown, so the AMap MapView is never
            // destroyed/recreated on navigation. Input is disabled when hidden.
            MapHomeScreen(
                onOpenHistory = { deviceId, name ->
                    historyDeviceId = deviceId
                    historyName = name
                    overlayRoute = OVERLAY_HISTORY
                },
                onOpenInvite = { overlayRoute = OVERLAY_INVITE },
                onOpenAlerts = { overlayRoute = OVERLAY_ALERTS },
                onSessionExpired = onSessionExpired,
                onFamilyRemoved = onLeftFamily,
                active = selectedTab == MainTab.MAP && overlayRoute == null,
            )

            if (selectedTab != MainTab.MAP) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(White)
                        // Consume taps that don't hit a control so they cannot
                        // leak to the paused map underneath.
                        .clickable(
                            interactionSource = overlayInteraction,
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    when (selectedTab) {
                        MainTab.FAMILY -> FamilyScreen(
                            onOpenInvite = { overlayRoute = OVERLAY_INVITE },
                            onLeftFamily = onLeftFamily,
                        )

                        MainTab.SETTINGS -> SettingsScreen(
                            onLoggedOut = onLoggedOut,
                            onOpenPlaces = { overlayRoute = OVERLAY_PLACES },
                        )

                        MainTab.MAP -> Unit
                    }
                }
            }
        }
    }

        when (overlayRoute) {
            OVERLAY_HISTORY -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(White)
                    .clickable(
                        interactionSource = overlayInteraction,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                HistoryScreen(
                    deviceId = historyDeviceId,
                    memberName = historyName,
                    onBack = { overlayRoute = null },
                )
            }

            OVERLAY_INVITE -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(White)
                    .clickable(
                        interactionSource = overlayInteraction,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                InviteScreen(onBack = { overlayRoute = null })
            }

            OVERLAY_ALERTS -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(White)
                    .clickable(
                        interactionSource = overlayInteraction,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                AlertsScreen(
                    onBack = { overlayRoute = null },
                    onOpenMember = { userId ->
                        AppGraph.requestMemberFocus(userId)
                        selectedTabName = MainTab.MAP.name
                        overlayRoute = null
                    },
                )
            }

            OVERLAY_PLACES -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(White)
                    .clickable(
                        interactionSource = overlayInteraction,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                PlacesScreen(onBack = { overlayRoute = null })
            }
        }
    }
}
