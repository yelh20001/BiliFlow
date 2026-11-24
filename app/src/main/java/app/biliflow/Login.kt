@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.biliflow

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun LoginScreen(model: MainViewModel, onBack: () -> Unit, openWeb: () -> Unit) {
    val state = model.qrState
    LaunchedEffect(Unit) { model.startQrLogin() }
    DisposableEffect(Unit) { onDispose { model.stopQrLogin() } }
    var qrBitmap by remember(state.qr?.url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state.qr?.url) {
        state.qr?.url?.let { url ->
            qrBitmap = withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 2))
                Bitmap.createBitmap(IntArray(640 * 640) { index -> if (matrix[index % 640, index / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 640, 640, Bitmap.Config.ARGB_8888)
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopAppBar(title = { Text("登录哔哩哔哩") }, navigationIcon = { IconButton(onClick = onBack) { InterfaceIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } })
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("让推荐，更懂你的喜欢", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("使用哔哩哔哩客户端扫一扫，并在手机上确认登录。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Surface(shape = RoundedCornerShape(26.dp), color = Color.White, shadowElevation = 2.dp) {
                Box(Modifier.size(270.dp).padding(14.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator()
                        qrBitmap != null -> Image(qrBitmap!!.asImageBitmap(), "B 站登录二维码", Modifier.fillMaxSize())
                        else -> Text("二维码暂未就绪", color = Color.DarkGray)
                    }
                    if (state.expired) Surface(color = Color.White.copy(alpha = .95f), modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) { TextButton(onClick = model::startQrLogin) { Text("二维码已过期 · 点击刷新") } }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(state.status.ifBlank { "正在申请官方登录二维码…" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            state.error?.let { ErrorCard(it, model::startQrLogin) }
            TextButton(onClick = model::startQrLogin, enabled = !state.loading) { InterfaceIcon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("刷新二维码") }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = openWeb, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(15.dp)) {
                InterfaceIcon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("使用官方网页登录")
            }
            Spacer(Modifier.height(20.dp))
            Text("也可先通过官方网页登录，再在官方页面确认 App 授权。完成后首页使用此账号的安卓推荐流，登录会话加密保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
