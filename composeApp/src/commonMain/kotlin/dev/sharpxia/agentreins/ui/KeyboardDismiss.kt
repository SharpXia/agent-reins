package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 点击未被子组件消费的空白区域时收起软键盘并清除焦点。
 * 按钮、输入框等会消费 tap 的组件不受影响。
 */
@Composable
fun Modifier.dismissKeyboardOnTap(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return pointerInput(Unit) {
        detectTapGestures {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
}
