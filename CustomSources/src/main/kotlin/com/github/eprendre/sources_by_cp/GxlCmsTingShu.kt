package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.showToast
import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import com.github.eprendre.tingshu.utils.Episode
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/**
 * GXLCMS 听书站群通用基类(恋听网一系)。
 *
 * 这是继 PTCMS、DedeCMS 之后的第三个模板家族，页面结构和前两家完全不同：
 * - 分类  /{slug}/ ，翻页 /{slug}/index{页码}.html
 * - 详情  /{slug}/{书籍id}.html ，章节全在这一页、不分页
 * - 播放  /{slug}/{书籍id}/0-{集数}.html
 * - 搜索  POST 到 [searchPath]，表单字段 wd
 *
 * **音频取址是 POST 换 JSON，不需要 WebView** —— 播放页的 meta 里放着 bookId、集数与一枚
 * 令牌，POST 给取址接口换回真实地址。这点比爱听书那种被商业混淆的 player js 友好得多，
 * 手表之类没有 WebView 的设备也能用。
 *
 * 这个家族至少有两个站(ting15、恋听网 ting55)共用同一套取址契约 —— meta 名称一模一样，
 * ting15 的播放函数甚至直接叫 `ting55_play`。所以路由做成参数交给子类填，取址逻辑共用。
 */
abstract class GxlCmsTingShu : TingShu(), AudioUrlExtraHeaders {
    /** 站点根地址，不带结尾斜杠 */
    abstract val baseUrl: String

    /** 分类清单，pair 为 (路径段, 显示名)，例如 "jingdianpingshu" to "经典评书" */
    abstract val categories: List<Pair<String, String>>

    /** 取址接口，各站不同(ting15 是 "/?s=api-getneoplay"，恋听网是 "/nlinka") */
    abstract val audioApiPath: String

    /** 搜索接口，POST 表单字段固定叫 wd */
    protected open val searchPath: String = "/?s=ting-search"

    /** 书名后缀，站点会在标题后面加"有声小说"之类，显示时去掉 */
    protected open val titleSuffix: String = "有声小说"

    override fun getUrl() = baseUrl

    /** 音频是 POST 换回来的直链，不需要 WebView */
    override fun isWebViewNotRequired() = true

    /** 测试会覆写这里，替换掉只有 app 才有实现的 config() */
    protected open fun configure(connection: Connection): Connection = connection.config(true)

    /** 测试会覆写，app 里弹 toast */
    protected open fun toast(message: String) {
        showToast(message)
    }

    private fun connect(url: String, referer: String?): Connection {
        val connection = configure(Jsoup.connect(url))
        if (referer != null) {
            connection.referrer(referer)
        }
        return connection
    }

    internal fun fetch(url: String, referer: String? = null): Document =
        connect(url, referer).get()

    override fun getCategoryMenus(): List<CategoryMenu> {
        return listOf(
            CategoryMenu(
                "分类",
                categories.map { (slug, name) -> CategoryTab(name, "$baseUrl/$slug/") }
            )
        )
    }

    /**
     * 搜索第 1 页是 POST，**第 2 页起是 GET**，见 [searchPageUrl]。
     *
     * 原本这里写死「搜索结果不分页，固定回 1 页」—— 那是错的，站方分页栏就有「尾页」。
     * 实测搜「天」有 14 页(约 140 笔)，而使用者只拿得到前 10 笔。
     * 同样的错在爱听书那个源上也犯过一次:**别假设搜索不分页，去看分页栏**。
     */
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val doc = if (page <= 1) {
            connect("$baseUrl$searchPath", "$baseUrl/")
                .method(Connection.Method.POST)
                .data("wd", keywords)
                .post()
        } else {
            fetch(searchPageUrl(keywords, page), "$baseUrl/")
        }
        return Pair(parseBooks(doc), parseSearchTotalPage(doc, page))
    }

    /**
     * 搜索第 2 页起的地址。ting15 形如 `/?s=ting-search-wd-{关键词}-p-{页码}.html`。
     * 同族的恋听网路由不同，所以做成可覆写。
     */
    protected open fun searchPageUrl(keywords: String, page: Int): String =
        "$baseUrl/?s=ting-search-wd-${URLEncoder.encode(keywords, "UTF-8")}-p-$page.html"

    /** 给测试用:searchPageUrl 是 protected */
    internal fun searchPageUrlForTest(keywords: String, page: Int) = searchPageUrl(keywords, page)

    /**
     * 搜索结果的总页数。
     *
     * **不能重用 [parseTotalPage]** —— 那个认的是分类页的 `/index{N}.html`，
     * 而搜索页的分页链接长得完全不同(`-p-{N}.html`)，套上去会一页都找不到。
     */
    internal fun parseSearchTotalPage(doc: Document, currentPage: Int): Int {
        val maxPage = doc.select("div.c-page a")
            .mapNotNull {
                Regex("""-p-(\d+)\.html""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: return currentPage
        return maxOf(maxPage, currentPage)
    }

    override fun getCategoryList(url: String): Category {
        val doc = fetch(url, "$baseUrl/")
        val slug = categorySlug(url)
        val currentPage = Regex("""/index(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val totalPage = parseTotalPage(doc, currentPage)
        val nextUrl = if (slug != null && currentPage < totalPage)
            "$baseUrl/$slug/index${currentPage + 1}.html" else ""
        return Category(parseBooks(doc), currentPage, totalPage, url, nextUrl)
    }

    private fun categorySlug(url: String): String? =
        Regex("""/([a-z]+)/(?:index\d*[^/]*\.html)?$""").find(url)?.groupValues?.get(1)

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        val doc = fetch(bookUrl, "$baseUrl/")
        val coverUrl = doc.selectFirst("div.bimg img, div.book-img img, div.img img")
            ?.absUrl("src") ?: ""
        val intro = doc.selectFirst("div.intro p")?.text()?.trim() ?: ""
        // 详情页把作者/播音写成"作者：X"这样的纯文本，选择器各站略有出入，从文字里取更稳
        val text = doc.select("div.binfo, div.book-info, div.info").text()
            .ifEmpty { doc.body().text() }
        val author = field(text, "作者")
        val artist = field(text, "播音").ifEmpty { field(text, "主播") }

        val episodes = if (loadEpisodes) {
            doc.select("div.plist ul li a, div.playlist ul li a").map {
                Episode(it.text().trim(), it.absUrl("href"))
            }
        } else {
            emptyList()
        }
        return BookDetail(episodes, intro, artist, author, episodes.size, coverUrl)
    }

    private fun field(text: String, name: String): String =
        Regex("""$name[:：]\s*([^\s，,。]{1,20})""").find(text)?.groupValues?.get(1)?.trim() ?: ""

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { chapterUrl -> resolveAudioUrl(chapterUrl) }
        return AudioUrlCustomExtractor
    }

    /**
     * 播放页 meta 里带着取址所需的一切：书籍 id、当前集数、以及一枚**每次现发的令牌**。
     * 令牌不能缓存，所以每集都要先取播放页再换地址。
     */
    internal fun resolveAudioUrl(chapterUrl: String): String {
        val doc = fetch(chapterUrl, "$baseUrl/")
        val bookId = meta(doc, "_b")
        val page = meta(doc, "_cp")
        val token = meta(doc, "_c")
        if (bookId.isEmpty() || page.isEmpty() || token.isEmpty()) {
            toast("播放页少了取址参数，站点可能改版了")
            return ""
        }
        val body = connect("$baseUrl$audioApiPath", chapterUrl)
            .method(Connection.Method.POST)
            .header("xt", token)
            .header("l", meta(doc, "_l"))
            .header("X-Requested-With", "XMLHttpRequest")
            .data("bookId", bookId)
            .data("isPay", meta(doc, "_p").ifEmpty { "0" })
            .data("page", page)
            .ignoreContentType(true)
            .execute()
            .body()
        return parseAudioResponse(body)
    }

    /**
     * 取址接口的回应：{"url":"…","status":1,"ourl":"","plink":""}
     *
     * status 的语义必须分清，混在一起会误导用户：
     * - 1  正常，地址在 ourl(优先)或 url
     * - 0  这一集缺音频
     * - -1 付费章节
     * - -2 **限流**(站点提示「访问过快，请1小时后再听」)，惩罚是锁一小时。
     *      绝不能当成"这本书失效了" —— 那会让用户以为源坏了，实际只要等一会。
     *      也因为这个，取址只能播到哪集取哪集，不能预取、不能批量拉。
     */
    internal fun parseAudioResponse(body: String): String {
        val json = body.trim().removePrefix("﻿")
        val status = Regex(""""status"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)
        when (status) {
            "-2" -> throw IOException("站点限流：访问过快，请过一小时再听(这不是源失效)")
            "-1" -> {
                toast("这一集是付费章节")
                return ""
            }
            "0" -> {
                toast("这一集没有音频，换一集试试")
                return ""
            }
        }
        // ourl 优先，为空才用 url
        for (key in arrayOf("ourl", "url")) {
            val value = Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            if (!value.isNullOrEmpty()) {
                return encodeNonAscii(decodeJsonString(value))
            }
        }
        toast("没取到音频地址，换一集或过一会再试")
        return ""
    }

    /**
     * JSON 字符串的转义要还原，不能只处理 `\/`。
     *
     * 这个站的音频路径里带中文(例如 `ps/田连元_孙庞斗智/01.mp3`)，在 JSON 里是
     * `田连元` 这种 `\uXXXX` 转义。漏掉的话地址里会留着字面上的
     * "田"，请求过去当然拿不到音频 —— 而且表象是"地址取到了却播不出来"。
     */
    internal fun decodeJsonString(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append(c)
                        i++
                    }
                }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                // \/ \\ \" 之类，转义字符本身就是结果
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /**
     * 地址里的非 ASCII 字符要做百分号编码。
     *
     * 这个站的音频文件名带中文(`/uploads/t010/15-无敌剑域-有声的紫襟/0001.m4a`)，
     * 带着原始中文去请求 cdn 回 404，编码过才给 200 —— 而且这是解开 unicode 转义之后
     * 才冒出来的第二层问题：前一步把转义还原成了真正的中文字符。
     *
     * 只动非 ASCII 部分，`?` `&` `/` `%` 这些结构字符与已经编码好的 %XX 都不碰。
     */
    internal fun encodeNonAscii(url: String): String {
        if (url.all { it.code <= 0x7F }) return url
        val out = StringBuilder(url.length + 16)
        for (char in url) {
            if (char.code <= 0x7F) {
                out.append(char)
            } else {
                for (byte in char.toString().toByteArray(Charsets.UTF_8)) {
                    out.append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }
        return out.toString()
    }

    private fun meta(doc: Document, name: String): String =
        doc.selectFirst("meta[name=$name]")?.attr("content")?.trim() ?: ""

    /**
     * 音频 cdn 校验防盗链：不带 Referer 直接 400(实测)。
     */
    override fun headers(audioUrl: String): Map<String, String> =
        mapOf("Referer" to "$baseUrl/")

    internal fun parseBooks(doc: Document): List<Book> {
        return doc.select("div.category-list ul > li").mapNotNull { item ->
            val link = item.selectFirst("div.info h4 a") ?: item.selectFirst("div.img a")
                ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty { link.text() }.removeSuffix(titleSuffix)
            val fields = item.select("div.info p").associate { p ->
                val parts = p.text().split("：", ":", limit = 2)
                (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
            }
            Book(
                item.selectFirst("img")?.absUrl("src") ?: "",
                link.absUrl("href"),
                title,
                fields["作者"] ?: "",
                fields["播音"] ?: fields["主播"] ?: ""
            ).apply {
                intro = fields["状态"]?.let { "状态：$it" } ?: ""
                sourceId = getSourceId()
            }
        }
    }

    /**
     * 分页栏带"尾页"链接，可直接拿到真实总页数。
     */
    internal fun parseTotalPage(doc: Document, currentPage: Int): Int {
        val maxPage = doc.select("div.c-page a")
            .mapNotNull {
                Regex("""/index(\d+)\.html""").find(it.attr("href"))?.groupValues?.get(1)
                    ?.toIntOrNull()
            }
            .maxOrNull() ?: return currentPage
        return maxOf(maxPage, currentPage)
    }
}
