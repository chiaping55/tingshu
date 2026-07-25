package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.getMobileUA
import com.github.eprendre.tingshu.extensions.showToast
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlJsoupExtractor
import com.github.eprendre.tingshu.sources.ISearchVerification
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
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * DedeCMS 听书站群通用基类。
 *
 * 这一批站(听书吧、海洋听书、有声听书吧…)共用同一套 DedeCMS 模板，选择器一致，
 * 但各站把 url 里的路径段改了名字，所以路径段做成参数交给子类填。
 *
 * 页面结构(以听书吧为例)：
 * - 分类  /{categoryPath}/{分类id}.html ，翻页 /{categoryPath}/{分类id}-{页码}.html
 * - 详情  /{bookPath}/{书籍id}.html ，章节全在这一页、不分页
 * - 播放  /play/{书籍id}-{线路}-{序号}.html
 * - 音频  播放页内嵌 js 里明文写着 var now="http://....m4a"，一次请求即可，不需要 WebView
 *
 * 搜索接口有验证码闸(标题"系统安全验证")，所以实现了 [ISearchVerification]
 * 让 app 弹出页面由用户自己过一次验证；没过验证时搜索会拿到验证页，这时提示用户而不是静默失败。
 */
abstract class DedeCmsTingShu : TingShu(), ISearchVerification, AudioUrlExtraHeaders {
    /** 站点根地址，不带结尾斜杠 */
    abstract val baseUrl: String

    /** 分类页的路径段，例如听书吧是 "books" */
    abstract val categoryPath: String

    /** 详情页的路径段，例如听书吧是 "mp3" */
    abstract val bookPath: String

    /** 分类清单，pair 为 (分类id, 显示名) */
    abstract val categories: List<Pair<String, String>>

    override fun getUrl() = baseUrl

    override fun isWebViewNotRequired() = true

    protected open fun configure(connection: Connection): Connection = connection.config(true)

    private fun fetch(url: String, referer: String? = null): Document {
        val connection = configure(Jsoup.connect(url))
        if (referer != null) {
            connection.referrer(referer)
        }
        return connection.get()
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        return listOf(
            CategoryMenu(
                "分类",
                categories.map { (id, name) ->
                    CategoryTab(name, "$baseUrl/$categoryPath/$id.html")
                }
            )
        )
    }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val doc = fetch(searchUrl(keywords), "$baseUrl/")
        if (isVerificationPage(doc)) {
            showToast("站点要求先过一次安全验证，请在弹出的页面完成后重试")
            return Pair(emptyList(), 1)
        }
        return Pair(parseBooks(doc), 1)// 搜索结果不分页
    }

    private fun searchUrl(keywords: String) =
        "$baseUrl/search.php?searchword=${URLEncoder.encode(keywords, "UTF-8")}"

    private fun isVerificationPage(doc: Document) = doc.title().contains("安全验证")

    override fun getSearchVerificationUrl(keywords: String) = searchUrl(keywords)

    override fun getSearchVerificationUA() = getMobileUA()

    override fun getSearchDelayMs() = 1000L

    override fun getCategoryList(url: String): Category {
        val doc = fetch(url, "$baseUrl/")
        val currentPage = Regex("""-(\d+)\.html""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val totalPage = parseTotalPage(doc, currentPage)
        val categoryId = Regex("""/$categoryPath/(\d+)""").find(url)?.groupValues?.get(1)
        val nextUrl = if (categoryId != null && currentPage < totalPage)
            "$baseUrl/$categoryPath/$categoryId-${currentPage + 1}.html" else ""
        return Category(parseBooks(doc), currentPage, totalPage, url, nextUrl)
    }

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        val doc = fetch(bookUrl, "$baseUrl/")
        val coverUrl = doc.selectFirst("span.img-box img")?.absUrl("src") ?: ""
        val descriptionText = doc.select("p.f-gray").joinToString(" ") { it.text() }
        val intro = Regex("内容介绍[：:](.*)").find(descriptionText)?.groupValues?.get(1)?.trim()
            ?: descriptionText
        // 站点这段 html 里 <a> 是坏的嵌套，从文字里取比选择器可靠
        val credits = Regex("作者[：:](.+?)，\\s*由(.+?)播音").find(descriptionText)
        val author = credits?.groupValues?.get(1)?.trim() ?: ""
        val artist = credits?.groupValues?.get(2)?.trim() ?: ""

        val episodes = if (loadEpisodes) {
            doc.select("div#yuedu ul.ul-36 li a").map {
                Episode(it.attr("title").ifEmpty { it.text() }, it.absUrl("href"))
            }
        } else {
            emptyList()
        }
        return BookDetail(episodes, intro, artist, author, episodes.size, coverUrl)
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlJsoupExtractor.setUp(true) { doc -> audioUrl(doc.html()) }
        return AudioUrlJsoupExtractor
    }

    /**
     * 播放页的内嵌 js: var now="http://audio.xmcdn.com/....m4a"; var next="…"。
     * next 是下一集，只取 now。
     */
    internal fun audioUrl(playPageHtml: String): String {
        return Regex("""\bvar\s+now\s*=\s*"(https?://[^"]+)"""")
            .find(playPageHtml)?.groupValues?.get(1) ?: ""
    }

    /**
     * 喜马拉雅新的 cdn(aod.cos.tx.xmcdn.com)校验防盗链，不带 Referer 直接 403，
     * 旧的 audio.xmcdn.com 不校验。这里严格判断域名再加，避免影响其它书源的音频请求。
     */
    override fun headers(audioUrl: String): Map<String, String> {
        val headers = HashMap<String, String>()
        if (audioUrl.contains("xmcdn.com")) {
            headers["Referer"] = "$baseUrl/"
        }
        return headers
    }

    internal fun parseBooks(doc: Document): List<Book> {
        return doc.select("ul.row-b > li").mapNotNull { item ->
            val titleLink = item.selectFirst("h2.style-title a") ?: return@mapNotNull null
            Book(
                item.selectFirst("span.img-box img")?.absUrl("src") ?: "",
                titleLink.absUrl("href"),
                titleLink.text(),
                item.selectFirst("h2.style-title span.fr")?.text()?.trim() ?: "",
                ""// 列表页只给作者，播音要进详情页
            ).apply {
                intro = item.selectFirst("p.f-gray")?.text() ?: ""
                sourceId = getSourceId()
            }
        }
    }

    /**
     * 分页栏带"尾页"链接，可直接拿到真实总页数；拿不到就退回看有没有下一页。
     */
    internal fun parseTotalPage(doc: Document, currentPage: Int): Int {
        val links = doc.select("div.page a, span.page a, a")
            .mapNotNull { link ->
                Regex("""/$categoryPath/\d+-(\d+)\.html""")
                    .find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
        val maxPage = links.maxOrNull() ?: return currentPage
        return maxOf(maxPage, currentPage)
    }

    private fun textOf(element: Element?) = element?.text()?.trim() ?: ""
}
