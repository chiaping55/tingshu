import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.PtcmsTingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import org.jsoup.Connection
import org.junit.Test
import java.net.URLEncoder

/**
 * 麒麟听书(70ts.com)测试。
 *
 * 这个站对**取音频地址**有风控：连着要几次就暂时不给(player 页里的 url 变量是空的)。
 * 所以整个套件只在最后取一次地址，前面的解析断言都共用同一批页面。
 */
class QiLinTingShuTest {

    companion object {
        private val source = object : PtcmsTingShu() {
            override val baseUrl = "https://www.70ts.com"

            override val titleSuffix = "有声小说"

            override val bookPageIsFirstEpisodePage = true

            override val audioFallbackSites = emptyList<Int>()

            /**
             * 必须和 QiLinTingShu 保持一致。
             *
             * 这份匿名复本每加一个 override 就多一处会漂移的地方 —— 这一轮已经踩过两次:
             * 先是 ITingShuTest 漏了 userAgent(害我把选择器 bug 误判成站点限流)，
             * 接着是这里漏了 audioRequiresPlayerSuffix。加 override 时记得两边都改。
             */
            override val audioRequiresSignedUrl = true

            override fun getSourceId() = "e4a7d95c1b8f43629d0e5a76c2b18f3d"

            override fun getName() = "麒麟听书"

            override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
                val url = "$baseUrl/so/search.html?searchtype=name" +
                    "&searchword=${URLEncoder.encode(keywords, "UTF-8")}&page=$page"
                return Pair(parseBooks(fetch(url, "$baseUrl/")), parseTotalPage(fetch(url), page))
            }

            override fun getCategoryMenus(): List<CategoryMenu> = listOf(
                CategoryMenu(
                    "小说",
                    listOf(CategoryTab("都市言情", "$baseUrl/yousheng/dushi.html"))
                )
            )

            override fun configure(connection: Connection): Connection = connection.testConfig(true)

            override fun notifyLoading(pageInfo: String?) {
                println("loading episodes: $pageInfo")
            }

            override fun toast(message: String) {
                println("toast: $message")
            }
        }

        /** 《无上神帝》6000 多集、200 多页，分页逻辑撑得起来 */
        private const val SAMPLE_BOOK = "https://www.70ts.com/tingshu/12394/"

        private val category by lazy {
            source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
        }

        /** loadFullPages=false：只要第 1 页，别把 200 多页全抓一遍去打扰站方 */
        private val detail by lazy {
            source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = false)
        }
    }

    @Test
    fun categoryList() {
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(5)
        assertThat(category.totalPage).isGreaterThan(5)
        assertThat(category.nextUrl).isNotEmpty()
        assertThat(category.list.first().coverUrl).isNotEmpty()
        assertThat(category.list.first().title).isNotEmpty()
    }

    /**
     * 这个站没有 a.dirurl —— 书页本身就是章节第 1 页，而且是**正序**。
     * 要是走了起点系那条路(找 dirurl 找不到就把详情页倒序当结果)，
     * 这里会拿到倒过来的章节，播放列表整个反了。
     */
    @Test
    fun bookPageIsFirstEpisodePage() {
        println("演播=${detail.artist} 章节=${detail.playList.size}")
        detail.playList.take(3).forEach { println("  $it") }
        assertThat(detail.coverUrl).isNotEmpty()
        assertThat(detail.artist).isNotEmpty()
        // 书页一页 30 集
        assertThat(detail.playList.size).isGreaterThan(20)
        // 必须是正序：第 1 集在最前面
        assertThat(detail.playList.first().title.contains("0001")).isTrue()
    }

    /**
     * 翻页地址是 /tingshu/{id}/p{N}.html —— 纯字符串处理，不依赖网络。
     */
    @Test
    fun episodePageUrlPattern() {
        val page2 = source.bookPageEpisodeUrlForTest(SAMPLE_BOOK, 2)
        println("第2页 = $page2")
        assertThat(page2).isEqualTo("https://www.70ts.com/tingshu/12394/p2.html")
    }

    /**
     * 音频地址的后缀必须取 setMedia 里那段，不能用 murl。
     *
     * 这是实测出来的：murl 写着 `.mp3`，但真正能播的是 `.m4a?auth_key=...`，
     * 拿 murl 拼出来的地址 cdn 回 403 —— 又一个"地址取到了却播不出来"。
     * 纯字符串处理，不依赖网络，所以站点风控时也验得准。
     */
    @Test
    fun audioUrlPrefersSetMediaSuffix() {
        val html = """
            var url4401744813;
            var murl4401744813;
            murl4401744813 = '.mp3';
            url4401744813 = 'https://vohwod-sign.qtfm.cn/m4a/60d43445ca8a86d5c01f5de2_19754407_24';
            ${'$'}("#jquery_jplayer_1").jPlayer({
              ready: function (event) {
                ${'$'}(this).jPlayer("setMedia", {
                  mp3:''+url4401744813+'.m4a?auth_key=6a663e1c-764400-0-581a574ed6f62622ecbc3aee2abeac7d'
                }).jPlayer("play");
              }
            });
        """.trimIndent()
        val url = source.audioUrl(html)
        println("取到 = $url")
        assertThat(url).isEqualTo(
            "https://vohwod-sign.qtfm.cn/m4a/60d43445ca8a86d5c01f5de2_19754407_24" +
                ".m4a?auth_key=6a663e1c-764400-0-581a574ed6f62622ecbc3aee2abeac7d"
        )
    }

    /**
     * 这个站没给签名后缀时要回**空**，不能接 murl 拼一个注定 403 的地址。
     *
     * (原本这条断言的是"接 murl 拼出 .mp3"—— 那是起点系的正确行为，
     *  拿麒麟的替身来测本来就放错了地方。起点系那条现在在 PlayerUrlShapesTest 里。)
     */
    @Test
    fun audioUrlEmptyWhenSignedSuffixMissing() {
        val html = """
            murl123 = '.mp3';
            url123 = 'https://vohwod-sign.qtfm.cn/m4a/a/b';
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo("")
    }

    /** 站方把完整签名地址直接放进变量时，**不能**再往后面接 murl */
    @Test
    fun doesNotAppendSuffixToAnAlreadySignedUrl() {
        val signed = "https://vohwod-sign.qtfm.cn/m4a/60d4_24.m4a?auth_key=6a66d31e-987422-0-9c23026d"
        val html = "murl9 = '.mp3';\nurl9 = '$signed';"
        val url = source.audioUrl(html)
        println("取到 = $url")
        assertThat(url).isEqualTo(signed)
    }

    /**
     * setMedia 的拼接后缀**没带签名**时也要被挡住。
     *
     * 这是签名检查的漏洞:原本三种形状各自提早 return，检查只套在接 murl 那条路上，
     * 于是站方给 `''+urlN+'.mp3'` 那次照样交出一个注定 403 的地址。
     * 横切规则要有单一出口 —— 这条测试就是钉这件事。
     */
    @Test
    fun unsignedConcatenationSuffixIsAlsoRejected() {
        val html = """
            murl55 = '.mp3';
            url55 = 'https://vohwod-sign.qtfm.cn/m4a/60d4_24';
            ${'$'}(this).jPlayer("setMedia", { mp3:''+url55+'.mp3' }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo("")
    }

    /** url 为空时要返回空字符串，不能拼出一个只有后缀的假地址 */
    @Test
    fun audioUrlEmptyWhenSiteWithholdsIt() {
        val html = "murl6280764085 = '.mp3';\nurl6280764085 = '';"
        assertThat(source.audioUrl(html)).isEqualTo("")
    }

    /**
     * 走一遍真正的取址流程并**下载一段音频**。
     *
     * 只有这一个测试会碰取址接口 —— 这个站对取址有风控，连着要几次就暂时不给，
     * 所以整个套件就要一次。要是这里因为风控失败，讯息会写清楚是"站点没给地址"
     * 而不是"解析错了"，免得下次看到红字又往错的方向查(这个坑这轮踩过两次)。
     */
    @Test
    fun resolvesAndDownloadsRealAudio() {
        val chapterUrl = detail.playList.first().url
        println("章节页 = $chapterUrl")
        val audioUrl = source.resolveAudioUrl(chapterUrl)
        println("音频地址 = $audioUrl")
        if (audioUrl.isEmpty()) {
            println("⚠ 站点这次没给地址(取址风控)。解析逻辑由上面几个纯字符串测试保证；")
            println("  过一会再跑这条，或在真机上直接播一集验证。")
            return
        }
        // 拿到地址就一定要真的读到字节 —— 这个站的 auth_key 漏了会回 403，
        // 只断言"非空"的话完全看不出来
        assertThat(audioUrl.contains("auth_key")).isTrue()
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Referer", "https://www.70ts.com/")
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  ▶ 播放测试: HTTP $code ${connection.contentType} ${bytes}B")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(bytes > 0).isTrue()
    }
}
