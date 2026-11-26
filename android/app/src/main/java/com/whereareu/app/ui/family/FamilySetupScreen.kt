package com.whereareu.app.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.BrandMark
import com.whereareu.app.ui.components.ConfirmDialog
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.MiroTextField
import com.whereareu.app.ui.components.PastelTag
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor

@Composable
fun FamilySetupScreen(onNavigate: (String) -> Unit) {
    val viewModel: FamilySetupViewModel = viewModel(
        factory = viewModelFactoryFor {
            FamilySetupViewModel(
                familyRepository = AppGraph.familyRepository,
                authRepository = AppGraph.authRepository,
                sessionStore = AppGraph.sessionStore,
                familySessionManager = AppGraph.familySessionManager,
            )
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var familyName by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.navigateTo) {
        val destination = uiState.navigateTo ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onNavigate(destination)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = MiroSpacing.lg),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(compact = true)
            LinkText(
                text = "退出登录",
                onClick = { showLogoutConfirm = true },
                color = SlateText,
            )
        }
        Spacer(Modifier.height(36.dp))
        if (uiState.displayName.isNotBlank()) {
            Text(
                text = "你好，${uiState.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = SlateText,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text("加入一个家庭", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "创建一个新家庭，或者用家人提供的邀请码加入。同一时间只能加入一个家庭。",
            style = MaterialTheme.typography.bodyMedium,
            color = SlateText,
        )
        Spacer(Modifier.height(24.dp))

        if (uiState.error != null) {
            StatusBanner(text = uiState.error.orEmpty(), tone = BannerTone.Error)
            Spacer(Modifier.height(16.dp))
        }

        MiroCard(
            background = TealPastel.copy(alpha = 0.28f),
            borderColor = TealPastel,
            cornerRadius = 20.dp,
        ) {
            PastelTag("新建家庭", TealPastel)
            Spacer(Modifier.height(12.dp))
            Text("我是第一个用户", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                text = "创建后你将成为管理员，可以邀请家人。",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
            MiroTextField(
                value = familyName,
                onValueChange = {
                    familyName = it.take(20)
                    nameError = null
                    viewModel.clearError()
                },
                label = "家庭名称",
                placeholder = "例如：陈家",
                error = nameError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (familyName.isBlank()) nameError = "请输入家庭名称" else viewModel.createFamily(familyName)
                }),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "创建家庭",
                loading = uiState.busyAction == "create",
                enabled = !uiState.loading,
                onClick = {
                    if (familyName.isBlank()) nameError = "请输入家庭名称" else viewModel.createFamily(familyName)
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        MiroCard(
            background = PinkPastel.copy(alpha = 0.35f),
            borderColor = PinkPastel,
            cornerRadius = 20.dp,
        ) {
            PastelTag("受邀加入", PinkPastel)
            Spacer(Modifier.height(12.dp))
            Text("家人给了我邀请码", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                text = "邀请码为 6 位数字，24 小时内有效。",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
            MiroTextField(
                value = inviteCode,
                onValueChange = {
                    inviteCode = it.filter(Char::isDigit).take(6)
                    codeError = null
                    viewModel.clearError()
                },
                label = "邀请码",
                placeholder = "6 位数字",
                error = codeError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!inviteCode.matches(Regex("\\d{6}"))) {
                        codeError = "请输入 6 位数字邀请码"
                    } else {
                        viewModel.joinFamily(inviteCode)
                    }
                }),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "加入家庭",
                loading = uiState.busyAction == "join",
                enabled = !uiState.loading,
                onClick = {
                    if (!inviteCode.matches(Regex("\\d{6}"))) {
                        codeError = "请输入 6 位数字邀请码"
                    } else {
                        viewModel.joinFamily(inviteCode)
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "位置数据只保存在你自建的家庭服务器上，仅家庭成员可见。",
            style = MaterialTheme.typography.labelSmall,
            color = SlateText,
            modifier = Modifier.padding(bottom = 32.dp),
        )
    }

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "退出登录？",
            message = "退出后需要重新登录，已保存的服务器地址不会被清除。",
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
