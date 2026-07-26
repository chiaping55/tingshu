import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.junit.Test

/**
 * 起点有声网(PTCMS 模板)测试。
 *
 * 正式代码走 config() 从 app 读 UA，那是个 stub，在 JVM 上会抛异常，
 * 所以这里用同一份解析逻辑但把 configure 换成 testConfig，真实跑一遍网络请求。
 */
class QiDianYouShengTest {

    private val source = object : PtcmsTingShu() {
        override val baseUrl = "https://www.qdysw.com"
        override fun getSourceId() = "3c0449a8f40b47a4b6f8b53fa2c9c6a5"
        override fun getName() = "起点有声网"
        override fun getCategoryMenus(): List<CategoryMenu> = listOf(
            menu(baseUrl, "小说", "xhqh" to "玄幻奇幻")
        )

        override fun configure(connection: Connection): Connection = connection.testConfig(true)

        override fun notifyLoading(pageInfo: String?) {
            println("loading episodes: $pageInfo")
        }
    }

    @Test
    fun search() {
        val (books, totalPage) = source.search("盗墓", 1)
        books.take(5).forEach { println(it) }
        println("totalPage=$totalPage")
        assertThat(books.size).isGreaterThan(0)
        assertThat(books.first().title).isNotEmpty()
        assertThat(books.first().bookUrl).isNotEmpty()
        assertThat(books.first().coverUrl).isNotEmpty()
        assertThat(totalPage).isGreaterThan(1)
    }

    @Test
    fun categoryList() {
        val url = source.getCategoryMenus().first().tabs.first().url
        val category = source.getCategoryList(url)
        category.list.take(5).forEach { println(it) }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(0)
        assertThat(category.totalPage).isGreaterThan(1)
        assertThat(category.nextUrl).isNotEmpty()
    }

    /**
     * 只加载第一页章节。这是列表弹窗和第一次进播放页的行为。
     */
    @Test
    fun bookDetailFirstPage() {
        val bookUrl = source.search("盗墓", 1).first.first().bookUrl
        val detail = source.getBookDetailInfo(bookUrl, loadEpisodes = true, loadFullPages = false)
        println("intro=${detail.intro?.take(60)}")
        println("artist=${detail.artist} cover=${detail.coverUrl} 章节数=${detail.playList.size}")
        detail.playList.take(5).forEach { println(it) }
        assertThat(detail.playList.size).isGreaterThan(0)
        assertThat(detail.coverUrl).isNotEmpty()
        // 目录页按 sort=asc 取，第一集应该排在最前
        assertThat(detail.playList.first().url).isNotEmpty()
    }

    /**
     * 全量加载多页章节，验证翻页拼接。挑一本集数多的书才测得到。
     */
    @Test
    fun bookDetailAllPages() {
        val bookUrl = "https://www.qdysw.com/book/16242.html"
        val firstPageOnly =
            source.getBookDetailInfo(bookUrl, loadEpisodes = true, loadFullPages = false)
        val allPages = source.getBookDetailInfo(bookUrl, loadEpisodes = true, loadFullPages = true)
        println("单页=${firstPageOnly.playList.size} 全量=${allPages.playList.size}")
        assertThat(allPages.playList.size).isGreaterThan(firstPageOnly.playList.size)
    }

    /**
     * 音频提取。
     *
     * 注意这里不只断言"拿到了地址"——之前就是这样漏掉 bug 的：地址拿到了但缺扩展名，
     * cdn 回 403 根本播不了。所以必须真的去请求一段字节，确认服务器认这个地址。
     */
    private fun assertPlayable(audioUrl: String) {
        assertThat(audioUrl).isNotEmpty()
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/77.0.3865.120 Safari/537.36"
        )
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val type = connection.contentType
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试: HTTP $code $type ${bytes}B")
        // 必须断言 Content-Type —— 站点故障时会回 200 的 HTML 错误页，
        // 只看状态码和字节数的话那也算"能播"，等于测不出坏掉的地址
        val ct = type
        val isAudio = ct != null && (ct.startsWith("audio") || ct.contains("mp4") ||
            ct.contains("octet-stream"))
        assertThat(isAudio).isTrue()
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(bytes > 0).isTrue()
    }

    @Test
    fun audioUrlFirstEpisode() {
        val bookUrl = "https://www.qdysw.com/book/16242.html"
        val episode = source.getBookDetailInfo(bookUrl, loadEpisodes = true, loadFullPages = false)
            .playList.first()
        println("episode=${episode.url}")
        val audioUrl = source.resolveAudioUrl(episode.url)
        println("audioUrl=$audioUrl")
        assertPlayable(audioUrl)
    }

    /**
     * 这一集站点默认线路(site=16)返回空地址，而且换到的线路给的 url 不带扩展名，
     * 两个坑都在这一集里，用它当回归测试。
     */
    @Test
    fun audioUrlChapterWithEmptyDefaultLine() {
        val chapterUrl = "https://www.qdysw.com/tingshu/16242/53190.html"
        val audioUrl = source.resolveAudioUrl(chapterUrl)
        println("audioUrl=$audioUrl")
        assertPlayable(audioUrl)
    }

    /**
     * 扩展名拼接逻辑本身，不依赖网络。
     */
    @Test
    fun audioUrlAppendsSuffixWhenMissing() {
        val withoutExtension = source.audioUrl(
            "var url123; var murl123;\nmurl123 = '.mp3';\nurl123 = 'https://cdn.test/a/M500001';"
        )
        assertThat(withoutExtension).isEqualTo("https://cdn.test/a/M500001.mp3")

        val withExtension = source.audioUrl(
            "murl456 = '.mp3';\nurl456 = 'https://cdn.test/a/M500001.mp3';"
        )
        assertThat(withExtension).isEqualTo("https://cdn.test/a/M500001.mp3")

        // 站点没有这一集时 url 是空串，不能返回 murl 之类的垃圾
        val empty = source.audioUrl("murl789 = '.mp3';\nurl789 = '';")
        assertThat(empty).isEqualTo("")

        // preurl / nexturl 是网页地址，不能被当成音频
        val onlyPageLinks = source.audioUrl(
            "nexturl = 'https://www.qdysw.com/tingshu/1/2.html';\npreurl = '';"
        )
        assertThat(onlyPageLinks).isEqualTo("")
    }

    /**
     * guard 反爬 + base64 解码这段逻辑单独验一次：能拿到正常页面就说明握手成功。
     */
    @Test
    fun guardHandshake() {
        val doc = source.fetch("https://www.qdysw.com/book/16242.html")
        println("title=${doc.title()}")
        // 挑战页只有一小段 js、没有 book-info
        assertThat(doc.selectFirst("div.book-info") != null).isTrue()
    }
}
