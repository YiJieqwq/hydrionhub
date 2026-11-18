package io.hydrion.hub.ui.messages

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import io.hydrion.hub.ui.theme.capsuleEdge
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageResult(msg: Message, query: String, vm: HubViewModel, busy: Boolean, showRegenerate: Boolean) {
    val size = remember(msg.parameters) { outputSize(msg.parameters) }
    val pager = rememberPagerState { msg.imageKeys.size }
    val scope = rememberCoroutineScope()
    var opened by rememberSaveable(msg.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val page = pager.currentPage.coerceIn(msg.imageKeys.indices)
    fun reference(imageKey: String) {
        vm.referenceImage(imageKey, size.width, size.height)
        Toast.makeText(context, "已添加到输入框", Toast.LENGTH_SHORT).show()
    }
    MessageCard(LocalMessageStyle.current.renderPalette.assistant, Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (msg.prompt.isNotBlank()) Text(highlighted(msg.prompt, query), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
                    val key = msg.imageKeys[index]
                    MockPicture(
                        key = key, width = size.width, height = size.height, description = "生成的图片，第 ${index + 1} 张",
                        modifier = Modifier.fillMaxWidth().aspectRatio((size.width.toFloat() / size.height).coerceIn(.9f, 2.1f))
                            .clickable(role = Role.Button, onClickLabel = "查看大图") { opened = key }
                    )
                }
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp).capsuleEdge(), color = Color.Black.copy(alpha = .62f),
                    shape = CircleShape, contentColor = Color.White) {
                    Text("${page + 1} / ${msg.imageKeys.size}", Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                if (page + 1 < msg.imageKeys.size) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(page + 1) } },
                        modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Outlined.ChevronRight, "下一张", tint = Color.White,
                            modifier = Modifier.capsuleEdge().background(Color.Black.copy(alpha = .55f), CircleShape))
                    }
                }
                if (page > 0) IconButton(onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Outlined.ChevronLeft, "上一张", tint = Color.White,
                        modifier = Modifier.capsuleEdge().background(Color.Black.copy(alpha = .55f), CircleShape))
                }
            }
            Text(listOfNotNull("${size.width} × ${size.height}", "${msg.imageKeys.size} 张", msg.parameters["quality"]).joinToString(" · "),
                color = LocalContentColor.current, style = MaterialTheme.typography.labelMedium)
            val custom = msg.parameters.filterKeys { it !in setOf("size", "aspect", "quality", "n") }
            if (custom.isNotEmpty()) Text(custom.entries.joinToString(" · ") { "${it.key} ${it.value}" },
                color = LocalContentColor.current, style = MaterialTheme.typography.labelSmall)
            FlowRow(Modifier.fillMaxWidth()) {
                TextButton(onClick = { reference(msg.imageKeys[page]) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("作为参考图", style = MaterialTheme.typography.labelMedium)
                }
                if (showRegenerate) TextButton(onClick = { vm.regenerate(msg.id) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新生成", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    val initial = opened?.let { msg.imageKeys.indexOf(it) } ?: -1
    if (initial >= 0) PictureViewer(msg, size, initial, onReference = ::reference, onDismiss = { opened = null })
}

@Composable
private fun PictureViewer(msg: Message, size: Extent, initial: Int, onReference: (String) -> Unit, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial) { msg.imageKeys.size }
    val key = msg.imageKeys[pager.currentPage.coerceIn(msg.imageKeys.indices)]
    var saveKey by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun runAction(success: String, action: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try {
                action()
                Toast.makeText(context, success, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = if (e is IllegalArgumentException) e.message else null
                Toast.makeText(context, reason ?: "图片操作失败，请重试", Toast.LENGTH_LONG).show()
            } finally {
                working = false
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val target = saveKey
        saveKey = null
        if (target != null && uri != null) runAction("PNG 已保存") {
            saveMockPicture(context, uri, target, size.width, size.height)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭大图") }
                    Text("${pager.currentPage + 1} / ${msg.imageKeys.size}", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge)
                    IconButton(enabled = !working && saveKey == null, onClick = {
                        runAction("图片已复制") { copyMockPicture(context, key, size.width, size.height) }
                    }) { Icon(Icons.Outlined.ContentCopy, "复制图片") }
                    IconButton(enabled = !working && saveKey == null, onClick = {
                        saveKey = key
                        saveFile.launch("HydrionHub-${key.take(12)}-${pager.currentPage + 1}.png")
                    }) { Icon(Icons.Outlined.Download, "保存图片") }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    MockPicture(msg.imageKeys[page], size.width, size.height, "大图，第 ${page + 1} 张",
                        Modifier.fillMaxSize(), fit = true)
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    if (msg.prompt.isNotBlank()) Text(msg.prompt, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${msg.model} · ${size.width} × ${size.height} · ${messageTime(msg.createdAt, true)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onReference(key); onDismiss() }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("作为参考图")
                    }
                }
            }
        }
    }
}
