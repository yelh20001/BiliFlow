package app.biliflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/** Debug-only UI fixture; never included in release builds. */
class CommentPictureFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.semantics { testTagsAsResourceId = true }) {
                    val url = "file:///android_asset/comment-animation.gif"
                    CommentRow(Comment(1, "评论示例", "", "图片查看测试", 0, 0, 0,
                        pictures = listOf(CommentPicture(url), CommentPicture("file:///android_asset/comment-static.png"))))
                }
            }
        }
    }
}
