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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
 * 带一个单行输入框的对话框：[initial] 是预填值，[onConfirm] 拿到用户输入（已去掉首尾空格）。
 * 用于导入预设时自己起名，以及给已有预设改名。
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
) {
    var value by remember { mutableStateOf(initial) }
    // 弹出来直接能打字：省掉再点一下输入框。
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
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
