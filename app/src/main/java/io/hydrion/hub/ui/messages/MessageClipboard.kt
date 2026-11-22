package io.hydrion.hub.ui.messages

import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import io.hydrion.hub.R

internal fun copyPlainTextToClipboard(text: String, sdkInt: Int, setText: (AnnotatedString) -> Unit, confirm: () -> Unit) {
    setText(AnnotatedString(text))
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) confirm()
}

@Composable
internal fun rememberPlainTextCopier(@StringRes confirmation: Int): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context, confirmation) {
        { text: String ->
            try {
                copyPlainTextToClipboard(text, Build.VERSION.SDK_INT, clipboard::setText) {
                    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
                }
            } catch (_: RuntimeException) {
                Toast.makeText(context, R.string.turn_copy_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
