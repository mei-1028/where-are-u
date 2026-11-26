package com.whereareu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.BorderStrong
import com.whereareu.app.ui.theme.DangerRed
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.MiroBlueSoft
import com.whereareu.app.ui.theme.MiroRadius
import com.whereareu.app.ui.theme.OfflineGray
import com.whereareu.app.ui.theme.PlaceholderGray
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SuccessGreen
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.theme.WarningAmber
import com.whereareu.app.ui.theme.WarningSurface
import com.whereareu.app.ui.theme.DangerSurface
import com.whereareu.app.ui.theme.memberColor

@Composable
fun BrandMark(compact: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(if (compact) 34.dp else 42.dp)
                .clip(RoundedCornerShape(if (compact) 9.dp else 12.dp))
                .background(MiroBlue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = White,
                modifier = Modifier.size(if (compact) 20.dp else 24.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "你在哪",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
            if (!compact) {
                Text(
                    text = "和家人保持联系",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = "请稍候…",
    containerColor: Color = MiroBlue,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(MiroRadius.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = White,
            disabledContainerColor = Color(0xFFD9DCF0),
            disabledContentColor = White,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(loadingText, style = MaterialTheme.typography.labelLarge)
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Ink,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(MiroRadius.button),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LinkText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MiroBlue,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun MiroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val placeholderContent: (@Composable () -> Unit)? = if (placeholder.isBlank()) {
        null
    } else {
        { Text(placeholder, color = PlaceholderGray, style = MaterialTheme.typography.bodyLarge) }
    }

    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = SlateText,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholderContent,
            singleLine = singleLine,
            isError = error != null,
            shape = RoundedCornerShape(MiroRadius.input),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MiroBlue,
                unfocusedBorderColor = BorderHairline,
                errorBorderColor = DangerRed,
                focusedContainerColor = White,
                unfocusedContainerColor = White,
                errorContainerColor = White,
                cursorColor = MiroBlue,
                focusedPlaceholderColor = PlaceholderGray,
                unfocusedPlaceholderColor = PlaceholderGray,
            ),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = DangerRed,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

@Composable
fun MemberAvatar(
    name: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    online: Boolean? = null,
    highlight: Boolean = false,
) {
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(memberColor(colorIndex)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).ifBlank { "?" },
                color = White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).sp,
                maxLines = 1,
            )
        }
        if (highlight) {
            Box(Modifier.fillMaxSize().border(2.dp, MiroBlue, CircleShape))
        }
        if (online != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(White),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (online) SuccessGreen else OfflineGray),
                )
            }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
fun PermissionBadge(
    granted: Boolean?,
    grantedLabel: String = "已开启",
    deniedLabel: String = "未开启",
    unknownLabel: String = "需手动确认",
) {
    val text = when (granted) {
        true -> grantedLabel
        false -> deniedLabel
        null -> unknownLabel
    }
    val foreground = when (granted) {
        true -> SuccessGreen
        false -> DangerRed
        null -> Color(0xFF8A5A00)
    }
    val background = when (granted) {
        true -> Color(0xFFE3F8EF)
        false -> DangerSurface
        null -> WarningSurface
    }
    StatusPill(text = text, foreground = foreground, background = background)
}

enum class BannerTone { Warning, Error, Neutral }

@Composable
fun StatusBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val background = when (tone) {
        BannerTone.Warning -> WarningSurface
        BannerTone.Error -> DangerSurface
        BannerTone.Neutral -> Color(0xFFF1F2F5)
    }
    val foreground = when (tone) {
        BannerTone.Warning -> Color(0xFF7A4A00)
        BannerTone.Error -> Color(0xFF8A1C16)
        BannerTone.Neutral -> SlateText
    }
    val icon = when (tone) {
        BannerTone.Warning, BannerTone.Error -> Icons.Filled.Warning
        BannerTone.Neutral -> Icons.Filled.Info
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = foreground)
            }
        }
    }
}

@Composable
fun MiroCard(
    modifier: Modifier = Modifier,
    background: Color = White,
    borderColor: Color = BorderHairline,
    cornerRadius: Dp = MiroRadius.card,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = SlateText,
        )
        trailing?.invoke()
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
    containerColor: Color = White,
    borderColor: Color = BorderHairline,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun MiroTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                tint = Ink,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

data class ChoiceOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
)

@Composable
fun ChoicePills(
    options: List<ChoiceOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val selected = option.key == selectedKey
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MiroBlueSoft else White)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MiroBlue else BorderHairline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(option.key) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MiroBlue else Ink,
                    maxLines = 1,
                )
                if (!option.subtitle.isNullOrBlank()) {
                    Text(
                        text = option.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MiroBlue else SlateText,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                    modifier = Modifier.padding(start = 12.dp),
                    textAlign = TextAlign.End,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(color = BorderHairline)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = SlateText) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (destructive) DangerRed else MiroBlue,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = MaterialTheme.typography.labelLarge, color = SlateText)
            }
        },
        containerColor = White,
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
fun EmptyStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = Icons.Filled.LocationOn,
) {
    MiroCard(modifier = modifier, background = White) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(TealPastel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = SlateText,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            LinkText(text = actionLabel, onClick = onAction, modifier = Modifier.padding(start = 0.dp))
        }
    }
}

@Composable
fun LoadingState(message: String = "加载中…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MiroBlue, strokeWidth = 2.5.dp)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = SlateText)
    }
}

@Composable
fun PastelTag(text: String, background: Color = TealPastel, foreground: Color = Ink) {
    Surface(
        color = background,
        contentColor = foreground,
        shape = CircleShape,
        modifier = Modifier.height(30.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun MemberInitialsLegend(index: Int, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(memberColor(index)),
        )
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, color = SlateText)
    }
}
