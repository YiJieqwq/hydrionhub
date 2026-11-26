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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
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
import io.hydrion.hub.ui.theme.HubStyle
import java.util.Locale
import kotlin.random.Random

val LocalMessageStyleStore = staticCompositionLocalOf<MessageStyleStore> { error("ProvideMessageStyle is required") }
internal val LocalMessageStyle = compositionLocalOf { MessageStyle() }
private val LocalChatScheme = compositionLocalOf<ColorScheme?> { null }

@Composable
fun ProvideMessageStyle(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { MessageStyleStore.shared(context) }
    val style by store.style.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalMessageStyleStore provides store, LocalMessageStyle provides style,
        LocalChatScheme provides MaterialTheme.colorScheme, content = content)
}

internal fun String.messageColor() = Color(0xFF000000L or removePrefix("#").toLong(16))

private val lightTimeline = lightColorScheme(
    background = Color(0xFFFAFAFB), surface = Color.White, onSurface = Color(0xFF202124),
    onBackground = Color(0xFF202124), onSurfaceVariant = Color(0xFF53565B),
    primary = Color(0xFF325CBB), secondary = Color(0xFF53565B)
)

@Composable
internal fun timelineScheme(tone: MessageTone, darkScheme: ColorScheme = MaterialTheme.colorScheme) =
    if (tone == MessageTone.Light) lightTimeline else darkScheme

@Composable
private fun previewScheme(tone: MessageTone) = timelineScheme(tone, LocalChatScheme.current ?: MaterialTheme.colorScheme)

@Composable
internal fun MessageCanvas(style: MessageStyle, modifier: Modifier, scheme: ColorScheme = timelineScheme(style.tone),
    content: @Composable () -> Unit) {
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
    outlined: Boolean = true,
    content: @Composable () -> Unit
) {
    val material = LocalMessageStyle.current.material
    val background = colors.background.messageColor()
    val edge = if (outlined) Modifier.border(1.dp, colors.border.messageColor(), shape) else Modifier
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
    }.then(edge)) {
        CompositionLocalProvider(LocalContentColor provides colors.text.messageColor(), content = content)
    }
}

internal enum class ChatSurface { UserBubble, ThoughtAndTools, ToolPanel, Translation }

private val chatTintedShape = RoundedCornerShape(16.dp)
private val replyChainShape = RoundedCornerShape(20.dp)
private val darkThoughtToolFill = defaultMessagePalette(MessageTone.Dark).assistant.background.messageColor()

private fun uneditedUserFill(scheme: ColorScheme, dark: Boolean): Color {
    val stockAccent = if (dark) HubStyle.Blue else lightTimeline.primary
    val stockCanvas = if (dark) HubStyle.Background else lightTimeline.background
    if (scheme.primary == stockAccent && scheme.background == stockCanvas) {
        return if (dark) Color(0xFF161F22) else Color(0xFFECF3F5)
    }
    return scheme.primary.copy(alpha = if (dark) .15f else .08f)
}

@Composable
internal fun MessageStyle.userCardInEditor(): MessageCardStyle {
    val user = renderPalette.user
    if (!usesAutomaticUserTint) return user
    val scheme = previewScheme(tone)
    val fill = uneditedUserFill(scheme, tone == MessageTone.Dark).compositeOver(scheme.background)
    return user.copy(background = String.format(Locale.ROOT, "#%06X", fill.toArgb() and 0xFFFFFF))
}

/** The floating panel lives outside MessageCanvas. Give a light card the same backing and text scheme as the timeline. */
@Composable
internal fun ToolPanelCard(modifier: Modifier, content: @Composable () -> Unit) {
    val tone = LocalMessageStyle.current.tone
    val scheme = timelineScheme(tone)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        val backing = if (tone == MessageTone.Light) modifier.clip(chatTintedShape).background(scheme.background) else modifier
        ChatTintedSurface(ChatSurface.ToolPanel, backing, content)
    }
}

@Composable
internal fun ChatTintedSurface(target: ChatSurface, modifier: Modifier, content: @Composable () -> Unit) {
    val style = LocalMessageStyle.current
    val user = target == ChatSurface.UserBubble
    val thoughtOrTools = target == ChatSurface.ThoughtAndTools || target == ChatSurface.ToolPanel
    val dark = style.tone == MessageTone.Dark
    val fixedDarkCard = thoughtOrTools && dark
    val palette = if (user) style.renderPalette.user else style.renderPalette.assistant
    val defaults = defaultMessagePalette(style.tone)
    val shape = if (target == ChatSurface.ThoughtAndTools) replyChainShape else chatTintedShape
    val useMaterial = if (user) !style.usesAutomaticUserTint else style.material != CardMaterial.Solid || palette != defaults.assistant
    if (useMaterial && !fixedDarkCard) {
        MessageCard(palette, modifier, shape, outlined = !thoughtOrTools, content = content)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val fill = if (dark && target == ChatSurface.ThoughtAndTools) Color(0xFF262B2E)
        else if (fixedDarkCard) darkThoughtToolFill
        else if (user) uneditedUserFill(scheme, dark)
        else scheme.primaryContainer.copy(alpha = if (dark) .25f else .30f)
    Box(modifier.clip(shape).background(fill)) {
        val ink = if (dark && target == ChatSurface.ThoughtAndTools) Color(0xFFB5CAD7)
            else if (fixedDarkCard) Color.White else scheme.onSurface
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

@Composable
internal fun AssistantBody(text: String, query: String, mode: Mode,
    fragments: Map<Int, RichFragment> = emptyMap(), images: Map<Int, RichImage> = emptyMap()) {
    val style = LocalMessageStyle.current
    val colors = style.renderPalette.assistant
    val chat = mode == Mode.Chat
    val type = MaterialTheme.typography.bodyLarge
    val body = if (chat) type.copy(fontSize = 15.7.sp, lineHeight = 23.55.sp, letterSpacing = 0.sp) else type
    if (style.assistantBubble) {
        val shape = RoundedCornerShape(if (chat) 16.dp else 19.dp)
        MessageCard(colors, Modifier.testTag("message-assistant-body"), shape = shape) {
            Box(Modifier.padding(horizontal = if (chat) 12.dp else 14.dp, vertical = if (chat) 12.dp else 10.dp)) {
                MarkdownBody(text, query, if (chat) Modifier else Modifier.fillMaxWidth(), body, chat, fragments, images)
            }
        }
    } else CompositionLocalProvider(LocalContentColor provides colors.text.messageColor()) {
        Box(Modifier.testTag("message-assistant-body")) { MarkdownBody(text, query, Modifier.fillMaxWidth(), body, chat, fragments, images) }
    }
}

@Composable
fun MessageStylePreview(style: MessageStyle, modifier: Modifier = Modifier) {
    val user = remember { Message(id = "preview-user", role = Role.User, text = "用户消息") }
    val assistant = remember { Message(id = "preview-assistant", role = Role.Assistant,
        text = "这是助手的回复。", thought = "这里展示思考内容。") }
    MessageCanvas(style, modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)), previewScheme(style.tone)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { UserBubble(user, "", Mode.Chat) }
            ReplyTimeline(assistant, "")
        }
    }
}
