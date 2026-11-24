package app.biliflow

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class AuthorIdentity(val id: Long, val face: String)
suspend fun BiliApi.videoAuthor(video: Video): AuthorIdentity {
    if (video.authorFace.isNotBlank()) return AuthorIdentity(video.authorId, video.authorFace)
    if (video.authorId > 0) try {
        val card = get("/x/web-interface/card", mapOf("mid" to "${video.authorId}")).getJSONObject("card")
        val face = secureUrl(card.optString("face"))
        if (face.isNotBlank()) return AuthorIdentity(video.authorId, face)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    val detail = detail(video.id)
    return AuthorIdentity(detail.authorId, detail.authorFace)
}

/** Only visible cards request missing avatars. Deduplicate by author, bound memory and network fan-out. */
class AuthorAvatars(private val scope: CoroutineScope, private val fetch: suspend (Video) -> AuthorIdentity) {
    private val faces = mutableStateMapOf<String, String>()
    private val order = LinkedHashSet<String>()
    private val jobs = mutableMapOf<String, Job>()
    private val failedUntil = mutableMapOf<String, Long>()
    private val permits = Semaphore(3)
    private fun key(video: Video) = if (video.authorId > 0) "up:${video.authorId}" else "video:${video.id}"
    fun face(video: Video) = video.authorFace.ifBlank { faces[key(video)].orEmpty() }
    fun load(video: Video) {
        val key = key(video)
        if (video.authorFace.isNotBlank() || key in faces || key in jobs || (failedUntil[key] ?: 0) > System.currentTimeMillis()) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val author = permits.withPermit { fetch(video) }
                if (author.face.isNotBlank()) {
                    put(key, author.face)
                    if (author.id > 0) put("up:${author.id}", author.face)
                } else fail(key)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { fail(key) }
            finally { jobs.remove(key) }
        }
        jobs[key] = task; task.start()
    }
    private fun fail(key: String) { failedUntil[key] = System.currentTimeMillis() + 60_000; if (failedUntil.size > 256) failedUntil.remove(failedUntil.keys.first()) }
    private fun put(key: String, face: String) {
        faces[key] = face; order.remove(key); order.add(key)
        while (order.size > 256) { val oldest = order.first(); order.remove(oldest); faces.remove(oldest) }
    }
}
val LocalAuthorAvatars = staticCompositionLocalOf<AuthorAvatars?> { null }

@Composable fun VideoAuthorAvatar(video: Video, modifier: Modifier = Modifier) {
    val avatars = LocalAuthorAvatars.current
    val face = video.authorFace.ifBlank { avatars?.face(video).orEmpty() }
    LaunchedEffect(avatars, video.id, video.authorId, video.authorFace) { avatars?.load(video) }
    var loaded by remember(face) { mutableStateOf(false) }
    Box(modifier.testTag(if (loaded) "video_author_avatar_loaded" else "video_author_avatar_loading"), contentAlignment = Alignment.Center) {
        if (!loaded) InterfaceIcon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        AsyncImage(face.takeIf { it.isNotBlank() }, "UP 主头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
