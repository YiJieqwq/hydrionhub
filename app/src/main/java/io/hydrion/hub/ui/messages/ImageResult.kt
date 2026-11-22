package io.hydrion.hub.ui.messages

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.R
import io.hydrion.hub.model.GeneratedImageStore
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageResult(msg: Message, query: String, vm: HubViewModel, busy: Boolean, showRegenerate: Boolean) {
    val demoSize = remember(msg.parameters) { outputSize(msg.parameters) }
    val sizes = remember(msg.imageKeys, demoSize) {
        msg.imageKeys.associateWith { pictureSize(it, demoSize) }
    }
    val pager = rememberPagerState { msg.imageKeys.size }
    val scope = rememberCoroutineScope()
    var opened by rememberSaveable(msg.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val page = pager.currentPage.coerceIn(msg.imageKeys.indices)
    val pageSize = sizes[msg.imageKeys[page]]
    suspend fun reference(imageKey: String): Boolean {
        val size = sizes[imageKey] ?: return false
        val conversation = vm.state.value.current.id
        try {
            if (GeneratedImageStore.isStored(imageKey)) GeneratedImageStore(context).requireAvailable(imageKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showImageFailure(context, e, "无法读取原始生成图片")
            return false
        }
        if (vm.state.value.current.id != conversation) return false
        vm.referenceImage(imageKey, size.width, size.height)
        Toast.makeText(context, "已添加到输入框", Toast.LENGTH_SHORT).show()
        return true
    }
    MessageCard(LocalMessageStyle.current.renderPalette.assistant, Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (msg.prompt.isNotBlank()) Text(highlighted(msg.prompt, query), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
                    val key = msg.imageKeys[index]
                    val aspect = sizes[key]?.let { it.width.toFloat() / it.height } ?: 1f
                    GeneratedPicture(
                        key = key, width = demoSize.width, height = demoSize.height, description = "生成的图片，第 ${index + 1} 张",
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(.9f, 2.1f))
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
            Text(listOfNotNull(pageSize?.let { "${it.width} × ${it.height}" }, "${msg.imageKeys.size} 张", msg.parameters["quality"]).joinToString(" · "),
                color = LocalContentColor.current, style = MaterialTheme.typography.labelMedium)
            val custom = msg.parameters.filterKeys { it !in setOf("size", "aspect", "quality", "n") }
            if (custom.isNotEmpty()) Text(custom.entries.joinToString(" · ") { "${it.key} ${it.value}" },
                color = LocalContentColor.current, style = MaterialTheme.typography.labelSmall)
            FlowRow(Modifier.fillMaxWidth()) {
                TextButton(onClick = { scope.launch { reference(msg.imageKeys[page]) } }, enabled = pageSize != null, contentPadding = PaddingValues(horizontal = 8.dp)) {
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
    if (initial >= 0) PictureViewer(msg, demoSize, sizes, initial, onReference = ::reference, onDismiss = { opened = null })
}

@Composable
internal fun PictureViewer(msg: Message, demoSize: Extent, sizes: Map<String, Extent?>, initial: Int,
                          onReference: suspend (String) -> Boolean, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial) { msg.imageKeys.size }
    var controlsVisible by remember(pager.currentPage) { mutableStateOf(true) }
    val key = msg.imageKeys[pager.currentPage.coerceIn(msg.imageKeys.indices)]
    val size = sizes[key]
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
                showImageFailure(context, e, "图片操作失败，请重试")
            } finally {
                working = false
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val target = saveKey
        saveKey = null
        if (target != null && uri != null) runAction("PNG 已保存") {
            savePicture(context, uri, target, demoSize.width, demoSize.height)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                if (controlsVisible) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭大图") }
                        Text("${pager.currentPage + 1} / ${msg.imageKeys.size}", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge)
                        IconButton(enabled = size != null && !working && saveKey == null, onClick = {
                            runAction("图片已复制") { copyPicture(context, key, demoSize.width, demoSize.height) }
                        }) { Icon(Icons.Outlined.ContentCopy, "复制图片") }
                        IconButton(enabled = size != null && !working && saveKey == null, onClick = {
                            saveKey = key
                            saveFile.launch("HydrionHub-${key.take(12).replace(':', '-')}-${pager.currentPage + 1}.png")
                        }) { Icon(Icons.Outlined.Download, "保存图片") }
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    GeneratedPicture(msg.imageKeys[page], demoSize.width, demoSize.height, "大图，第 ${page + 1} 张",
                        Modifier.fillMaxSize(), fit = true,
                        loadedImageModifier = Modifier.clickable(
                            interactionSource = null, indication = null, role = Role.Button,
                            onClickLabel = stringResource(
                                if (controlsVisible) R.string.turn_hide_image_controls else R.string.turn_show_image_controls
                            ),
                        ) { controlsVisible = !controlsVisible },
                    )
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (controlsVisible) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                        if (msg.prompt.isNotBlank()) Text(msg.prompt, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(msg.model, size?.let { "${it.width} × ${it.height}" },
                            messageTime(msg.createdAt, true)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { scope.launch { if (onReference(key)) onDismiss() } }, enabled = size != null) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("作为参考图")
                        }
                    }
                }
            }
        }
    }
}

private fun showImageFailure(context: Context, error: Exception, fallback: String) {
    val reason = if (error is IllegalArgumentException) error.message else null
    Toast.makeText(context, reason ?: fallback, Toast.LENGTH_LONG).show()
}
