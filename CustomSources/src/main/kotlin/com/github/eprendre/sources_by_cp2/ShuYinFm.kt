package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 书音FM https://m.mekui.com
 *
 * 2026-11 新找到的站:5.5 万本,内容是酷我畅听的库(封面在 img1.kuwo.cn、TME 有声剧场、
 * 冠声文化那批多人剧),等于绕过了酷我自己下架的章节接口。音频最终是酷我 cdn 的 mp3 直链,
 * 不需要 WebView。
 *
 * Next.js 前端 + 帝国CMS 后端,数据全走 `/ecmsapi/index.php` 的 JSON 接口(2026-11-09 逐个验过):
 *   分类导航  mod=column&act=navigation&classid=0        -> {code, list[{classid, classname, sonclass "|1|2|…"}]}
 *   子分类    mod=column&act=navigation&classid={父id}    -> 同上
 *   书籍列表  mod=movie&act=list&classid=&page=&pagesize=20[&sort=onclick desc] -> {code, data{total, totalpage, list[]}}
 *   搜索      mod=movie&act=search&keyword=&page=&pagesize=20 -> 同上
 *   书籍详情  mod=movie&act=detail&id=                   -> {code, data{detail{title, titlepic, player, playadmin, moviesay, filetype}}}
 *   章节列表  mod=movie&act=movielist&id=&page=&pagesize=500 -> {code, data{count, totalpage, moielist[{id, title}]}}(站方就拼成 moielist)
 *   音频地址  act=wapseries&mod=movie&id=&movieId=&t=&token= -> {code, data{SeriesUrl}} 302 到酷我 mp3
 *
 * 三个脾气:
 * 1. **每个请求都要带 User-Agent**,不带直接 403。
 * 2. **音频接口要签名**:把 act/id/mod/movieId/t 按 key 升序拼成 query,末尾接
 *    `&token=<站方前端写死的 key>`,整串取 MD5 当 token 参数。签名只算 query 本身,
 *    key 不上传。时间戳是秒。这是站方网页自己的播放流程,照做即可。
 * 3. SeriesUrl 是一个会 302 的中转地址,这里手动跟到最终 mp3 再交给播放器 ——
 *    免得播放器那边不跟跳转或带了不该带的 header。
 *
 * 逻辑全放基类,正式源 [ShuYinFm] 是空壳 —— 测试用真逻辑跑,只换掉 app 的 notifyLoading。
 */
abstract class ShuYinFmBase : TingShu() {

    /** app 里显示「正在加载章节列表 x/y」;测试覆写成打印 */
    protected open fun notifyLoading(pageInfo: String?) = notifyLoadingEpisodes(pageInfo)

    /** 分类菜单缓存:导航要打 1 + 子分类数 次接口,发现页每次进都重打没意义 */
    private var cachedMenus: List<CategoryMenu>? = null

    override fun getSourceId() = "9d2e4b6a8c1f4e7b9a3d5c0f2e8b1a4c"

    override fun getUrl() = "$BASE/"

    override fun getName() = "书音FM"

    override fun getDesc() = "推荐指数:4星 ⭐⭐⭐⭐\n" +
        "酷我畅听的库(5 万多本):TME 有声剧场、冠声文化等多人剧,和其它网文站的演播版本不同。\n" +
        "音频直链不用内置浏览器,手表也能用。"

    override fun isWebViewNotRequired() = true

    /** 章节接口 500 集一页,让 app 走分页加载的逻辑 */
    override fun isMultipleEpisodePages() = true

    private fun enc(s: String) = URLEncoder.encode(s, "utf-8")

    private fun getJson(query: String): JSONObject =
        Fuel.get("$API?$query")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .header("Accept", "application/json, text/plain, */*")
            .timeout(15000).timeoutRead(20000)
            .responseJson().third.get().obj()

    /** 站方把封面写成 http,混合内容在 app 里会被挡 */
    private fun https(url: String) = url.replaceFirst("http://", "https://")

    private fun bookUrl(id: String) = "$API?mod=movie&act=detail&id=$id"

    private fun bookId(bookUrl: String) = Regex("""[?&]id=(\d+)""").find(bookUrl)?.groupValues?.get(1)

    private fun episodeUrl(workId: String, movieId: String) =
        "$API?act=wapseries&mod=movie&id=$workId&movieId=$movieId"

    private fun listUrl(classId: String, sort: String, page: Int): String {
        val sb = StringBuilder("$API?mod=movie&act=list&classid=$classId&page=$page&pagesize=$PAGE_SIZE")
        if (sort.isNotEmpty()) sb.append("&sort=").append(enc(sort))
        return sb.toString()
    }

    /** 列表/搜索/详情三处的字段一样:id / title / titlepic / player(演播) / playadmin(作者) / moviesay / filetype */
    private fun toBook(item: JSONObject): Book? {
        val id = item.optString("id")
        if (id.isEmpty()) return null
        val status = item.optString("filetype")
        return Book(https(item.optString("titlepic")), bookUrl(id), item.optString("title"),
            item.optString("playadmin"), item.optString("player").replace(";", " ")).apply {
            intro = item.optString("moviesay")
            this.status = status
            isCompleted = status.contains("完结")
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

    /** list / search 的响应形状一样:data{totalpage, list[]} */
    private fun pageOf(root: JSONObject, page: Int): Pair<List<Book>, Int> {
        if (root.optInt("code") != 1) return Pair(emptyList(), page)
        val data = root.optJSONObject("data") ?: return Pair(emptyList(), page)
        return Pair(toBooks(data.optJSONArray("list")), maxOf(data.optInt("totalpage", 1), 1))
    }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return try {
            val kw = enc(ChineseConverter.toSimplified(keywords))
            val (books, totalPage) = pageOf(getJson("mod=movie&act=search&keyword=$kw&page=$page&pagesize=$PAGE_SIZE"), page)
            ChineseConverter.restoreKeywordInTitles(books, keywords)
            Pair(books, totalPage)
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
                    CategoryTab("最新上架", listUrl("0", "", 1)),
                    CategoryTab("最多播放", listUrl("0", "onclick desc", 1))
                )
            )
        )
        try {
            val tops = getJson("mod=column&act=navigation&classid=0").optJSONArray("list") ?: JSONArray()
            for (i in 0 until tops.length()) {
                val top = tops.getJSONObject(i)
                val classId = top.optString("classid")
                val name = top.optString("classname")
                if (classId.isEmpty() || name.isEmpty()) continue
                val tabs = ArrayList<CategoryTab>()
                tabs.add(CategoryTab("全部", listUrl(classId, "", 1)))
                if (top.optString("sonclass").trim('|').isNotEmpty()) {
                    try {
                        val sons = getJson("mod=column&act=navigation&classid=$classId").optJSONArray("list") ?: JSONArray()
                        for (j in 0 until sons.length()) {
                            val son = sons.getJSONObject(j)
                            val sonId = son.optString("classid")
                            val sonName = son.optString("classname")
                            if (sonId.isEmpty() || sonName.isEmpty()) continue
                            tabs.add(CategoryTab(sonName, listUrl(sonId, "", 1)))
                        }
                    } catch (e: Exception) {
                        // 子分类拿不到就只留「全部」
                    }
                }
                menus.add(CategoryMenu(name, tabs))
            }
        } catch (e: Exception) {
            // 导航拿不到就只剩排行那组,别让整个发现页空掉
        }
        if (menus.size > 1) cachedMenus = menus
        return menus
    }

    override fun getCategoryList(url: String): Category {
        val classId = Regex("""classid=(\d+)""").find(url)?.groupValues?.get(1) ?: "0"
        val currentPage = Regex("""[?&]page=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 1
        val sort = Regex("""[?&]sort=([^&]*)""").find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "utf-8") } ?: ""
        val (books, totalPage) = pageOf(getJson(url.substringAfter('?')), currentPage)
        val nextUrl = if (currentPage < totalPage) listUrl(classId, sort, currentPage + 1) else ""
        return Category(books, currentPage, maxOf(totalPage, currentPage), url, nextUrl)
    }

    private val pageList = ArrayList<Int>()

    override fun reset() {
        pageList.clear()
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val id = bookId(bookUrl) ?: return BookDetail(emptyList())
        val book = try {
            getJson("mod=movie&act=detail&id=$id").optJSONObject("data")?.optJSONObject("detail")?.let { toBook(it) }
        } catch (e: Exception) {
            null
        }
        val episodes = ArrayList<Episode>()
        var count = 0
        if (loadEpisodes) {
            val first = getJson(episodesQuery(id, 1)).optJSONObject("data") ?: JSONObject()
            count = first.optInt("count")
            val totalPage = maxOf(first.optInt("totalpage", 1), 1)
            episodes.addAll(parseEpisodes(id, first))
            if (loadFullPages && totalPage > 1) {
                pageList.addAll(2..totalPage)
                while (pageList.isNotEmpty()) {
                    val page = pageList.removeAt(0)
                    notifyLoading("$page / $totalPage")
                    episodes.addAll(parseEpisodes(id, getJson(episodesQuery(id, page)).optJSONObject("data") ?: JSONObject()))
                    Thread.sleep(Random.nextLong(100, 400))
                }
                notifyLoading(null)
            }
        }
        return BookDetail(episodes, book?.intro ?: "", book?.artist ?: "", book?.author ?: "",
            if (count > 0) count else episodes.size, book?.coverUrl ?: "")
    }

    private fun episodesQuery(id: String, page: Int) =
        "mod=movie&act=movielist&id=$id&page=$page&pagesize=$EPISODE_PAGE_SIZE"

    private fun parseEpisodes(workId: String, data: JSONObject): List<Episode> {
        val list = ArrayList<Episode>()
        val array = data.optJSONArray("moielist") ?: return list
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val movieId = item.optString("id")
            if (movieId.isEmpty()) continue
            list.add(Episode(item.optString("title"), episodeUrl(workId, movieId)))
        }
        return list
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { chapterUrl -> resolveAudioUrl(chapterUrl) }
        return AudioUrlCustomExtractor
    }

    /** 签名取址 → SeriesUrl → 手动跟 302 到最终 mp3;任何一步拿不到都回空,让 app 照实提示 */
    internal fun resolveAudioUrl(chapterUrl: String): String {
        val workId = Regex("""[?&]id=(\d+)""").find(chapterUrl)?.groupValues?.get(1) ?: return ""
        val movieId = Regex("""[?&]movieId=(\d+)""").find(chapterUrl)?.groupValues?.get(1) ?: return ""
        return try {
            val query = signedAudioQuery(workId, movieId, System.currentTimeMillis() / 1000)
            val root = getJson(query)
            if (root.optInt("code") != 1) return ""
            val data = root.optJSONObject("data") ?: return ""
            var audio = data.optString("SeriesUrl")
            if (audio.isEmpty()) {
                val signed = data.optJSONArray("signed_urls")
                if (signed != null) {
                    for (i in 0 until signed.length()) {
                        val s = signed.optString(i)
                        if (s.isNotEmpty()) { audio = s; break }
                    }
                }
            }
            if (audio.isEmpty()) "" else followRedirects(audio)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 站方前端的签名规则:参数按 key 升序拼成 query,末尾接 `&token=<key>` 取 MD5,
     * 再把这个 MD5 当 token 参数挂在 query 后面(key 本身不上传)。
     * 拆成纯函数,好用离线测试钉住拼法 —— 顺序拼错一个字签名就全错,表象只是「取不到地址」。
     */
    internal fun signedAudioQuery(workId: String, movieId: String, timestamp: Long): String {
        val raw = "act=wapseries&id=$workId&mod=movie&movieId=$movieId&t=$timestamp"
        return "$raw&token=${md5("$raw&token=$TOKEN_KEY")}"
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    /** 最多跟 3 次 302;跟不动就把当前地址交出去,交给播放器再试 */
    private fun followRedirects(start: String): String {
        var current = start
        repeat(3) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Referer", "$BASE/")
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code !in 300..399 || location.isNullOrEmpty()) return current
                current = URL(URL(current), location).toString()
            } catch (e: Exception) {
                return current
            } finally {
                conn?.disconnect()
            }
        }
        return current
    }

    companion object {
        const val BASE = "https://m.mekui.com"
        const val API = "$BASE/ecmsapi/index.php"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /** 站方网页前端写死的签名 key(见类注释) */
        private const val TOKEN_KEY = "056a308c515e16b2fe5a5c631319339cbc60a8ee0e03d016"
        private const val PAGE_SIZE = 20
        const val EPISODE_PAGE_SIZE = 500
    }
}

object ShuYinFm : ShuYinFmBase()
