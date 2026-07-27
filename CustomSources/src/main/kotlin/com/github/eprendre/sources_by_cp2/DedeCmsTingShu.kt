package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.getMobileUA
import com.github.eprendre.tingshu.extensions.showToast
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
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
 * **不要给音频请求加 Referer。** 曾经以为喜马拉雅新 cdn 校验防盗链所以统一加上，
 * 实测正相反：aod.cos.tx.xmcdn.com 加不加都一样，而 fdfs.xmcdn.com 一带 Referer 就回 403 ——
 * 16 个样本里有 4 个本来能播的被这一行改成了 403。这也是听书吧可播率一度只有 48% 的主因。
 *
 * 搜索接口有验证码闸(标题"系统安全验证")，所以实现了 [ISearchVerification]
 * 让 app 弹出页面由用户自己过一次验证；没过验证时搜索会拿到验证页，这时提示用户而不是静默失败。
 */
abstract class DedeCmsTingShu : TingShu(), ISearchVerification {
    /** 站点根地址，不带结尾斜杠 */
    abstract val baseUrl: String

    /** 分类页的路径段，例如听书吧是 "books" */
    abstract val categoryPath: String

    /** 详情页的路径段，例如听书吧是 "mp3" */
    abstract val bookPath: String

    /**
     * 分类清单，pair 为 (分类id, 显示名)。
     * 这批站的分类编号是一样的，默认给全套 24 个，站点有出入时再覆写。
     */
    open val categories: List<Pair<String, String>> = STANDARD_CATEGORIES

    override fun getUrl() = baseUrl

    override fun isWebViewNotRequired() = true

    protected open fun configure(connection: Connection): Connection = connection.config(true)

    /**
     * app 里弹提示；测试覆写成打印。
     *
     * PtcmsTingShu 和 GxlCmsTingShu 都有这层，这里以前漏了 —— 结果 search() 一走到
     * 「站点要求先过一次安全验证」那条就直接呼叫 showToast stub 抛异常，
     * 在 JVM 上根本没法测。而这两个站的验证闸目前是**常开**的
     * (search.php 一律回「系统安全验证」)，所以是必定踩到、不是偶发。
     */
    protected open fun toast(message: String) = showToast(message)

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
            toast("站点要求先过一次安全验证，请在弹出的页面完成后重试")
            return Pair(emptyList(), 1)
        }
        val books = parseBooks(doc)
        ChineseConverter.restoreKeywordInTitles(books, keywords)
        return Pair(books, 1)// 搜索结果不分页
    }

    // 转简体再搜:站方库是简体的，繁体会命中归零。放这里让 search 和验证页 callback 都受益
    private fun searchUrl(keywords: String) =
        "$baseUrl/search.php?searchword=${URLEncoder.encode(ChineseConverter.toSimplified(keywords), "UTF-8")}"

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
    ): BookDetail = parseDetail(fetch(bookUrl, "$baseUrl/"))

    /** 给测试用:把解析和取页面分开，才能拿本地 html 验证选择器而不发网络 */
    internal fun parseDetailForTest(doc: Document) = parseDetail(doc, loadEpisodes = true)

    private fun parseDetail(doc: Document, loadEpisodes: Boolean = true): BookDetail {
        val coverUrl = doc.selectFirst("span.img-box img")?.absUrl("src") ?: ""
        // **只看本书自己那一块**。整页有 27 个 p.f-gray，本书只占 3 个，其余全是侧栏推荐；
        // 原本 select("p.f-gray") 全抓来串成一行，于是 `内容介绍[：:](.*)` 的 `.*`
        // 一路吃到字符串结尾 —— 每本书的简介尾巴都黏上「超品相师作者：…，由…播音」
        // 这类侧栏文案(实测两站 12 本全中)。作者/播音的 regex 跑在同一个字符串上，
        // 现在靠"本书的 credits 排在最前面"才对，某本书自己那段不符 pattern 时
        // 会静静抓到侧栏第一本推荐书的作者 —— 变成错资料而不是空值，那更难发现。
        val infoBlock = doc.selectFirst("div.style-img") ?: doc.body()
        val descriptionText = infoBlock.select("p.f-gray").joinToString(" ") { it.text() }
        val intro = Regex("内容介绍[：:](.*)").find(descriptionText)?.groupValues?.get(1)?.trim()
            ?: descriptionText
        // 站点这段 html 里 <a> 是坏的嵌套，从文字里取比选择器可靠
        val credits = Regex("作者[：:](.+?)，\\s*由(.+?)播音").find(descriptionText)
        val author = credits?.groupValues?.get(1)?.trim() ?: ""
        val artist = credits?.groupValues?.get(2)?.trim() ?: ""

        val episodes = if (loadEpisodes) {
            doc.select("div#yuedu ul.ul-36 li a").map {
                // title 属性要 trim —— 站方有些书整本的章节名都带尾 tab(实测 1089 集全带)
                Episode(it.attr("title").trim().ifEmpty { it.text().trim() }, it.absUrl("href"))
            }
        } else {
            emptyList()
        }
        return BookDetail(episodes, intro, artist, author, episodes.size, coverUrl)
    }

    /**
     * 用 [AudioUrlCustomExtractor] 而不是 [AudioUrlJsoupExtractor]。
     *
     * 差别在于**允不允许在里面发网络请求**：Jsoup 那个的契约写明 lambda 是"处理返回的 Document"，
     * 纯解析，没有保证跑在哪个线程上；而 Custom 那个的契约是"传入章节地址，解析后传出音频地址"，
     * 自己取页面本来就是它的活(PtcmsTingShu 一直这么用，实机验证过)。
     *
     * 这里需要发请求是因为 [pickPlayable] 要探测哪个音质后缀能播。
     * 放在 Jsoup 那个里面的话，万一它在主线程上跑就会 NetworkOnMainThreadException ——
     * 虽然会被 catch 住退回原地址、不至于崩，但会变成不同机型行为不一致，将来很难查。
     */
    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { chapterUrl -> resolveAudioUrl(chapterUrl) }
        return AudioUrlCustomExtractor
    }

    /** 取章节页 → 解出音频地址(含探测哪个后缀能播) */
    internal fun resolveAudioUrl(chapterUrl: String): String =
        audioUrl(fetch(chapterUrl, "$baseUrl/").html())

    /**
     * 播放页的内嵌 js: var now="http://audio.xmcdn.com/....m4a"; var next="…"。
     * next 是下一集，只取 now。
     */
    internal fun audioUrl(playPageHtml: String): String {
        val url = Regex("""\bvar\s+now\s*=\s*"(https?://[^"]+)"""")
            .find(playPageHtml)?.groupValues?.get(1) ?: return ""
        return pickPlayable(url)
    }

    /**
     * 站点给的地址和实际能播的地址不一定一致，**逐个试过再交出去**。
     *
     * 喜马拉雅 /storages/ 下有些文件名带音质后缀 `-aacv2-48K`、有些不带，而站点两种都可能写错。
     * 实测 16 个样本：原样能播 8 个，原样失败改用带后缀的又能救回 2 个 ——
     * 而这两组之间**没有可以靠地址本身判断的规律**(同样是 /storages/…audiofreehighqps/…，
     * 一边要后缀一边不要)，所以只能试。
     *
     * 代价是每集多一个几百字节的探测请求，换来的是可播率从一半提到六成以上。
     * 都试不通就交回原地址，让 app 照常报错 —— 那种是站上的文件真的没了。
     */
    internal fun pickPlayable(url: String): String {
        val candidates = ArrayList<String>()
        candidates.add(url)
        withQualitySuffix(url)?.let { candidates.add(it) }
        if (candidates.size == 1) return url
        for (candidate in candidates) {
            if (isPlayable(candidate)) return candidate
        }
        return url
    }

    /** 给可播率量测用:isPlayable 是 protected */
    internal fun isPlayableForTest(url: String) = isPlayable(url)

    /** 只读一个字节，够判断服务器认不认这个地址就行 */
    protected open fun isPlayable(url: String): Boolean {
        return try {
            val response = Jsoup.connect(url)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .header("Range", "bytes=0-1")
                .maxBodySize(MAX_PROBE_BYTES)
                .timeout(PROBE_TIMEOUT_MS)
                .execute()
            response.statusCode() in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 带音质后缀的那个变体；本来就带后缀就返回 null(没有别的变体可试)。
     *
     * **不要限制在 `/storages/` 路径。** 原本只对 storages 路径生成这个候选，
     * 实测 `group75/M09/8F/AC/wKgO3V6SuBrysbuSAEGnDInNYiU675.m4a` 这种老路径
     * 原地址 404、补上后缀 206 能播 —— 那道守卫等于一次都不试就放弃。
     * 反正 [pickPlayable] 会先试原地址，多一个候选只在原地址失败时多打一个探测请求。
     */
    private fun withQualitySuffix(url: String): String? {
        if (url.contains("-aacv2-")) return null
        val suffixed = url.replace(Regex("""\.(m4a|mp3)$"""), "-aacv2-48K.$1")
        return if (suffixed == url) null else suffixed
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

    protected companion object {
        /** 探测只需要头几个字节 */
        const val MAX_PROBE_BYTES = 2048
        const val PROBE_TIMEOUT_MS = 15000

        /** 这批 DedeCMS 站共用的分类编号 */
        val STANDARD_CATEGORIES = listOf(
            "1" to "玄幻",
            "2" to "言情",
            "3" to "都市",
            "7" to "武侠",
            "10" to "穿越",
            "11" to "科幻",
            "12" to "网游",
            "8" to "历史",
            "9" to "军事",
            "4" to "恐怖",
            "5" to "惊悚",
            "6" to "推理",
            "13" to "评书",
            "23" to "相声小品",
            "14" to "戏曲",
            "15" to "笑话",
            "24" to "百家讲坛",
            "16" to "儿童",
            "17" to "财经",
            "18" to "广播",
            "19" to "诗歌",
            "20" to "文学",
            "21" to "粤语",
            "22" to "经典"
        )
    }
}
