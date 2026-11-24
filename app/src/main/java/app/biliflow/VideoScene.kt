package app.biliflow

data class VideoScene(val detail: VideoDetail? = null, val fans: Long? = null, val interaction: VideoInteraction = VideoInteraction(),
    val comments: CommentsSnapshot = CommentsSnapshot(), val related: List<Video> = emptyList(), val relatedVisible: Int = 8)

/** Metadata only; inactive destinations never retain a player, surface or screenshot. */
class VideoSceneCache {
    private val values = LinkedHashMap<String, VideoScene>()
    fun get(key: String): VideoScene = values[key] ?: VideoScene()
    fun put(key: String, scene: VideoScene) {
        values.remove(key); values[key] = scene
        while (values.size > 8) values.remove(values.keys.first())
    }
    fun clear() { values.clear() }
}
