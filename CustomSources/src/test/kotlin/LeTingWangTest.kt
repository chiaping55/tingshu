import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.junit.Test

/**
 * 乐听网测试。第三个复用 PtcmsTingShu 的站点。
 */
class LeTingWangTest {

    private val source = object : PtcmsTingShu() {
        override val baseUrl = "https://www.leting.vip"
        override fun getSourceId() = "6f2b1c94e5a8471d9c3e0d76ab5f2418"
        override fun getName() = "乐听网"
        override fun getCategoryMenus(): List<CategoryMenu> = listOf(
            menu(baseUrl, "小说", "xhqh" to "玄幻奇幻")
        )

        override fun configure(connection: Connection): Connection = connection.testConfig(true)

        override fun notifyLoading(pageInfo: String?) {
            println("loading episodes: $pageInfo")
        }
    }

    @Test
    fun endToEndPlayback() {
        val (books, totalPage) = source.search("盗墓", 1)
        println("搜索到 ${books.size} 本, totalPage=$totalPage")
        books.take(3).forEach { println("  $it") }
        assertThat(books.size).isGreaterThan(0)

        val detail =
            source.getBookDetailInfo(books.first().bookUrl, loadEpisodes = true, loadFullPages = false)
        println("章节 ${detail.playList.size} 集, cover=${detail.coverUrl}")
        assertThat(detail.playList.size).isGreaterThan(0)

        val audioUrl = source.resolveAudioUrl(detail.playList.first().url)
        println("audioUrl=$audioUrl")
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
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(bytes > 0).isTrue()
    }

    @Test
    fun categoryList() {
        val category = source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
        category.list.take(3).forEach { println(it) }
        println("page ${category.currentPage}/${category.totalPage}")
        assertThat(category.list.size).isGreaterThan(0)
    }
}
