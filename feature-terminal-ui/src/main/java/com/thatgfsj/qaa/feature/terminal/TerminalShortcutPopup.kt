package com.thatgfsj.qaa.feature.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 长按次选浮层（#93，Termux popup 风格）：贴在快捷键行正上方，列出该键的次选动作。
 *
 * 终端风格：纯黑底、直角细描边、等宽字；点击条目立即执行并关闭，点击外部 / 返回键取消。
 * 浮层本身不持有业务状态：动作由 [onPick] 交回底部栏统一分发。
 */
@Composable
internal fun ShortcutAlternatePopup(
    keyLabel: String,
    alternates: List<ShortcutAlternate>,
    onDismiss: () -> Unit,
    onPick: (ShortcutAlternate) -> Unit,
) {
    Popup(
        popupPositionProvider = AboveAnchorPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 168.dp)
                .background(TerminalColors.Background)
                .border(BorderStroke(1.dp, TerminalColors.BarDividerText))
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "$keyLabel 长按",
                color = TerminalColors.BarDividerText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            alternates.forEach { alternate ->
                Text(
                    text = alternate.label,
                    color = TerminalColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(alternate) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 定位：左对齐父布局并放在父布局正上方；越界时夹在窗口内，避免浮层被裁掉。 */
private object AboveAnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxLeft = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val top = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(anchorBounds.left.coerceIn(0, maxLeft), top)
    }
}
