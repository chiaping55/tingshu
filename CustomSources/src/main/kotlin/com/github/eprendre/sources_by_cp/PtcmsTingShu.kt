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
import java.io.IOException
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

    /** 反爬挑战处理，cookie 存在里面复用，避免每个请求都要握手两次 */
    private val guard = PtcmsGuard()

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

    /** 同上，app 里弹提示；测试里覆写成打印即可 */
    protected open fun toast(message: String) = showToast(message)

    private fun connect(url: String, referer: String?, ua: String?): Connection {
        val connection = configure(Jsoup.connect(url))
        (ua ?: userAgent)?.let { connection.userAgent(it) }
        if (referer != null) {
            connection.referrer(referer)
        }
        return connection
    }

    internal fun fetch(url: String, referer: String? = null, ua: String? = null): Document =
        checkNotThrottled(guard.request { connect(url, referer, ua) }.parse())

    internal fun fetchHtml(url: String, referer: String? = null): String =
        guard.request { connect(url, referer, null) }.body()

    /** 有些站的搜索是 POST 表单 */
    internal fun fetchPost(url: String, data: Map<String, String>, referer: String? = null): Document =
        checkNotThrottled(
            guard.request {
                connect(url, referer, null).method(Connection.Method.POST).data(data)
            }.parse()
        )

    /**
     * 限流有时回的是 **HTTP 200** 的「访问过于频繁」提示页，不是 429。
     *
     * 这个检查以前只做在翻章节那个循环里，所以书籍页、搜索、目录第一页拿到提示页时
     * 会被当成正常内容解析 —— 结果是「这本书没有章节」「搜索没结果」，
     * 使用者完全看不出是限流。现在挪到 [fetch] 里，所有请求都过一遍。
     *
     * 判断加了长度上限：提示页只有几百字，而真实的书籍页有几千字。
     * 只比对关键词的话，简介里恰好提到「稍后再试」的书会被误判成限流。
     */
    private fun checkNotThrottled(doc: Document): Document {
        val text = doc.body()?.text() ?: return doc
        if (text.length <= INTERSTITIAL_MAX_CHARS && THROTTLE_HINTS.any { text.contains(it) }) {
            throw IOException("站点提示访问过于频繁，请过一会再试")
        }
        return doc
    }

    /** 把目录页地址换到 [episodeDirectoryBaseUrl]（没设就原样返回） */
    internal fun directoryPageUrl(dirUrl: String, page: Int): String =
        directoryPageUrl(dirUrl, page, episodeDirectoryBaseUrl)

    internal fun directoryPageUrl(dirUrl: String, page: Int, host: String?): String {
        val target = if (host == null) dirUrl else host + dirUrl.removePrefix(baseUrl)
        return "$target?page=$page&sort=asc"
    }

    /**
     * 取目录页：先走 [episodeDirectoryBaseUrl]（手机站），被限流就退回桌面站再试一次。
     *
     * 两个站的限流额度是独立的，所以这个退路是真的有用；两边都不行才抛出去，
     * 让 app 显示"点击重试"—— 那是诚实的表达，比返回一份不完整的章节列表好。
     * (曾经在这里退回详情页自带的"最新几集"，结果 app 把那 10 集当成整本书的播放列表：
     *  显示 1/10、列表停在最后一页、播放位置全乱。宁可报错也不要给出误导的列表。)
     */
    private fun fetchDirectoryPage(dirUrl: String, page: Int): Document {
        try {
            return fetch(directoryPageUrl(dirUrl, page), null, episodeDirectoryUserAgent)
        } catch (e: Exception) {
            if (episodeDirectoryBaseUrl == null) throw e
            return fetch(directoryPageUrl(dirUrl, page, null), null, userAgent)
        }
    }

    /** 列表里书名带的固定后缀，子类需要时覆写，例如"有声小说" */
    protected open val titleSuffix: String = ""

    /**
     * 覆盖 UA。
     *
     * 默认走 app 设置里的 UA，但有的站会拿 UA 当风控依据 —— 爱听书对 2019 年的
     * Chrome 77 在章节目录页直接回 429（书籍页却放行，很容易误判成限流）。
     * 这种站就在子类里指定一个较新的 UA，别依赖用户的设置。
     */
    protected open val userAgent: String? = null

    /**
     * 章节目录页改走这个域名，留 null 就用 [baseUrl]。
     *
     * 有的站桌面站限流很紧：爱听书那种一千多集的书目录有 27 页，连着翻就把额度用光，
     * 接着连别的书的列表都读不出来。实测手机站是**独立额度**，所以把翻目录这种
     * 请求量大的操作挪过去，桌面站的额度留给浏览用。
     */
    protected open val episodeDirectoryBaseUrl: String? = null

    /** 上面那个域名要配的 UA */
    protected open val episodeDirectoryUserAgent: String? = null

    /**
     * 翻章节目录时每页之间的等待区间。有的站限流比较严(爱听书连翻会回 429)，
     * 这种站要放慢，否则退避重试会让整体更慢。
     */
    protected open val episodePageDelay: LongRange = 100L..500L

    /**
     * 这个站没有单独的章节目录页(没有 a.dirurl)，书页本身就是章节第 1 页。
     *
     * 麒麟听书是这一型：书页列前 30 集(正序)，后面的页在 /tingshu/{id}/p{N}.html，
     * 总页数看"快速选集"那个列表。起点系不是这样，所以默认 false。
     */
    protected open val bookPageIsFirstEpisodePage: Boolean = false

    /** 配合上面那个开关，给出书页第 [page] 页的地址 */
    protected open fun bookPageEpisodeUrl(bookUrl: String, page: Int): String =
        bookUrl.trimEnd('/') + "/p$page.html"

    /** 给测试用：bookPageEpisodeUrl 是 protected，测试从外面调不到 */
    internal fun bookPageEpisodeUrlForTest(bookUrl: String, page: Int) =
        bookPageEpisodeUrl(bookUrl, page)

    /**
     * 默认线路拿不到地址时依次尝试的线路号。
     *
     * 留空表示这个站不支持换线路 —— 麒麟听书的播放器地址带一次性 token，
     * 重打同一个地址只会拿到 "Access Denied"，白费请求还可能触发风控；
     * 那种站改走"重取章节页换新 token"的重试。
     */
    protected open val audioFallbackSites: List<Int> = FALLBACK_SITES

    /**
     * 拿不到地址时重取章节页换新 token 的次数。
     *
     * 默认 0 —— 站点不给地址通常是取址风控，短间隔重试帮不上忙还会加重限流。
     * 只有确认是偶发空值的站才调大。
     */
    protected open val freshTokenRetries: Int = 0

    /**
     * 取不到音频时给用户的提示。要说清楚**该怎么办**，
     * 而不是笼统地"失败了"—— 限流和这一集真的没音频，处理方式完全不同。
     */
    protected open val noAudioMessage: String = "这一集所有线路都没有音频，换一集试试"

    /**
     * 这个站的音频在**签名 cdn** 上，地址没有查询参数(签名)就一定播不出来。
     *
     * 麒麟听书的音频主机是 `vohwod-sign.qtfm.cn` —— 名字里的 sign 就是签名。
     * 少了 `?auth_key=...` 一律 403，而站方给的 player 页有好几种形状：
     * 有时签名在 setMedia 的拼接里、有时整个完整地址就在变量里、
     * 有时**只给一个没签名的地址**(取址风控时)。
     *
     * 最后那种情况下没有任何可用地址。交回去只会让 app 请求失败、使用者看到播放错误，
     * 而真正的原因(站点这次没给签名)完全表达不出来 —— 宁可回空，让上层照实提示
     * 「等一两分钟再点这一集，不是源坏了」。
     *
     * 判断就看最终地址有没有查询参数，不去猜 player 页是哪种形状 ——
     * 之前用"我们有没有拼过它"当判据，结果站方直接给没签名的 `.mp3` 那次就漏掉了。
     *
     * 起点系那批站的 cdn 不校验签名，接 murl 就能播，所以默认 false。
     */
    protected open val audioRequiresSignedUrl: Boolean = false

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        // 站方数据库是简体的，繁体输入会字面对不上、命中率归零 —— 先转简体再搜
        val url = "$baseUrl/search.html?searchtype=name" +
            "&searchword=${URLEncoder.encode(ChineseConverter.toSimplified(keywords), "UTF-8")}&page=$page"
        val doc = fetch(url, "$baseUrl/")
        return Pair(parseBooks(doc), parseTotalPage(doc, page))
    }

    override fun getCategoryList(url: String): Category {
        val doc = fetch(url)
        val pager = doc.selectFirst("div.fanye")
        val currentPage = pager?.selectFirst("strong")?.text()?.trim()?.toIntOrNull() ?: 1
        // 有"下一页/下页"就用它；没有的话找页码等于当前页+1 的那个链接
        val nextUrl = pager?.select("a")
            ?.firstOrNull { link -> NEXT_PAGE_LABELS.any { link.text().contains(it) } }
            ?.absUrl("href")
            ?: pager?.select("a")
                ?.firstOrNull { it.text().trim().toIntOrNull() == currentPage + 1 }
                ?.absUrl("href")
            ?: ""
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
        // 起点系详情页不给作者，爱听书给，所以取不到就留空
        val author = doc.select("div.book-info dd")
            .firstOrNull { it.text().contains("作者") }
            ?.selectFirst("a")?.text() ?: ""

        val episodes = ArrayList<Episode>()
        if (loadEpisodes) {
            // 详情页只列最新几集且是倒序，完整章节在 a.dirurl 指向的目录页。
            val dirUrl = doc.selectFirst("a.dirurl")?.absUrl("href")
            if (bookPageIsFirstEpisodePage) {
                // 这一类站没有单独的目录页：书页本身就是章节第 1 页(而且是正序)，
                // 后面几页在 bookPageEpisodeUrl 给的地址上。
                episodes.addAll(parseEpisodes(doc))
                val totalPage = parseEpisodeTotalPage(doc)
                if (loadFullPages && totalPage > 1) {
                    loadRemainingPages(2..totalPage, totalPage, episodes) { page ->
                        fetch(bookPageEpisodeUrl(bookUrl, page), bookUrl)
                    }
                }
            } else if (dirUrl == null) {
                // 站点没给目录页(少数书)，只能用详情页那一小段
                episodes.addAll(parseEpisodes(doc).asReversed())
            } else {
                // 一律从目录页第一页开始，章节顺序才是从第 1 集正着排。
                // 别拿详情页的"最新几集"当播放列表 —— app 会把它当成整本书，
                // 于是一本 2640 集的书显示成 1/10、列表停在最后一页、播放位置全乱。
                val firstPage = fetchDirectoryPage(dirUrl, 1)
                episodes.addAll(parseEpisodes(firstPage))
                val totalPage = parseEpisodeTotalPage(firstPage)
                if (loadFullPages && totalPage > 1) {
                    loadRemainingPages(2..totalPage, totalPage, episodes) { page ->
                        fetchDirectoryPage(dirUrl, page)
                    }
                }
            }
        }
        return BookDetail(episodes, intro, artist, author, episodes.size, coverUrl)
    }

    /**
     * 把第 2 页到最后一页的章节接着抓下来。
     *
     * 限流或临时故障时**保留已经抓到的部分**而不是整本失败 —— 用户至少还能听前面几百集；
     * 但要提示一声，否则会以为这本书就只有这么多集。
     */
    private fun loadRemainingPages(
        pages: IntRange,
        totalPage: Int,
        into: ArrayList<Episode>,
        fetchPage: (Int) -> Document
    ) {
        pageList.clear()
        pageList.addAll(pages)
        try {
            while (pageList.isNotEmpty()) {
                val page = pageList.removeAt(0)
                notifyLoading("$page / $totalPage")
                val doc = fetchPage(page)
                val pageEpisodes = parseEpisodes(doc)
                // 站点被打太急时会回一个 200 的"访问过于频繁"页，解析不到章节。
                // 这种要当限流处理并停下，否则会把后面的页数都白跑一遍。
                if (pageEpisodes.isEmpty() && isThrottlePage(doc)) {
                    throw IOException("站点提示访问过于频繁")
                }
                into.addAll(pageEpisodes)
                Thread.sleep(Random.nextLong(episodePageDelay.first, episodePageDelay.last))
            }
        } catch (e: Exception) {
            pageList.clear()
            toast("章节只加载了一部分(${into.size}集)，站点限流了，稍后重新进入可续加载")
        }
        notifyLoading(null)
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
            toast("找不到播放器，站点可能改版了")
            return ""
        }
        audioUrl(fetchHtml(playerUrl, chapterUrl)).let { if (it.isNotEmpty()) return it }
        for (site in audioFallbackSites) {
            val url = audioUrl(fetchHtml(withSite(playerUrl, site), chapterUrl))
            if (url.isNotEmpty()) return url
        }
        // 有的站换线路这条路走不通(播放器地址里的 token 一次性，重打只会被拒)，
        // 那就重新取章节页换张新票再试。
        //
        // 注意**别在这里做短间隔重试**：麒麟听书那种站的取址冷却是分钟级的
        // (实测隔 25 秒都还不给)，一两秒后再要只是在被限流时多打两次请求，
        // 反而可能把冷却拖更久。所以默认不重试，只有确实是"偶发空值"的站才打开。
        repeat(freshTokenRetries) {
            Thread.sleep(Random.nextLong(600L, 1200L))
            val retryUrl = fetch(chapterUrl).selectFirst("iframe#play")?.absUrl("src")
            if (!retryUrl.isNullOrEmpty()) {
                val url = audioUrl(fetchHtml(retryUrl, chapterUrl))
                if (url.isNotEmpty()) return url
            }
        }
        toast(noAudioMessage)
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
     *
     * 后缀有两个来源，**优先用 setMedia 里那个**：
     *
     *     url4401744813 = 'https://vohwod-sign.qtfm.cn/m4a/60d4...';
     *     murl4401744813 = '.mp3';
     *     ...setMedia({ mp3: ''+url4401744813+'.m4a?auth_key=6a663e1c-...' })
     *
     * 麒麟听书就是这样：真正要用的是 `.m4a?auth_key=...`(带签名)，而 murl 写的是 `.mp3`。
     * 拿 murl 拼出来的地址 cdn 一律回 403 —— 表象是"地址取到了却播不出来"。
     */
    internal fun audioUrl(playerHtml: String): String {
        val match = Regex("""\b(url\d*)\s*=\s*'(https?://[^']*)'""").find(playerHtml)
        if (match != null) {
            val name = match.groupValues[1]
            val url = match.groupValues[2]
            // 播放器初始化时把变量和一段字符串拼起来，那段就是真后缀(可能带签名参数)：
            //   mp3:''+url4401744813+'.m4a?auth_key=...'
            // 不去匹配 setMedia 的调用形状(实际是 jPlayer("setMedia", {...})，写法各站不同)，
            // 只认"变量后面紧跟着 + '字符串'"—— 那个组合只会出现在这里。
            //
            // 先找和 url 变量**同名**的那个拼接(最精确)；找不到再放宽成"任何 url 变量的拼接"。
            // 放宽这一步是必要的：麒麟听书有时会在同一页里放两个 url 变量，
            // setMedia 引用的不是带地址那个 —— 只认同名的话就取不到签名参数，
            // 退回接 murl 拼出 `....mp3`，而那个地址 cdn 一律回 403(实测)。
            val sameName = Regex("""\b$name\s*\+\s*'([^']*)'""")
                .find(playerHtml)?.groupValues?.get(1)
            val anyName = Regex("""\burl\d+\s*\+\s*'([^']+)'""")
                .find(playerHtml)?.groupValues?.get(1)
            // **单一出口**:签名检查是横切规则，得套在每一种形状上。
            // 原本三条路各自 return，结果 setMedia 拼接那两条绕过了检查 ——
            // 站方给 `''+urlN+'.mp3'`(没签名)那次就漏掉了，交出去照样 403。
            val candidate = when {
                !sameName.isNullOrEmpty() -> url + sameName
                !anyName.isNullOrEmpty() -> url + anyName
                else -> withMurlSuffix(url, playerHtml)
            }
            // 签名 cdn 上的音频，没有签名参数就是个注定 403 的地址 —— 见 audioRequiresSignedUrl
            if (audioRequiresSignedUrl && !candidate.contains('?')) return ""
            return candidate
        }
        // url 变量是空的 —— 起点有声网的**默认线路**就是这样：地址直接写死在
        // setMedia 的字面量里(`mp3:'https://car-lv.kuwo.cn/....mp3'`)，url 变量留空。
        // 少了这一条，每一集都要多打 1~3 个请求去换线路才拿到地址，而换到的字符串
        // 和这里的字面量一字不差 —— site 参数只换 player 模板、不换媒体，那些请求纯属浪费。
        // 幻听网同一台服务器却是填在 url 变量里的，所以两条路都要留。
        //
        // 必须排在 url 变量那条路**后面**：麒麟听书写的是 `mp3:''+url<N>+'...'`，
        // `mp3:` 后面不是 `'http`，不会被这条误抢(有测试钉住)。
        val literal = Regex("""\bmp3\s*:\s*'(https?://[^']*)'""")
            .find(playerHtml)?.groupValues?.get(1)
        if (!literal.isNullOrEmpty()) return withMurlSuffix(literal, playerHtml)
        return ""
    }

    /**
     * 地址不带扩展名时接上 murl 里那个后缀，否则 cdn 回 403。
     *
     * **判断扩展名要看路径、不能看整串结尾。** 站方有时把完整的签名地址直接放进变量：
     *   `https://vohwod-sign.qtfm.cn/m4a/xxx_24.m4a?auth_key=...d3a4`
     * 整串是以 auth_key 的十六进制结尾的，`endsWith(".m4a")` 于是判成"没有扩展名"，
     * 又接一次 `.mp3` 上去 —— 把一个本来能播的地址改成 403。
     * 这个 bug 在带查询参数的地址出现之前一直藏着，是测试真的下载音频才逼出来的。
     */
    private fun withMurlSuffix(url: String, playerHtml: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        if (AUDIO_SUFFIXES.any { path.endsWith(it, ignoreCase = true) }) return url
        // 已经带查询参数的地址通常是签好名的完整地址，别再往后面接东西
        if (url.contains('?')) return url
        val suffix = Regex("""\bmurl\d*\s*=\s*'([^']*)'""")
            .find(playerHtml)?.groupValues?.get(1) ?: ""
        return url + suffix
    }

    internal fun parseBooks(doc: Document): List<Book> {
        return doc.select("ul.list-works > li").mapNotNull { item ->
            val titleLink = item.selectFirst("dt.list-book-dt a") ?: return@mapNotNull null
            val status = item.selectFirst("dt.list-book-dt span")?.text() ?: ""
            // 列表页的两个字段各站摆法不同，别对调了 ——
            // 爱听书有独立的 span.book-boyin 放演播(多人剧会有十几个 a)，
            // 而 span.book-author 放的是**作者**(站方还把它 display:none 藏起来)。
            // 起点系只有 span.book-author，里面确实是演播。
            // 原本一律把 book-author 当演播，于是爱听书的列表把作者显示成演播者 ——
            // 而演播者正是判断"是不是收藏里那一版"的唯一依据。
            val boyin = item.select("dd.list-book-cs span.book-boyin a")
            val authorSpan = item.selectFirst("dd.list-book-cs span.book-author a")?.text() ?: ""
            val artist = if (boyin.isNotEmpty()) boyin.joinToString(" ") { it.text() } else authorSpan
            val author = if (boyin.isNotEmpty()) authorSpan else ""
            Book(
                imageUrl(item.selectFirst("div.list-imgbox img")),
                titleLink.absUrl("href"),
                titleLink.text().removeSuffix(titleSuffix),
                author,
                artist
            ).apply {
                intro = item.selectFirst("dd.list-book-des")?.text() ?: ""
                this.status = status
                isCompleted = status.contains("完结")
                sourceId = getSourceId()
            }
        }
    }

    /** 站点限流时给的是 200 的提示页，不是 429 */
    internal fun isThrottlePage(doc: Document): Boolean {
        val text = doc.text()
        return THROTTLE_HINTS.any { text.contains(it) }
    }

    internal fun parseEpisodes(doc: Document): List<Episode> {
        // 桌面站的容器是 div#playlist ul，手机站(m.xxx)是 ol.novel-text-list —— 目录翻页
        // 走手机站时必须认得后者，不然会解析出 0 集、把限流降级那条路整个废掉。
        var links = doc.select("div#playlist ul li a")
        if (links.isEmpty()) {
            links = doc.select("ol.novel-text-list li a")
        }
        // 手机站的章节名带 ".mp3" 尾巴，桌面站没有；去掉才不会同一本书两种名字
        return links.map { Episode(it.text().removeSuffix(".mp3"), it.absUrl("href")) }
    }

    /**
     * 章节目录页的"快速选集"每一项对应一页(1~50、51~100…)，数量就是总页数。
     * 桌面站是 ul.js_chapter_ul，手机站是弹窗 div.pt-dir-sel。
     */
    internal fun parseEpisodeTotalPage(doc: Document): Int {
        var chunks = doc.select("ul.js_chapter_ul > li").size
        if (chunks == 0) {
            chunks = doc.select("div.pt-dir-sel ul > li").size
        }
        return if (chunks > 0) chunks else 1
    }

    /**
     * 分页栏只显示当前附近的页码，没有"尾页"链接，所以拿不到真实总页数时
     * 按基类约定用"当前页+1"表示还有下一页。
     */
    internal fun parseTotalPage(doc: Document, currentPage: Int): Int {
        val pager = doc.selectFirst("div.fanye") ?: return currentPage
        // "尾页"链接里的页码最可靠 —— 那是站方自己算出来要跳过去的地方。
        // 曾经优先信分页栏写的"共 N 页"，结果爱听书那个数字是错的：
        // 玄幻分类写"共 607 页"而尾页指向第 304 页，app 于是显示 607 页、
        // 第 305 页之后全是空白。站方的文案会写错，跳转链接不会。
        lastPageNumber(pager)?.let { return maxOf(it, currentPage) }
        // 没有尾页链接才退回文案
        Regex("""共\s*(\d+)\s*页""").find(pager.text())?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return maxOf(it, currentPage) }
        val maxNumbered = pager.select("a, strong")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .maxOrNull() ?: currentPage
        return if (hasNextLink(pager) && maxNumbered <= currentPage) currentPage + 1
        else maxOf(maxNumbered, currentPage)
    }

    /** "尾页"链接指向的页码 */
    private fun lastPageNumber(pager: Element): Int? {
        val href = pager.select("a")
            .firstOrNull { LAST_PAGE_LABELS.any { label -> it.text().contains(label) } }
            ?.attr("href") ?: return null
        // 页码是路径里最后一段数字，例如 /yousheng/xuanhuan/lastupdate/1/304.html
        return Regex("""(\d+)\.html""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d+)/?$""").find(href)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 有没有"下一页"。
     *
     * 各站文案不同 —— 爱听书写的是"下页"(两个字)，所以原本只认"下一页"那条规则
     * 在这个站永远空转，靠后面"页码等于当前页+1"的兜底才凑出来。
     */
    private fun hasNextLink(pager: Element) =
        pager.select("a").any { link -> NEXT_PAGE_LABELS.any { link.text().contains(it) } }

    /** 列表图片是懒加载的，真地址在 data-original，src 只是占位图 */
    private fun imageUrl(img: Element?): String {
        if (img == null) return ""
        val lazy = img.absUrl("data-original")
        return if (lazy.isNotEmpty()) lazy else img.absUrl("src")
    }


    protected companion object {
        private val AUDIO_SUFFIXES = listOf(".mp3", ".m4a", ".aac", ".m4s", ".wav")
        private val THROTTLE_HINTS = listOf("过于频繁", "訪問過於頻繁", "稍后再试", "稍後再試")

        /** 提示页只有几百字，真实书籍页有几千字 —— 用长度把两者分开，避免误判 */
        private const val INTERSTITIAL_MAX_CHARS = 600

        /** 各站的"下一页"文案不一样:起点系写"下一页"，爱听书写"下页" */
        private val NEXT_PAGE_LABELS = listOf("下一页", "下页")

        private val LAST_PAGE_LABELS = listOf("尾页", "末页", "最后一页")

        /**
         * 默认线路拿不到地址时依次尝试的线路号。
         * 实测同一集里 11、14 给的地址自带扩展名，其它几条要接 murl，都能播。
         */
        val FALLBACK_SITES = listOf(11, 14, 2, 13, 15, 17, 20, 8)

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
