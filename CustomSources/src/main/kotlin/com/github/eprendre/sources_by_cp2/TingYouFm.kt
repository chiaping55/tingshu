package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import com.github.eprendre.tingshu.utils.Episode
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.json.responseJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * 听友听书 https://www.tingyou.fm/listening
 *
 * 2026-11 新找到的站:多人有声剧、名家演播(大斌《剑来》、头陀渊、老宝玉这类喜马拉雅上的
 * 热门版本)、评书按演播人分类(单田芳/刘兰芳/田连元…),片源和现有的网文有声站都不同。
 * 音频是签名直链(file.hgeuz.cn,回 audio/mpeg),支持 Range、不校验 UA/Referer(实测裸请求 206),
 * 所以不实现 AudioUrlExtraHeaders —— 这个专案在「为了救 A 加 header 结果弄坏 B」上吃过亏。
 *
 * 站点是 Nuxt 前端,数据全走 `/listening-api` 下的 JSON 接口(2026-11-08 逐个验过):
 *   分类筛选  GET  filters                     -> sorts[] / statuses[] / categories[{id,name,types[{id,name}]}]
 *   分类列表  GET  category?page=&category=&type=&sort=&status=  -> {data[], page, pages}(pages 封顶 100)
 *   搜索      POST search {keyword, page}      -> {results[], total, has_more}(只接受 POST)
 *   专辑      GET  album/{id}                  -> {id,title,author,teller,cat,cover_url,count,status,synopsis…}
 *   章节      GET  chapters/{id}               -> {chapters:[{index,title,…}]} 一次全给(5000 多集也一次)
 *   播放      POST play {album_id, chapter_idx} -> {play_url}
 * 接口习惯:带 x-listening-session(UUID v4,会话内复用)与 x-listening-timezone;
 * 少了也能用,但照网页的做法带上,少踩风控。
 *
 * 搜索走 POST + JSON body(GET 会回「method not allowed」),关键词必须是 UTF-8 ——
 * 2026-11-08 用 Git Bash 的 curl 探测时中文被送成乱码、每个词都回 0 条,差点误判成站方搜索坏了;
 * 从 Kotlin 发同样的请求「剑来」就有 10 条。接口只给 has_more、没有总页数。
 *
 * 逻辑全放在基类,正式源 [TingYouFm] 是空壳 —— 测试用真的逻辑跑,不维护会漂移的复本。
 */
abstract class TingYouFmBase : TingShu() {

    /** 会话 ID,接口要 UUID v4,进程内生成一次复用 */
    private val sessionId: String by lazy { UUID.randomUUID().toString() }

    /** 分类菜单缓存:filters 一次拿全,发现页每次进都请求没有意义 */
    private var cachedMenus: List<CategoryMenu>? = null

    override fun getSourceId() = "3f9c2b7d5e1a4c8f9b6d0a2e7c4f1b3d"

    override fun getUrl() = "$BASE/listening"

    override fun getName() = "听友听书"

    override fun getDesc() = "推荐指数:4星 ⭐⭐⭐⭐\n" +
        "多人有声剧、名家演播版本多(《剑来》大斌、头陀渊、老宝玉等),评书按演播人分类。\n" +
        "音频直链不用内置浏览器,手表也能用。"

    override fun isWebViewNotRequired() = true

    /** 章节接口一次全给 */
    override fun isMultipleEpisodePages() = false

    private fun headers() = mapOf(
        "User-Agent" to UA,
        "Referer" to "$BASE/listening",
        "Accept" to "application/json",
        "x-listening-session" to sessionId,
        "x-listening-timezone" to "Asia/Shanghai"
    )

    private fun getJson(path: String): JSONObject =
        Fuel.get("$API/$path").header(headers())
            .timeout(15000).timeoutRead(20000)
            .responseJson().third.get().obj()

    private fun postJson(path: String, body: JSONObject): JSONObject =
        Fuel.post("$API/$path").header(headers()).header("Content-Type", "application/json")
            .body(body.toString())
            .timeout(15000).timeoutRead(20000)
            .responseJson().third.get().obj()

    private fun enc(s: String) = URLEncoder.encode(s, "utf-8")

    private fun param(url: String, name: String): String =
        Regex("""[?&]$name=([^&]*)""").find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "utf-8") } ?: ""

    /** 专辑地址用站方的网页地址表示:是个真网址,id 也取得回来 */
    private fun bookUrl(id: String) = "$BASE/listening/album/$id"

    private fun episodeUrl(albumId: String, index: Int) = "$API/play?album_id=$albumId&chapter_idx=$index"

    private fun categoryUrl(category: String, type: String, sort: String, page: Int): String {
        val sb = StringBuilder("$API/category?page=$page")
        if (category.isNotEmpty()) sb.append("&category=").append(enc(category))
        if (type.isNotEmpty()) sb.append("&type=").append(enc(type))
        if (sort.isNotEmpty()) sb.append("&sort=").append(enc(sort))
        return sb.toString()
    }

    /** 专辑对象 -> Book。列表、搜索、详情三处的字段一样 */
    private fun toBook(item: JSONObject): Book? {
        val id = item.optString("id").ifEmpty { item.optString("album_id") }
        if (id.isEmpty()) return null
        val cover = item.optString("cover_url").replaceFirst("http://", "https://")
        val completed = item.optInt("status", 1) == 0
        return Book(cover, bookUrl(id), item.optString("title"), item.optString("author"), item.optString("teller")).apply {
            intro = listOf("synopsis", "description", "intro").map { item.optString(it) }.maxByOrNull { it.length } ?: ""
            status = (if (completed) "已完结" else "连载中") +
                item.optInt("count").let { if (it > 0) " · $it 集" else "" }
            isCompleted = completed
            sourceId = getSourceId()
        }
    }

    private fun toBooks(array: JSONArray?): List<Book> {
        val list = ArrayList<Book>()
        if (array == null) return list
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { toBook(it) }?.let { list.add(it) }
        }
        return list
    }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return try {
            val root = postJson("search", JSONObject().put("keyword", ChineseConverter.toSimplified(keywords)).put("page", page))
            val books = toBooks(root.optJSONArray("results") ?: root.optJSONArray("items"))
            ChineseConverter.restoreKeywordInTitles(books, keywords)
            // 接口只给 has_more,没有总页数
            Pair(books, if (root.optBoolean("has_more") && books.isNotEmpty()) page + 1 else page)
        } catch (e: Exception) {
            Pair(emptyList(), 1)
        }
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        cachedMenus?.let { return it }
        val menus = ArrayList<CategoryMenu>()
        menus.add(
            CategoryMenu(
                "排行",
                listOf(
                    CategoryTab("播放最多", categoryUrl("", "", "popular", 1)),
                    CategoryTab("最近更新", categoryUrl("", "", "updated", 1)),
                    CategoryTab("最新发布", categoryUrl("", "", "new", 1)),
                    CategoryTab("综合推荐", categoryUrl("", "", "comprehensive", 1))
                )
            )
        )
        try {
            val cats = getJson("filters").optJSONArray("categories") ?: JSONArray()
            for (i in 0 until cats.length()) {
                val cat = cats.getJSONObject(i)
                val catId = cat.optString("id")
                val catName = cat.optString("name")
                if (catId.isEmpty() || catName.isEmpty()) continue
                val tabs = ArrayList<CategoryTab>()
                tabs.add(CategoryTab("全部", categoryUrl(catId, "", "", 1)))
                val types = cat.optJSONArray("types") ?: JSONArray()
                for (j in 0 until types.length()) {
                    val t = types.getJSONObject(j)
                    val typeId = t.optString("id")
                    val typeName = t.optString("name")
                    if (typeId.isEmpty() || typeName.isEmpty()) continue
                    tabs.add(CategoryTab(typeName, categoryUrl(catId, typeId, "", 1)))
                }
                menus.add(CategoryMenu(catName, tabs))
            }
        } catch (e: Exception) {
            // filters 拿不到就只剩排行那组,别让整个发现页空掉
        }
        if (menus.size > 1) cachedMenus = menus
        return menus
    }

    override fun getCategoryList(url: String): Category {
        val category = param(url, "category")
        val type = param(url, "type")
        val sort = param(url, "sort")
        val currentPage = param(url, "page").toIntOrNull() ?: 1
        val root = getJson(url.substringAfter("$API/"))
        val books = toBooks(root.optJSONArray("data") ?: root.optJSONArray("items"))
        val totalPage = maxOf(root.optInt("pages", 1), 1)
        val nextUrl = if (currentPage < totalPage) categoryUrl(category, type, sort, currentPage + 1) else ""
        return Category(books, currentPage, totalPage, url, nextUrl)
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val id = Regex("""/album/(\d+)""").find(bookUrl)?.groupValues?.get(1) ?: return BookDetail(emptyList())
        val book = try { toBook(getJson("album/$id")) } catch (e: Exception) { null }
        val episodes = ArrayList<Episode>()
        if (loadEpisodes) {
            val chapters = getJson("chapters/$id").optJSONArray("chapters") ?: JSONArray()
            val indexed = ArrayList<Pair<Int, Episode>>()
            for (i in 0 until chapters.length()) {
                val c = chapters.getJSONObject(i)
                val index = c.optInt("index", 0)
                if (index <= 0) continue
                indexed.add(index to Episode(c.optString("title").ifEmpty { "第 $index 集" }, episodeUrl(id, index)))
            }
            indexed.sortBy { it.first }
            episodes.addAll(indexed.map { it.second })
        }
        return BookDetail(
            episodes, book?.intro ?: "", book?.artist ?: "", book?.author ?: "",
            episodes.size, book?.coverUrl ?: ""
        )
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { chapterUrl -> resolveAudioUrl(chapterUrl) }
        return AudioUrlCustomExtractor
    }

    /** POST play 换签名直链;拿不到回空,让 app 照实提示 */
    internal fun resolveAudioUrl(chapterUrl: String): String {
        val albumId = param(chapterUrl, "album_id")
        val index = param(chapterUrl, "chapter_idx").toIntOrNull() ?: return ""
        if (albumId.isEmpty()) return ""
        return try {
            postJson("play", JSONObject().put("album_id", albumId).put("chapter_idx", index))
                .optString("play_url").replaceFirst("http://", "https://")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val BASE = "https://www.tingyou.fm"
        const val API = "$BASE/listening-api"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

object TingYouFm : TingYouFmBase()
