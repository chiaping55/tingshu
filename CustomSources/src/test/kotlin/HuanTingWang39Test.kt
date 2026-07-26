import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.junit.Test

/**
 * 幻听网(ting39.com)测试。
 *
 * 和起点有声网共用 PtcmsTingShu，这里验证同一份解析逻辑换 baseUrl 后依然成立，
 * 也就是"一份代码吃整个 PTCMS 站群"这个前提是真的。
 */
class HuanTingWang39Test {

    private val source = object : PtcmsTingShu() {
        override val baseUrl = "https://www.ting39.com"
        override fun getSourceId() = "999ed242fe644e37a7e9ac5c8a652b55"
        override fun getName() = "幻听网"
        override fun getCategoryMenus(): List<CategoryMenu> = listOf(
            menu(baseUrl, "小说", "xhqh" to "玄幻奇幻", "pingshu" to "其他评书")
        )

        override fun configure(connection: Connection): Connection = connection.testConfig(true)

        override fun notifyLoading(pageInfo: String?) {
            println("loading episodes: $pageInfo")
        }
    }

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
    fun search() {
        val (books, totalPage) = source.search("盗墓", 1)
        books.take(5).forEach { println(it) }
        println("totalPage=$totalPage")
        assertThat(books.size).isGreaterThan(0)
        assertThat(books.first().title).isNotEmpty()
        assertThat(books.first().coverUrl).isNotEmpty()
    }

    @Test
    fun categoryList() {
        val url = source.getCategoryMenus().first().tabs.first().url
        val category = source.getCategoryList(url)
        category.list.take(3).forEach { println(it) }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(0)
        assertThat(category.nextUrl).isNotEmpty()
    }

    /**
     * 端到端:搜索 -> 详情 -> 章节 -> 音频真的能下载。
     */
    @Test
    fun endToEndPlayback() {
        val book = source.search("盗墓", 1).first.first()
        println("book=${book.title} ${book.bookUrl}")
        val detail = source.getBookDetailInfo(book.bookUrl, loadEpisodes = true, loadFullPages = false)
        println("章节数=${detail.playList.size} cover=${detail.coverUrl}")
        assertThat(detail.playList.size).isGreaterThan(0)

        val episode = detail.playList.first()
        println("episode=${episode.title} ${episode.url}")
        val audioUrl = source.resolveAudioUrl(episode.url)
        println("audioUrl=$audioUrl")
        assertPlayable(audioUrl)
    }
}
