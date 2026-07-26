import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp.DedeCmsTingShu
import org.jsoup.Connection
import org.junit.Test

/**
 * 听书吧(DedeCMS 站群)测试。
 */
class TingShuBaTest {

    private val source = object : DedeCmsTingShu() {
        override val baseUrl = "https://www.ting8.cc"
        override val categoryPath = "books"
        override val bookPath = "mp3"
        override val categories = listOf("1" to "玄幻", "13" to "评书")
        override fun getSourceId() = "b7d41e5c9a084f36bc27de1058a93741"
        override fun getName() = "听书吧"
        override fun configure(connection: Connection): Connection = connection.testConfig(true)
    }

    private fun assertPlayable(audioUrl: String) {
        assertThat(audioUrl).isNotEmpty()
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-4096")
        // 不带 Referer —— 实测 fdfs.xmcdn.com 一带就回 403(见 DedeCmsTingShu 注释)
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/77.0.3865.120 Safari/537.36"
        )
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试: HTTP $code ${connection.contentType} ${bytes}B")
        // 必须断言 Content-Type —— 站点故障时会回 200 的 HTML 错误页，
        // 只看状态码和字节数的话那也算"能播"，等于测不出坏掉的地址
        val ct = connection.contentType
        val isAudio = ct != null && (ct.startsWith("audio") || ct.contains("mp4") ||
            ct.contains("octet-stream"))
        assertThat(isAudio).isTrue()
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(bytes > 0).isTrue()
    }

    @Test
    fun categoryList() {
        val url = source.getCategoryMenus().first().tabs.first().url
        val category = source.getCategoryList(url)
        category.list.take(3).forEach { println(it) }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(10)
        assertThat(category.totalPage).isGreaterThan(50)
        assertThat(category.nextUrl).isNotEmpty()
        assertThat(category.list.first().title).isNotEmpty()
        assertThat(category.list.first().coverUrl).isNotEmpty()
    }

    @Test
    fun categoryPaging() {
        val second = source.getCategoryList("https://www.ting8.cc/books/1-2.html")
        println("第2页 ${second.list.size} 本, currentPage=${second.currentPage}")
        assertThat(second.list.size).isGreaterThan(10)
        assertThat(second.currentPage).isEqualTo(2)
    }

    /**
     * 端到端:分类 -> 详情 -> 章节 -> 音频真的能下载。
     */
    @Test
    fun endToEndPlayback() {
        val url = source.getCategoryMenus().first().tabs.first().url
        val book = source.getCategoryList(url).list.first()
        println("book=${book.title} ${book.bookUrl}")
        val detail = source.getBookDetailInfo(book.bookUrl, loadEpisodes = true, loadFullPages = true)
        println("章节=${detail.playList.size} 作者=${detail.author} 播音=${detail.artist}")
        println("简介=${detail.intro?.take(50)}")
        assertThat(detail.playList.size).isGreaterThan(0)
        assertThat(detail.coverUrl).isNotEmpty()

        val episode = detail.playList.first()
        println("episode=${episode.title} ${episode.url}")
        val playPage = org.jsoup.Jsoup.connect(episode.url).testConfig(true)
            .referrer(book.bookUrl).get()
        val audioUrl = source.audioUrl(playPage.html())
        println("audioUrl=$audioUrl")
        assertPlayable(audioUrl)
    }

    /**
     * 音频正则:只取 now，不要误取 next(下一集)。
     */
    @Test
    fun audioUrlPicksCurrentEpisode() {
        val html = """
            var now="http://audio.xmcdn.com/a/current.m4a";var pn="mp3";
            var next="http://audio.xmcdn.com/a/next.m4a";
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo("http://audio.xmcdn.com/a/current.m4a")
        assertThat(source.audioUrl("var next=\"http://x/y.m4a\";")).isEqualTo("")
    }
}
