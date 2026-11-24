@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.*

@Composable fun LiveScreen(roomId: Long, model: MainViewModel, onBack: () -> Unit) {
    val engine = model.playback
    val activity = LocalActivity.current as? PlaybackActivity
    var qualityOpen by remember { mutableStateOf(false) }
    var chat by remember(roomId) { mutableStateOf<List<LiveChat>>(emptyList()) }
    var chatError by remember(roomId) { mutableStateOf<String?>(null) }
    var chatLoading by remember(roomId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(roomId) { engine.openLive(roomId) }
    DisposableEffect(roomId) { onDispose { engine.close() } }
    BackHandler(engine.fullScreen && !engine.inPip) { engine.updateFullScreen(false) }
    LaunchedEffect(engine.fullScreen, engine.inPip, model.pipEnabled) { activity?.syncPlayerWindow() }
    LaunchedEffect(roomId, refresh) {
        chatLoading = true
        try { chat = model.api.liveChat(roomId); chatError = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { chatError = networkMessage(e) }
        finally { chatLoading = false }
    }
    val messages = (chat + engine.liveDanmaku.chat).distinctBy { it.id }.takeLast(80)
    PlaybackFrame(engine, onBack, { qualityOpen = true }, toolbar = {
        TopAppBar(title = { Text("直播间") }, windowInsets = WindowInsets(0, 0, 0, 0), navigationIcon = {
            IconButton(onClick = onBack) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        })
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("native_live_detail"), contentPadding = PaddingValues(bottom = 24.dp)) {
            engine.error?.let { message -> item { ErrorCard(message, engine::retry) } }
            item {
                val info = engine.liveDetail
                Column(Modifier.padding(20.dp)) {
                    Text(info?.title ?: "正在连接直播间…", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(info?.avatar, "主播头像", Modifier.size(44.dp).clip(CircleShape))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(info?.author.orEmpty(), fontWeight = FontWeight.SemiBold)
                            Text("${info?.area.orEmpty()} · 人气 ${count(info?.online ?: 0)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (info?.live == true) "● 直播中" else "未开播", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    info?.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 14.dp), maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item { Text(engine.liveDanmaku.status, Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("直播聊天", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { refresh++ }, enabled = !chatLoading) { SpinningRefreshIcon(chatLoading, "刷新直播聊天") }
                }
            }
            chatError?.let { message -> item { ErrorCard(message, { refresh++ }) } }
            items(messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).animateItem()) {
                    Text(message.name + "：", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text(message.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (messages.isEmpty() && chatError == null) item { Text("这里会显示最近的直播聊天", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (qualityOpen && !engine.pipPresentation) ModalBottomSheet(onDismissRequest = { qualityOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("直播清晰度", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(engine.qualities, key = { it.id }) { option ->
                ListItem(headlineContent = { Text(option.label) }, trailingContent = { RadioButton(option.id == engine.quality, null) },
                    modifier = Modifier.clickable { engine.selectQuality(option.id); qualityOpen = false })
            }
        }
    }
}
