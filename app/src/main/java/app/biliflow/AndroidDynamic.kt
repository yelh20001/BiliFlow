package app.biliflow

import org.json.JSONObject

private fun paragraphBlocks(paragraph: ProtoMessage?): List<DynamicBlock> {
    if (paragraph == null) return emptyList()
    val text = paragraph.message(3)?.messages(1).orEmpty().joinToString("") { it.text(2) }
    val pictures = paragraph.message(4)?.message(1)?.messages(1).orEmpty().mapNotNull { picture ->
        picture.text(1).takeIf { it.isNotBlank() }?.let { url ->
            val w = picture.long(2); val h = picture.long(3)
            DynamicBlock(picture = secureUrl(url), ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f)
        }
    }
    return (if (text.isNotBlank()) listOf(DynamicBlock(text = text)) else emptyList()) + pictures
}

private fun appDynamicItem(item: ProtoMessage, fallbackId: String = ""): DynamicPost {
    val modules = item.messages(3)
    val extend = item.message(4)
    val id = extend?.text(1).orEmpty().ifBlank { fallbackId }
    val author = modules.firstNotNullOfOrNull { it.message(2) ?: it.message(19)?.message(3) }
    val forwardAuthor = modules.firstNotNullOfOrNull { it.message(13) }
    val user = author?.message(3)
    val mid = author?.long(1)?.takeIf { it > 0 } ?: user?.long(1)?.takeIf { it > 0 } ?: forwardAuthor?.long(3) ?: 0
    val name = user?.text(2).orEmpty().ifBlank { forwardAuthor?.messages(1).orEmpty().joinToString("") { it.text(1) } }
    val avatar = secureUrl(user?.text(3).orEmpty().ifBlank { forwardAuthor?.text(6).orEmpty() })
    val time = author?.text(2).orEmpty().ifBlank { forwardAuthor?.text(4).orEmpty() }
    val desc = modules.mapNotNull { it.message(4) }.joinToString("\n") { it.text(3).ifBlank { it.messages(1).joinToString("") { d -> d.text(1) } } }.trim()
    val blocks = modules.flatMap { module ->
        val paragraph = module.message(32)?.message(1)
        val opus = module.message(30)
        paragraphBlocks(paragraph) + paragraphBlocks(opus?.message(2))
    }
    val dynamic = modules.firstNotNullOfOrNull { it.message(5) }
    val archive = dynamic?.message(2)
    val avid = archive?.long(7) ?: 0
    val bvid = archive?.text(26).orEmpty().ifBlank { if (avid > 0) runCatching { AppProtocol.bvid(avid) }.getOrDefault("") else "" }
    val video = if (bvid.isNotBlank()) Video(bvid, archive?.text(1).orEmpty(), secureUrl(archive?.text(2).orEmpty()), name,
        archive?.long(27) ?: 0, duration((archive?.long(20) ?: 0).toInt()), authorId = mid, authorFace = avatar) else null
    val draw = dynamic?.message(7)?.messages(1).orEmpty().map { secureUrl(it.text(1)) }.filter { it.isNotBlank() }
    val pictures = (draw + blocks.map { it.picture }.filter { it.isNotBlank() }).distinct()
    val original = dynamic?.message(6)?.message(1)?.let { appDynamicItem(it, extend?.text(3).orEmpty()) }
        ?: extend?.text(3)?.takeIf { it.isNotBlank() }?.let { originalId ->
            DynamicPost(originalId, extend.text(4), secureUrl(extend.text(17)), 0, "", extend.messages(6).joinToString("") { it.text(1) })
        }
    val stats = modules.firstNotNullOfOrNull { it.message(9) }
    val article = dynamic?.message(8)
    val title = article?.text(3).orEmpty()
    val attachments = buildList {
        dynamic?.message(3)?.let { add(DynamicAttachment("番剧", it.text(1), cover = secureUrl(it.text(2)), url = it.text(3))) }
        dynamic?.message(10)?.let { add(DynamicAttachment("内容", it.text(3), it.text(4), secureUrl(it.text(5)), it.text(2))) }
        article?.let { add(DynamicAttachment("专栏", it.text(3), it.text(4), secureUrl(it.strings(5).firstOrNull().orEmpty()), it.text(2))) }
        dynamic?.message(9)?.let { add(DynamicAttachment("音频", it.text(4), it.text(6), url = it.text(2))) }
        dynamic?.message(12)?.let { add(DynamicAttachment("播单", it.text(3), it.text(4), secureUrl(it.text(5)), it.text(2))) }
        modules.mapNotNull { it.message(8) }.forEach { additional ->
            additional.message(3)?.let { add(DynamicAttachment("商品", it.text(1), url = it.text(5))) }
            additional.message(5)?.let { add(DynamicAttachment("附加内容", it.text(2), listOf(it.text(4), it.text(5)).filter(String::isNotBlank).joinToString(" · "), secureUrl(it.text(3)), it.text(6))) }
            additional.message(8)?.let { add(DynamicAttachment("投票", it.text(3), it.text(4), url = it.text(17))) }
            additional.message(9)?.let { add(DynamicAttachment("视频", it.text(2), it.text(4), secureUrl(it.text(3)), it.text(6))) }
            additional.message(10)?.let { add(DynamicAttachment("预约", it.text(1), it.text(3), url = it.text(4))) }
            additional.message(12)?.let { add(DynamicAttachment("专栏", it.text(1), it.text(3), secureUrl(it.message(2)?.text(1).orEmpty()), it.text(5))) }
            additional.message(13)?.let { add(DynamicAttachment("直播", it.text(1), it.text(5), secureUrl(it.text(2)), it.text(6))) }
        }
        modules.mapNotNull { it.message(16) }.forEach { add(DynamicAttachment("动态", it.text(2))) }
        val otherType = when (item.long(1)) {
            4L, 14L, 24L -> "课程"
            5L -> "折叠动态"
            16L -> "小程序"
            17L, 21L -> "订阅"
            20L -> "合集"
            22L -> "故事"
            23L, 25L -> "话题"
            26L, 27L -> "通知"
            else -> ""
        }
        if (otherType.isNotEmpty() && isEmpty()) add(DynamicAttachment(otherType, "此卡片暂不能完整展示", url = extend?.text(15).orEmpty()))
        if (item.long(1) != 0L && video == null && pictures.isEmpty() && original == null && desc.isBlank() && blocks.isEmpty() && isEmpty()) {
            add(DynamicAttachment("其他动态", "此类型暂不能完整展示", url = extend?.text(15).orEmpty()))
        }
    }
    val live = dynamic?.message(11)?.long(1) ?: dynamic?.message(15)?.text(1)?.let { raw -> runCatching { JSONObject(raw).optJSONObject("live_play_info")?.optLong("room_id") ?: 0 }.getOrDefault(0) } ?: 0
    val content = desc.ifBlank { blocks.map { it.text }.filter { it.isNotBlank() }.joinToString("\n") }.ifBlank { article?.text(4).orEmpty() }
    return DynamicPost(id, name, avatar, mid, time, content, pictures, video, original,
        stats?.long(2) ?: 0, stats?.long(3) ?: 0, stats?.long(1) ?: 0, title, live,
        attachments = attachments, appBlocks = blocks, appType = item.long(1))
}

internal fun parseAndroidDynamicPage(bytes: ByteArray, personal: Boolean): DynamicPage {
    val response = ProtoMessage(bytes)
    val list = if (personal) response else response.message(1) ?: error("安卓动态响应缺少列表")
    val items = list.messages(1)
    val posts = items.mapIndexed { index, item -> appDynamicItem(item, "app-${bytes.contentHashCode()}-$index") }
    val upList = if (personal) null else response.message(2)
    val authors = if (personal) emptyList() else (upList?.messages(2).orEmpty() + upList?.messages(10).orEmpty()).mapNotNull { item ->
        val id = item.long(4); if (id <= 0) null else FollowedAuthor(id, item.text(3), secureUrl(item.text(2)), item.long(1) != 0L, upList?.text(3).orEmpty(), item.text(17))
    }.distinctBy { it.id }
    return DynamicPage(posts, list.text(if (personal) 2 else 3), list.long(if (personal) 3 else 5) != 0L, authors, if (personal) list.text(4) else "")
}

internal suspend fun BiliApi.androidDynamic(offset: String, authorId: Long? = null, footprint: String = "", personalExtra: String = "", preload: Boolean = true): DynamicPage {
    val personal = authorId != null
    val payload = if (personal) ProtoWriter().number(1, authorId!!).string(2, offset).number(4, if (preload) 1 else 0).number(6, 8).string(7, footprint).string(10, personalExtra).build()
        else ProtoWriter().string(2, offset).number(4, if (offset.isEmpty()) 0 else 1).number(7, 8).build()
    return parseAndroidDynamicPage(appDynamicGrpc(if (personal) "DynAllPersonal" else "DynAll", payload), personal)
}

internal suspend fun BiliApi.markAndroidDynamicRead(author: FollowedAuthor, readOffset: String) {
    if (readOffset.isNotBlank()) {
        val payload = ProtoWriter().number(1, author.id).string(2, readOffset).string(3, author.footprint).string(4, author.personalExtra).build()
        appDynamicGrpc("DynAllUpdOffset", payload)
    } else {
        // A non-preload personal request tells the server that the page was viewed.
        androidDynamic("", author.id, author.footprint, author.personalExtra, preload = false)
    }
}

internal suspend fun BiliApi.androidDynamicDocument(id: String): DynamicDocument {
    require(id.isNotBlank() && id.all(Char::isDigit)) { "原动态不存在或已删除" }
    val payload = ProtoWriter().string(2, id).number(10, 8).build()
    val item = ProtoMessage(appDynamicGrpc("DynDetail", payload)).message(1) ?: error("动态不存在或无法查看")
    val post = appDynamicItem(item, id)
    val content = if (post.appBlocks.isEmpty()) dynamicPreview(post).blocks else {
        (if (post.text.isNotBlank() && post.appBlocks.none { it.text.isNotBlank() }) listOf(DynamicBlock(text = post.text)) else emptyList()) + post.appBlocks
    }
    return DynamicDocument(post, content + post.pictures.filter { picture -> content.none { it.picture == picture } }.map { DynamicBlock(picture = it) })
}
