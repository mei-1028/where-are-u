package com.whereareu.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.ServerConfig
import com.whereareu.app.ui.viewModelFactoryFor
import com.whereareu.app.ui.components.BrandMark
import com.whereareu.app.ui.components.CircleIconButton
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.MiroTextField
import com.whereareu.app.ui.components.PastelTag
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.White

@Composable
fun LoginScreen(
    onAuthenticated: (String) -> Unit,
    onOpenRegister: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel(
        factory = viewModelFactoryFor {
            AuthViewModel(AppGraph.authRepository, AppGraph.sessionStore)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showServerDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.navigateTo) {
        val destination = uiState.navigateTo ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onAuthenticated(destination)
    }

    fun submit() {
        val nameError = when {
            username.isBlank() -> "请输入用户名"
            username.trim().length < 3 -> "用户名至少 3 位"
            else -> null
        }
        val passError = when {
            password.isBlank() -> "请输入密码"
            password.length < 8 -> "密码至少 8 位"
            else -> null
        }
        usernameError = nameError
        passwordError = passError
        if (nameError == null && passError == null) {
            viewModel.login(username, password)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(White),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .height(180.dp)
                .width(180.dp)
                .background(PinkPastel.copy(alpha = 0.35f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = MiroSpacing.lg),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BrandMark(compact = true)
                CircleIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "服务器地址设置",
                    onClick = { showServerDialog = true },
                )
            }
            Spacer(Modifier.height(52.dp))
            PastelTag("仅家庭成员可见", TealPastel)
            Spacer(Modifier.height(16.dp))
            Text("欢迎回家", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "登录后查看家人的实时位置，也让家人知道你在哪。",
                style = MaterialTheme.typography.bodyMedium,
                color = SlateText,
            )
            Spacer(Modifier.height(32.dp))

            MiroTextField(
                value = username,
                onValueChange = {
                    username = it
                    usernameError = null
                    viewModel.clearError()
                },
                label = "用户名",
                placeholder = "3–20 位",
                error = usernameError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )
            Spacer(Modifier.height(16.dp))
            MiroTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordError = null
                    viewModel.clearError()
                },
                label = "密码",
                placeholder = "至少 8 位",
                error = passwordError,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Text(
                        text = if (showPassword) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = MiroBlue,
                        modifier = Modifier
                            .clickable { showPassword = !showPassword }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            if (uiState.error != null) {
                Spacer(Modifier.height(16.dp))
                StatusBanner(text = uiState.error.orEmpty(), tone = BannerTone.Error)
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "登录",
                loading = uiState.loading,
                onClick = { submit() },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("还没有账号？", style = MaterialTheme.typography.bodySmall, color = SlateText)
                LinkText(text = "注册", onClick = onOpenRegister)
            }

            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TealPastel.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .height(8.dp)
                        .width(8.dp)
                        .background(MiroBlue, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "服务器：${uiState.serverUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showServerDialog) {
        ServerConfigDialog(
            currentUrl = uiState.serverUrl,
            onSave = { url ->
                viewModel.saveServerUrl(url)
                showServerDialog = false
            },
            onDismiss = { showServerDialog = false },
        )
    }
}

@Composable
private fun ServerConfigDialog(
    currentUrl: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器地址", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column {
                Text(
                    text = "填写自建服务器的根地址，不需要包含 /api。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                )
                Spacer(Modifier.height(12.dp))
                MiroTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        localError = null
                    },
                    label = "服务器地址",
                    placeholder = "https://your-domain.com",
                    error = localError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "本地开发可用 http://10.0.2.2:3000（模拟器访问电脑）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!ServerConfig.isValid(value)) {
                        localError = "地址无效，请包含有效的主机名"
                    } else {
                        onSave(ServerConfig.normalize(value))
                    }
                },
            ) {
                Text("保存", style = MaterialTheme.typography.labelLarge, color = MiroBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = MaterialTheme.typography.labelLarge, color = SlateText)
            }
        },
        containerColor = White,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
    )
}
