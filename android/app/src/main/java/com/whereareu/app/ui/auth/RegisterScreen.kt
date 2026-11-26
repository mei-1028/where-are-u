package com.whereareu.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.MiroTextField
import com.whereareu.app.ui.components.MiroTopBar
import com.whereareu.app.ui.components.PastelTag
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor

@Composable
fun RegisterScreen(
    onAuthenticated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel(
        factory = viewModelFactoryFor {
            AuthViewModel(AppGraph.authRepository, AppGraph.sessionStore)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    var usernameError by remember { mutableStateOf<String?>(null) }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.navigateTo) {
        val destination = uiState.navigateTo ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onAuthenticated(destination)
    }

    fun submit() {
        val nameError = when {
            username.isBlank() -> "请输入用户名"
            username.trim().length !in 3..20 -> "用户名需为 3–20 位"
            else -> null
        }
        val nickError = when {
            nickname.isBlank() -> "请输入昵称"
            nickname.trim().length > 20 -> "昵称最多 20 位"
            else -> null
        }
        val passError = when {
            password.isBlank() -> "请设置密码"
            password.length < 8 -> "密码至少 8 位"
            else -> null
        }
        val confirm = when {
            confirmPassword != password -> "两次输入的密码不一致"
            else -> null
        }
        val invite = when {
            inviteCode.isBlank() -> null
            !inviteCode.matches(Regex("\\d{6}")) -> "邀请码为 6 位数字"
            else -> null
        }
        usernameError = nameError
        nicknameError = nickError
        passwordError = passError
        confirmError = confirm
        inviteError = invite
        if (nameError == null && nickError == null && passError == null && confirm == null && invite == null) {
            viewModel.register(username, nickname, password, inviteCode)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(White)) {
        MiroTopBar(title = "创建账号", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = MiroSpacing.lg),
        ) {
            Spacer(Modifier.height(8.dp))
            PastelTag("和家人一起用", PinkPastel)
            Spacer(Modifier.height(16.dp))
            Text("先介绍一下自己", style = MaterialTheme.typography.headlineSmall, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "注册后可以创建家庭，或用家人的邀请码加入。",
                style = MaterialTheme.typography.bodyMedium,
                color = SlateText,
            )
            Spacer(Modifier.height(28.dp))

            MiroTextField(
                value = username,
                onValueChange = {
                    username = it.take(20)
                    usernameError = null
                    viewModel.clearError()
                },
                label = "用户名",
                placeholder = "3–20 位，登录时使用",
                error = usernameError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(16.dp))
            MiroTextField(
                value = nickname,
                onValueChange = {
                    nickname = it.take(20)
                    nicknameError = null
                    viewModel.clearError()
                },
                label = "昵称",
                placeholder = "家人看到的名字，例如「阿哲」",
                error = nicknameError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(16.dp))
            MiroTextField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    confirmError = null
                    viewModel.clearError()
                },
                label = "确认密码",
                placeholder = "再次输入密码",
                error = confirmError,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(16.dp))
            MiroTextField(
                value = inviteCode,
                onValueChange = {
                    inviteCode = it.filter(Char::isDigit).take(6)
                    inviteError = null
                    viewModel.clearError()
                },
                label = "邀请码（选填）",
                placeholder = "6 位数字，可稍后加入",
                error = inviteError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            if (uiState.error != null) {
                Spacer(Modifier.height(16.dp))
                StatusBanner(text = uiState.error.orEmpty(), tone = BannerTone.Error)
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "注册并开始",
                loading = uiState.loading,
                onClick = { submit() },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已有账号？", style = MaterialTheme.typography.bodySmall, color = SlateText)
                LinkText(text = "登录", onClick = onBack)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
