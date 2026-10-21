import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.PtcmsTingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import org.jsoup.Connection
import org.junit.Test
import java.net.URLEncoder

/**
 * 麒麟听书(70ts.com)的**离线**测试。
 *
 * 站点 2026-10-20 确认死亡(整站 404，只剩主机面板默认页)，源已从 SourceEntry 移除、
 * QiLinTingShu.kt 也删了。但它逼出来的几个基类开关(书页即章节第 1 页、签名 cdn 没签名
 * 就不交地址、setMedia 后缀优先于 murl)还在 PtcmsTingShu 里，这里留的纯字符串测试
 * 继续钉住这些行为。网络测试(分类、书页、真下载)已随站一起删除。
 */
class QiLinTingShuTest {

    companion object {
        private val source = object : PtcmsTingShu() {
            override val baseUrl = "https://www.70ts.com"

            override val titleSuffix = "有声小说"

            override val bookPageIsFirstEpisodePage = true

            override val audioFallbackSites = emptyList<Int>()

            /** 保留当年 QiLinTingShu 的全部开关，测的是基类对这组开关的行为 */
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

        /** 只用来验翻页地址的字符串拼接，不会真的去抓 */
        private const val SAMPLE_BOOK = "https://www.70ts.com/tingshu/12394/"
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
}
