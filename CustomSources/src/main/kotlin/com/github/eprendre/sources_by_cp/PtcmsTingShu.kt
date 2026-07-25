package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import com.github.eprendre.tingshu.extensions.showToast
import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.Episode
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import kotlin.random.Random

/**
 * PTCMS 听书站通用基类。
 *
 * 这一套模板被多个站点共用，站点之间只有域名和分类不同，所以解析逻辑全部放在这里，
 * 子类只需提供 baseUrl / 名称 / sourceId / 分类菜单。
 *
 * 页面结构：
 * - 搜索  $baseUrl/search.html?searchtype=name&searchword=xx&page=1
 * - 分类  $baseUrl/book/{类型代号}/lastupdate.html ，翻页 .../lastupdate/{页码}.html
 * - 详情  $baseUrl/book/{书籍id}.html
 * - 章节  详情页 a.dirurl 指向 /bookdir/xx/yy.html ，每页 50 集，?page=N&sort=asc 为正序
 * - 音频  章节页 iframe#play -> /player.html?nid=&cid=&site= ，明文写在 js 变量 urlXXX 里
 *
 * 注意站点带 guard 反爬：首次请求返回一段混淆 js，解出 token 写进 pt_guid cookie 后重新请求
 * 才会拿到真正的页面，见 [fetch]。
 */
abstract class PtcmsTingShu : TingShu() {
    /** 站点根地址，不带结尾斜杠，例如 https://www.qdysw.com */
    abstract val baseUrl: String

    override fun getUrl() = baseUrl

    override fun isWebViewNotRequired() = true

    override fun isMultipleEpisodePages() = true

    /** guard cookie 有效期 2 小时，存下来复用，避免每个请求都要握手两次 */
    private val cookies = HashMap<String, String>()

    private val pageList = ArrayList<Int>()

    override fun reset() {
        pageList.clear()
    }

    /**
     * 交给子类或测试替换请求配置。正式代码用 config()，从 app 设置读 UA；
     * 单元测试里换成 testConfig() 就能在 JVM 上直接跑。
     */
    protected open fun configure(connection: Connection): Connection = connection.config(true)

    /**
     * 同上，也是为了能在 JVM 上跑测试：app 里用来显示"正在加载章节列表: x/y"。
     */
    protected open fun notifyLoading(pageInfo: String?) = notifyLoadingEpisodes(pageInfo)

    private fun connect(url: String, referer: String?): Connection {
        val connection = configure(Jsoup.connect(url)).cookies(cookies)
        if (referer != null) {
            connection.referrer(referer)
        }
        return connection
    }

    /**
     * 请求页面并处理 guard。
     *
     * 挑战页会同时下发 pt_browser_id cookie，而 js 里那个 token 就是用这个 id 算出来的，
     * 所以两个 cookie 都要带上服务端才认，只补 pt_guid 会一直拿到挑战页。
     */
    private fun request(url: String, referer: String?): Connection.Response {
        var response = connect(url, referer).execute()
        cookies.putAll(response.cookies())
        val token = guardToken(response.body())
        if (token != null) {
            cookies["pt_guid"] = URLEncoder.encode(token, "UTF-8")
            cookies["ptcms_guard_retry"] = "1"
            response = connect(url, referer).execute()
            cookies.putAll(response.cookies())
        }
        return response
    }

    internal fun fetch(url: String, referer: String? = null): Document = request(url, referer).parse()

    internal fun fetchHtml(url: String, referer: String? = null): String = request(url, referer).body()

    /**
     * guard 挑战页里藏着一段倒序的 base64，解出来是设置 pt_guid cookie 的 js。
     * 返回 null 表示这次拿到的就是正常页面。
     */
    private fun guardToken(html: String): String? {
        val reversed = Regex("""var\s+reversed\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1) ?: return null
        val script = String(base64Decode(reversed.reversed()), Charsets.UTF_8)
        return Regex("""var\s+token\s*=\s*'([^']+)'""").find(script)?.groupValues?.get(1)
    }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val url = "$baseUrl/search.html?searchtype=name" +
            "&searchword=${URLEncoder.encode(keywords, "UTF-8")}&page=$page"
        val doc = fetch(url, "$baseUrl/")
        return Pair(parseBooks(doc), parseTotalPage(doc, page))
    }

    override fun getCategoryList(url: String): Category {
        val doc = fetch(url)
        val pager = doc.selectFirst("div.fanye")
        val currentPage = pager?.selectFirst("strong")?.text()?.trim()?.toIntOrNull() ?: 1
        val nextUrl = pager?.select("a")
            ?.firstOrNull { it.text().contains("下一页") }
            ?.absUrl("href") ?: ""
        return Category(parseBooks(doc), currentPage, parseTotalPage(doc, currentPage), url, nextUrl)
    }

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        val doc = fetch(bookUrl)
        val coverUrl = imageUrl(doc.selectFirst("div.book-img img"))
        val intro = doc.selectFirst("div.book-des")?.text() ?: ""
        val artist = doc.select("div.book-info dd")
            .firstOrNull { it.text().contains("演播") }
            ?.selectFirst("a")?.text() ?: ""

        val episodes = ArrayList<Episode>()
        if (loadEpisodes) {
            // 详情页只列最新几集且是倒序，完整章节在 a.dirurl 指向的目录页。
            val dirUrl = doc.selectFirst("a.dirurl")?.absUrl("href")
            if (dirUrl == null) {
                episodes.addAll(parseEpisodes(doc).reversed())
            } else {
                val firstPage = fetch("$dirUrl?page=1&sort=asc", bookUrl)
                episodes.addAll(parseEpisodes(firstPage))
                val totalPage = parseEpisodeTotalPage(firstPage)
                if (loadFullPages && totalPage > 1) {
                    pageList.clear()
                    pageList.addAll(2..totalPage)
                    while (pageList.isNotEmpty()) {
                        val page = pageList.removeAt(0)
                        notifyLoading("$page / $totalPage")
                        episodes.addAll(parseEpisodes(fetch("$dirUrl?page=$page&sort=asc", bookUrl)))
                        Thread.sleep(Random.nextLong(100, 500))
                    }
                    notifyLoading(null)
                }
            }
        }
        return BookDetail(episodes, intro, artist, "", episodes.size, coverUrl)
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { chapterUrl -> resolveAudioUrl(chapterUrl) }
        return AudioUrlCustomExtractor
    }

    /**
     * 章节页 -> player.html -> 音频地址。
     *
     * player.html 的 site 参数是线路号，站点给的默认线路经常是空的(尤其最新几集)，
     * 所以拿不到地址时要换其它线路重试。
     */
    internal fun resolveAudioUrl(chapterUrl: String): String {
        val playerUrl = fetch(chapterUrl).selectFirst("iframe#play")?.absUrl("src")
        if (playerUrl.isNullOrEmpty()) {
            showToast("找不到播放器，站点可能改版了")
            return ""
        }
        audioUrl(fetchHtml(playerUrl, chapterUrl)).let { if (it.isNotEmpty()) return it }
        for (site in FALLBACK_SITES) {
            val url = audioUrl(fetchHtml(withSite(playerUrl, site), chapterUrl))
            if (url.isNotEmpty()) return url
        }
        showToast("这一集所有线路都没有音频，换一集试试")
        return ""
    }

    private fun withSite(playerUrl: String, site: Int): String {
        val pattern = Regex("""([?&]site=)\d*""")
        return if (pattern.containsMatchIn(playerUrl)) playerUrl.replace(pattern, "$1$site")
        else "$playerUrl&site=$site"
    }

    /**
     * player.html 里音频地址是明文，变量名带一串随机数字：urlXXXXXX = 'http...'，
     * 扩展名单独放在 murlXXXXXX 里。站点有些线路给的 url 不带扩展名，
     * 这时必须接上 murl，否则 cdn 直接回 403。
     * 用 \b 开头是为了不误匹配 murl / preurl / nexturl。
     */
    internal fun audioUrl(playerHtml: String): String {
        val url = Regex("""\burl\d*\s*=\s*'(https?://[^']*)'""")
            .find(playerHtml)?.groupValues?.get(1) ?: ""
        if (url.isEmpty()) return ""
        val hasExtension = AUDIO_SUFFIXES.any { url.endsWith(it, ignoreCase = true) }
        if (hasExtension) return url
        val suffix = Regex("""\bmurl\d*\s*=\s*'([^']*)'""")
            .find(playerHtml)?.groupValues?.get(1) ?: ""
        return url + suffix
    }

    internal fun parseBooks(doc: Document): List<Book> {
        return doc.select("ul.list-works > li").mapNotNull { item ->
            val titleLink = item.selectFirst("dt.list-book-dt a") ?: return@mapNotNull null
            val status = item.selectFirst("dt.list-book-dt span")?.text() ?: ""
            Book(
                imageUrl(item.selectFirst("div.list-imgbox img")),
                titleLink.absUrl("href"),
                titleLink.text(),
                "",// 站点列表和详情页都不提供作者，只有演播
                item.selectFirst("dd.list-book-cs span.book-author a")?.text() ?: ""
            ).apply {
                intro = item.selectFirst("dd.list-book-des")?.text() ?: ""
                this.status = status
                isCompleted = status.contains("完结")
                sourceId = getSourceId()
            }
        }
    }

    internal fun parseEpisodes(doc: Document): List<Episode> {
        return doc.select("div#playlist ul li a").map { Episode(it.text(), it.absUrl("href")) }
    }

    /**
     * 章节目录页的"快速选集"每一项对应一页(1~50、51~100…)，数量就是总页数。
     */
    internal fun parseEpisodeTotalPage(doc: Document): Int {
        val chunks = doc.select("ul.js_chapter_ul > li").size
        return if (chunks > 0) chunks else 1
    }

    /**
     * 分页栏只显示当前附近的页码，没有"尾页"链接，所以拿不到真实总页数时
     * 按基类约定用"当前页+1"表示还有下一页。
     */
    internal fun parseTotalPage(doc: Document, currentPage: Int): Int {
        val pager = doc.selectFirst("div.fanye") ?: return currentPage
        val maxNumbered = pager.select("a, strong")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .maxOrNull() ?: currentPage
        val hasNext = pager.select("a").any { it.text().contains("下一页") }
        return if (hasNext && maxNumbered <= currentPage) currentPage + 1
        else maxOf(maxNumbered, currentPage)
    }

    /** 列表图片是懒加载的，真地址在 data-original，src 只是占位图 */
    private fun imageUrl(img: Element?): String {
        if (img == null) return ""
        val lazy = img.absUrl("data-original")
        return if (lazy.isNotEmpty()) lazy else img.absUrl("src")
    }

    /**
     * 自己实现 base64 解码：项目不引入 android 依赖，java.util.Base64 又要 api 26。
     */
    private fun base64Decode(input: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in input) {
            val value = BASE64_ALPHABET.indexOf(char)
            if (value < 0) continue// 跳过 '=' 和空白
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    protected companion object {
        private const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private val AUDIO_SUFFIXES = listOf(".mp3", ".m4a", ".aac", ".m4s", ".wav")

        /**
         * 默认线路拿不到地址时依次尝试的线路号。
         * 实测同一集里 11、14 给的地址自带扩展名，其它几条要接 murl，都能播。
         */
        private val FALLBACK_SITES = listOf(11, 14, 2, 13, 15, 17, 20, 8)

        /**
         * 生成分类菜单。传入 代号 to 名称，url 按 /book/{代号}/lastupdate.html 拼。
         */
        fun menu(baseUrl: String, title: String, vararg tabs: Pair<String, String>): CategoryMenu {
            return CategoryMenu(title, tabs.map { (code, name) ->
                com.github.eprendre.tingshu.utils.CategoryTab(
                    name,
                    "$baseUrl/book/$code/lastupdate.html"
                )
            })
        }
    }
}
