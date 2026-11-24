package app.biliflow

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Read-only diagnostic; reports only counts, never account data or tokens. */
class DynamicProbeActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = TextView(this).apply { textSize = 18f; text = "正在检查安卓动态" }; setContentView(result)
        lifecycleScope.launch {
            try {
                val list = model.api.androidDynamic("")
                val next = if (list.more && list.offset.isNotBlank()) model.api.androidDynamic(list.offset) else null
                val personal = list.authors.firstOrNull()?.let { model.api.androidDynamic("", it.id) }
                val numeric = list.posts.firstOrNull { it.id.isNotEmpty() && it.id.all(Char::isDigit) }
                val detail = numeric?.let { model.api.androidDynamicDocument(it.id) }
                result.text = "安卓动态连接成功：${list.posts.size} 条，下一页=${next?.posts?.size ?: -1}，重复=${next?.posts?.count { post -> list.posts.any { it.id == post.id } } ?: -1}，作者=${list.authors.size}，有效ID=${list.posts.count { it.id.all(Char::isDigit) }}，文字=${list.posts.count { it.text.isNotBlank() }}，视频=${list.posts.count { it.video != null }}，个人=${personal?.posts?.size ?: -1}，有已读游标=${!personal?.readOffset.isNullOrBlank()}，详情=${detail?.post?.id == numeric?.id}"
            } catch (e: Exception) { result.text = "安卓动态检查失败：${e.message}" }
        }
    }
}
