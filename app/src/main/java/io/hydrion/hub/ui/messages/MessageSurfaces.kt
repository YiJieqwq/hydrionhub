package io.hydrion.hub.ui.messages

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import kotlin.random.Random

val LocalMessageStyleStore = staticCompositionLocalOf<MessageStyleStore> { error("ProvideMessageStyle is required") }
internal val LocalMessageStyle = compositionLocalOf { MessageStyle() }

@Composable
fun ProvideMessageStyle(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { MessageStyleStore.shared(context) }
    val style by store.style.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalMessageStyleStore provides store, LocalMessageStyle provides style, content = content)
}

internal fun String.messageColor() = Color(0xFF000000L or removePrefix("#").toLong(16))

private val lightTimeline = lightColorScheme(
    background = Color(0xFFFAFAFB), surface = Color.White, onSurface = Color(0xFF202124),
    onBackground = Color(0xFF202124), onSurfaceVariant = Color(0xFF53565B),
    primary = Color(0xFF325CBB), secondary = Color(0xFF53565B)
)

@Composable
internal fun MessageCanvas(style: MessageStyle, modifier: Modifier, content: @Composable () -> Unit) {
    val scheme = if (style.tone == MessageTone.Light) lightTimeline else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalMessageStyle provides style, LocalContentColor provides scheme.onBackground) {
            Box(modifier.background(scheme.background)) { content() }
        }
    }
}

// A single repeatable translucent grain tile works on API 26+ and leaves all foreground text sharp.
private val grainPaint by lazy {
    val random = Random(71)
    val pixels = IntArray(48 * 48) {
        val alpha = random.nextInt(9)
        (alpha shl 24) or if (random.nextBoolean()) 0xFFFFFF else 0
    }
    val tile = Bitmap.createBitmap(pixels, 48, 48, Bitmap.Config.ARGB_8888)
    android.graphics.Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
}

@Composable
internal fun MessageCard(
    colors: MessageCardStyle,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable () -> Unit
) {
    val material = LocalMessageStyle.current.material
    val background = colors.background.messageColor()
    val stroke = colors.border.messageColor()
    Box(modifier.clip(shape).drawWithCache {
        val sheen = when (material) {
            CardMaterial.Solid, CardMaterial.Default -> null
            CardMaterial.Frosted -> Brush.linearGradient(listOf(
                Color.White.copy(alpha = .19f), Color.White.copy(alpha = .035f), Color.Transparent
            ))
        }
        onDrawBehind {
            drawRect(if (material == CardMaterial.Frosted) background.copy(alpha = .81f) else background)
            if (sheen != null) drawRect(sheen)
            if (material == CardMaterial.Frosted) {
                drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, grainPaint)
            }
        }
    }.border(1.dp, stroke, shape)) {
        CompositionLocalProvider(LocalContentColor provides colors.text.messageColor(), content = content)
    }
}

@Composable
internal fun ChatTintedSurface(role: Role, modifier: Modifier, content: @Composable () -> Unit) {
    val style = LocalMessageStyle.current
    val user = role == Role.User
    val palette = if (user) style.renderPalette.user else style.renderPalette.assistant
    val defaults = defaultMessagePalette(style.tone)
    val shape = RoundedCornerShape(16.dp)
    if (style.material != CardMaterial.Solid || palette != (if (user) defaults.user else defaults.assistant)) {
        MessageCard(palette, modifier, shape, content)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val dark = style.tone == MessageTone.Dark
    val fill = if (user) scheme.primary.copy(alpha = if (dark) .15f else .08f)
        else scheme.primaryContainer.copy(alpha = if (dark) .25f else .30f)
    Box(modifier.clip(shape).background(fill)) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

@Composable
internal fun AssistantBody(text: String, query: String, mode: Mode) {
    val style = LocalMessageStyle.current
    val colors = style.renderPalette.assistant
    val chat = mode == Mode.Chat
    val type = MaterialTheme.typography.bodyLarge
    val body = if (chat) type.copy(fontSize = 15.7.sp, lineHeight = 23.55.sp, letterSpacing = 0.sp) else type
    if (style.assistantBubble) {
        val shape = RoundedCornerShape(if (chat) 16.dp else 19.dp)
        MessageCard(colors, Modifier.testTag("message-assistant-body"), shape = shape) {
            Box(Modifier.padding(horizontal = if (chat) 12.dp else 14.dp, vertical = if (chat) 12.dp else 10.dp)) {
                MarkdownBody(text, query, if (chat) Modifier else Modifier.fillMaxWidth(), body)
            }
        }
    } else CompositionLocalProvider(LocalContentColor provides colors.text.messageColor()) {
        Box(Modifier.testTag("message-assistant-body")) { MarkdownBody(text, query, Modifier.fillMaxWidth(), body) }
    }
}

@Composable
fun MessageStylePreview(style: MessageStyle, modifier: Modifier = Modifier) {
    val user = remember { Message(id = "preview-user", role = Role.User, text = "用户消息") }
    val assistant = remember { Message(id = "preview-assistant", role = Role.Assistant,
        text = "这是助手的回复。", thought = "这里展示思考内容。") }
    var expanded by remember { mutableStateOf(false) }
    MessageCanvas(style, modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { UserBubble(user, "", Mode.Chat) }
            Thought(assistant, "", expanded) { expanded = !expanded }
            AssistantBody(assistant.text, "", Mode.Chat)
        }
    }
}
