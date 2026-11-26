package com.whereareu.app.ui.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.util.TimeUtils
import com.whereareu.app.core.util.toast
import com.whereareu.app.ui.components.BannerTone
import com.whereareu.app.ui.components.ConfirmDialog
import com.whereareu.app.ui.components.EmptyStateCard
import com.whereareu.app.ui.components.LinkText
import com.whereareu.app.ui.components.LoadingState
import com.whereareu.app.ui.components.MiroCard
import com.whereareu.app.ui.components.MiroTopBar
import com.whereareu.app.ui.components.PastelTag
import com.whereareu.app.ui.components.PrimaryButton
import com.whereareu.app.ui.components.SecondaryButton
import com.whereareu.app.ui.components.StatusBanner
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroSpacing
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.viewModelFactoryFor

@Composable
fun InviteScreen(onBack: () -> Unit) {
    val viewModel: InviteViewModel = viewModel(
        factory = viewModelFactoryFor {
            InviteViewModel(AppGraph.familyRepository)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        context.toast(message)
        viewModel.clearMessage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .navigationBarsPadding(),
    ) {
        MiroTopBar(
            title = "邀请家人",
            subtitle = uiState.familyName.ifBlank { null },
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MiroSpacing.lg),
        ) {
            when {
                uiState.loading -> LoadingState(
                    message = "正在获取邀请码…",
                    modifier = Modifier.height(240.dp),
                )

                !uiState.isAdmin -> {
                    PastelTag("管理员专属", PinkPastel)
                    Spacer(Modifier.height(16.dp))
                    EmptyStateCard(
                        title = "邀请码仅管理员可见",
                        message = "M1 中邀请码只展示给家庭管理员。请让管理员打开「家庭 → 邀请家人」并把 6 位码分享给你。",
                    )
                    Spacer(Modifier.height(16.dp))
                    LinkText(text = "重新加载", onClick = viewModel::load)
                }

                uiState.inviteCode.isNullOrBlank() -> {
                    EmptyStateCard(
                        title = "暂时没有邀请码",
                        message = "请稍后重试，或让管理员重置邀请码。",
                    )
                    Spacer(Modifier.height(16.dp))
                    LinkText(text = "重新加载", onClick = viewModel::load)
                }

                else -> {
                    val code = uiState.inviteCode.orEmpty()
                    PastelTag("邀请加入家庭", TealPastel)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "把邀请码发给家人",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "家人注册或登录后输入邀请码即可加入，双方地图会立即互见。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateText,
                    )
                    Spacer(Modifier.height(20.dp))

                    MiroCard(
                        background = TealPastel.copy(alpha = 0.3f),
                        borderColor = TealPastel,
                        cornerRadius = 20.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    ) {
                        Text(
                            text = "邀请码",
                            style = MaterialTheme.typography.labelMedium,
                            color = SlateText,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = code,
                            style = MaterialTheme.typography.displaySmall.copy(
                                letterSpacing = 8.sp,
                            ),
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = expiryLabel(uiState.expiresAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateText,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(
                                text = "复制邀请码",
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("家庭邀请码", code))
                                    context.toast("邀请码已复制")
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton(
                                text = "复制家庭名",
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("家庭名称", uiState.familyName),
                                    )
                                    context.toast("家庭名称已复制")
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    MiroCard(cornerRadius = 20.dp) {
                        Text(
                            text = "扫码加入（占位）",
                            style = MaterialTheme.typography.titleSmall,
                            color = Ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "本轮二维码为占位图形，家人可在 App 内手动输入邀请码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                        )
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(16.dp))
                                .background(White)
                                .padding(14.dp),
                        ) {
                            QrPlaceholder(code = code, modifier = Modifier.size(176.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = "分享给家人",
                        onClick = {
                            val text = "我在「你在哪」的家庭「${uiState.familyName}」，" +
                                "邀请码 $code，24 小时内有效。下载 App 后输入邀请码即可加入。"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            try {
                                context.startActivity(Intent.createChooser(intent, "分享给家人"))
                            } catch (t: Throwable) {
                                context.toast("没有可用的分享应用")
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkText(
                            text = if (uiState.resetting) "重置中…" else "重置邀请码",
                            onClick = {
                                if (!uiState.resetting) showResetConfirm = true
                            },
                            color = SlateText,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "重置后旧邀请码立即失效，新码 24 小时有效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateText,
                        modifier = Modifier.padding(bottom = 32.dp),
                    )
                }
            }

            if (uiState.error != null) {
                Spacer(Modifier.height(12.dp))
                StatusBanner(
                    text = uiState.error.orEmpty(),
                    tone = BannerTone.Error,
                    actionLabel = "重试",
                    onAction = viewModel::load,
                )
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = "重置邀请码？",
            message = "旧邀请码会立即失效，已分享出去的旧码将无法加入。",
            confirmLabel = "重置",
            destructive = true,
            onConfirm = {
                showResetConfirm = false
                viewModel.resetInvite()
            },
            onDismiss = { showResetConfirm = false },
        )
    }
}

private fun expiryLabel(expiresAt: String?): String {
    val epoch = TimeUtils.parseIsoEpochMillis(expiresAt)
    return if (epoch != null) {
        "有效至 ${TimeUtils.formatDateTime(epoch)}（24 小时内）"
    } else {
        "24 小时内有效"
    }
}

/**
 * Deterministic decorative pattern, NOT a scannable QR code. The prototype also
 * specifies a QR placeholder for M1.
 */
@Composable
private fun QrPlaceholder(
    code: String,
    modifier: Modifier = Modifier,
) {
    val seed = remember(code) { code.hashCode().toLong() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(White)
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val modules = 25
            val cell = size.minDimension / modules
            drawRect(color = White)
            val random = java.util.Random(seed)
            for (row in 0 until modules) {
                for (col in 0 until modules) {
                    val finder = finderModule(row, col, modules)
                    val filled = finder ?: (
                        row > 0 &&
                            col > 0 &&
                            row < modules - 1 &&
                            col < modules - 1 &&
                            random.nextInt(100) < 44
                        )
                    if (filled) {
                        drawRect(
                            color = Ink,
                            topLeft = Offset(col * cell, row * cell),
                            size = Size(cell * 0.92f, cell * 0.92f),
                        )
                    }
                }
            }
        }
    }
}

private fun finderModule(row: Int, col: Int, size: Int): Boolean? {
    val corners = listOf(0 to 0, size - 7 to 0, 0 to size - 7)
    for ((row0, col0) in corners) {
        val localRow = row - row0
        val localCol = col - col0
        if (localRow in 0..6 && localCol in 0..6) {
            val outer = localRow == 0 || localRow == 6 || localCol == 0 || localCol == 6
            val inner = localRow in 2..4 && localCol in 2..4
            return outer || inner
        }
    }
    return null
}
