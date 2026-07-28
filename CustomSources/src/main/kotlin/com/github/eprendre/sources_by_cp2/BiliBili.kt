package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.extensions.getMobileUA
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlWebViewSniffExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.*
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.json.responseJson
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 哔哩哔哩 https://m.bilibili.com —— B 站音频区(UP 主上传的有声小说/广播剧/同人音声)。
 *
 * 和其它源完全不同的片源:很多多人有声剧、同人音声只有 B 站有。搜索走公开搜索 API,
 * 音频靠 WebView 加载播放页再嗅探(B 站是 DASH,音轨得让页面自己跑出来)。
 *
 * 从原作者停维护的 sources_by_eprendre 移植过来并修好:
 * 1. 原来的 bvid 解析 `replace("BV1","")` 会把 bvid 截断,取不到选集 —— 改成取完整 bvid,
 *    章节用 `x/web-interface/view?bvid=` 的 `data.pages`(旧的 view/detail 那条也换掉)。
 * 2. 接上繁→简:B 站上的有声书标题多是简体,繁体输入转简体才搜得到;再把结果书名里的
 *    关键词还原成用户原词,好通过 app 聚合搜索的书名过滤(见 [ChineseConverter])。
 * 3. 搜索被 B 站风控挡下(code != 0)时回空,而不是 NPE 崩掉。
 */
object BiliBili : TingShu() {
    // B 站基础风控靠 buvid3/buvid4 cookie 识别,不带的话境外 IP 搜索会被 -412 拦截。
    // 首次用到时向 finger/spi 取一次、之后复用。加短逾时,免得卡住整个聚合搜索。
    private val cookie: String by lazy {
        try {
            val data = Fuel.get("https://api.bilibili.com/x/frontend/finger/spi")
                .header("User-Agent", getMobileUA())
                .timeout(6000).timeoutRead(6000)
                .responseJson().third.get().obj().getJSONObject("data")
            "buvid3=${data.optString("b_3")}; buvid4=${data.optString("b_4")}"
        } catch (e: Exception) {
            ""
        }
    }

    private fun headers() = mapOf(
        "User-Agent" to getMobileUA(),
        "Referer" to "https://m.bilibili.com",
        "Cookie" to cookie
    )

    // 不沿用 eprendre 版的 sourceId:使用者若还订着那包,两个源同 id 会被 app 当成
    // 同一个源去重,聚合搜索只采用其中一个(往往是旧的、被风控回空的那个)。
    override fun getSourceId() = "a12b84c0b39a4c8fb0d90bb6a7bc2973"

    override fun getUrl() = "https://m.bilibili.com"

    override fun getName() = "哔哩哔哩"

    override fun getDesc() = "推荐指数:3星 ⭐⭐⭐\n" +
        "B 站的多人有声剧、同人音声,很多是别处没有的片源。\n" +
        "开播放页要多等几秒(音频经内置浏览器加载),手表等无浏览器的设备用不了。"

    /** 音频靠 WebView 嗅探,所以需要 WebView */
    override fun isWebViewNotRequired() = false

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return try {
            val kw = ChineseConverter.toSimplified(keywords)
            val url = "https://api.bilibili.com/x/web-interface/search/all/v2" +
                "?keyword=${URLEncoder.encode(kw, "utf-8")}&page=$page&pagesize=20"
            val obj = Fuel.get(url).header(headers())
                .timeout(8000).timeoutRead(8000)
                .responseJson().third.get().obj()
            if (obj.optInt("code", -1) != 0 || obj.isNull("data")) {
                Pair(emptyList(), 1)
            } else {
                val data = obj.getJSONObject("data")
                val books = parseVideoResults(data)
                ChineseConverter.restoreKeywordInTitles(books, keywords)
                Pair(books, data.optInt("numPages", 1))
            }
        } catch (e: Exception) {
            Pair(emptyList(), 1)
        }
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        return listOf(
            CategoryMenu(
                "推荐", listOf(
                    CategoryTab("有声小说", "有声小说&&1"),
                    CategoryTab("同人音声", "同人音声&&1"),
                    CategoryTab("英文小说", "audiobooks&&1"),
                    CategoryTab("经典老歌", "经典老歌&&1"),
                    CategoryTab("音乐推荐", "音乐推荐&&1"),
                    CategoryTab("有声漫画", "有声漫画&&1")
                )
            )
        )
    }

    override fun getCategoryList(url: String): Category {
        val params = url.split("&&")
        val keywords = params[0]
        val currentPage = params[1].toInt()
        val currentUrl = "https://api.bilibili.com/x/web-interface/search/all/v2" +
            "?keyword=${URLEncoder.encode(keywords, "utf-8")}&page=$currentPage&pagesize=20"
        val obj = Fuel.get(currentUrl).header(headers()).responseJson().third.get().obj()
        if (obj.optInt("code", -1) != 0 || obj.isNull("data")) {
            return Category(emptyList(), currentPage, currentPage, url, "")
        }
        val data = obj.getJSONObject("data")
        val numPages = data.optInt("numPages", currentPage)
        val nextUrl = if (currentPage < numPages) "$keywords&&${currentPage + 1}" else ""
        return Category(parseVideoResults(data), currentPage, numPages, url, nextUrl)
    }

    /** search/all/v2 的结果按 result_type 分块,取第一块 video 组装成书 */
    private fun parseVideoResults(data: JSONObject): List<Book> {
        val list = ArrayList<Book>()
        val results = data.optJSONArray("result") ?: return list
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            if (result.optString("result_type") != "video") continue
            val videos = result.optJSONArray("data") ?: continue
            for (j in 0 until videos.length()) {
                val v = videos.getJSONObject(j)
                val title = v.optString("title")
                    .replace("<em class=\"keyword\">", "").replace("</em>", "")
                val cover = v.optString("pic").let { if (it.startsWith("//")) "https:$it" else it }
                list.add(
                    Book(cover, "https://m.bilibili.com/video/${v.optString("bvid")}", title, "", v.optString("author")).apply {
                        status = "播放次数: ${v.optInt("play")}"
                        intro = v.optString("description")
                        sourceId = getSourceId()
                    }
                )
            }
            break // 只取第一块 video
        }
        return list
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val episodes = ArrayList<Episode>()
        if (loadEpisodes) {
            val bvid = bookUrl.substringAfterLast("/video/").substringBefore("?").trim('/')
            val url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            val obj = Fuel.get(url).header(headers()).responseJson().third.get().obj()
            if (obj.optInt("code", -1) == 0 && !obj.isNull("data")) {
                val pages = obj.getJSONObject("data").getJSONArray("pages")
                (0 until pages.length()).forEach {
                    val item = pages.getJSONObject(it)
                    episodes.add(Episode(item.getString("part"), "$bookUrl?p=${item.getInt("page")}"))
                }
            }
        }
        return BookDetail(episodes)
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlWebViewSniffExtractor.setUp(false, null)
        return AudioUrlWebViewSniffExtractor
    }
}
