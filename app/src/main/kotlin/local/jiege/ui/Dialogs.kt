package local.jiege.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A Miuix dialog with two buttons: [dismissText] closes it, [confirmText] (primary) runs [onConfirm]. */
@Composable
internal fun OverlayConfirmDialog(
    title: String,
    summary: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
) {
    OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        DialogButtons(dismissText, onDismiss, confirmText, onConfirm)
    }
}

/** The button row of a dialog: [dismissText] on the left, the primary [confirmText] on the right. */
@Composable
internal fun DialogButtons(dismissText: String, onDismiss: () -> Unit, confirmText: String, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一行「标签 + 单行输入框」，参数编辑用。[numeric] 为 true 时只收整数与负号。
 */
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(58.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                onChange(if (numeric) raw.filter { it.isDigit() || it == '-' } else raw.replace("\n", " "))
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 带一个单行输入框的对话框：[initial] 是预填值，[onConfirm] 拿到用户输入（已去掉首尾空格）。
 * 用于导入预设时自己起名，以及给已有预设改名。
 * [autoFocus] 为 false 时不主动弹键盘——默认值常常就是想要的，用户直接点确定更省事。
 */
@Composable
internal fun NameFieldDialog(
    title: String,
    summary: String,
    initial: String,
    placeholder: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    autoFocus: Boolean = true,
) {
    var value by remember { mutableStateOf(initial) }
    // 弹出来直接能打字：省掉再点一下输入框。
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        delay(180)
        runCatching { focus.requestFocus() }
    }
    OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            DialogButtons("取消", onDismiss, confirmText) { onConfirm(value.trim()) }
        }
    }
}
