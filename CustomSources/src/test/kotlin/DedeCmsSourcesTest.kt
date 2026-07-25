import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp.DedeCmsTingShu
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.junit.Test

/**
 * DedeCMS 站群的源一起验证，确认"一份解析逻辑吃多个站"这个前提成立。
 *
 * 这批站只有路径段不一样，所以用一个测试用子类换参数跑就行。
 */
class DedeCmsSourcesTest {

    private class Site(
        override val baseUrl: String,
        override val categoryPath: String,
        override val bookPath: String,
        private val label: String
    ) : DedeCmsTingShu() {
        override fun getSourceId() = label
        override fun getName() = label
        override fun configure(connection: Connection): Connection = connection.testConfig(true)
    }

    private val sites = listOf(
        Site("https://www.ting8.cc", "books", "mp3", "听书吧"),
        Site("https://www.leting8.com", "le", "so", "乐听吧")
    )

    private fun assertPlayable(source: Site, audioUrl: String) {
        assertThat(audioUrl).isNotEmpty()
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/77.0.3865.120 Safari/537.36"
        )
        // app 播放时会带上 headers() 给的 Referer，测试也要带，新 cdn 不带会 403
        source.headers(audioUrl).forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("    播放测试: HTTP $code ${connection.contentType} ${bytes}B")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(bytes > 0).isTrue()
    }

    /**
     * 每个站都跑一遍 分类 -> 详情 -> 章节 -> 音频真的能下载。
     */
    @Test
    fun everySiteResolvesPlayableAudio() {
        sites.forEach { source ->
            println("=== ${source.getName()} ${source.baseUrl}")

            val category = source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
            println("  分类: ${category.list.size} 本, 共 ${category.totalPage} 页")
            assertThat(category.list.size).isGreaterThan(5)
            assertThat(category.totalPage).isGreaterThan(10)
            assertThat(category.nextUrl).isNotEmpty()

            val book = category.list.first()
            println("  书籍: ${book.title} / 封面=${book.coverUrl.isNotEmpty()}")
            assertThat(book.title).isNotEmpty()
            assertThat(book.coverUrl).isNotEmpty()

            val detail = source.getBookDetailInfo(book.bookUrl, loadEpisodes = true, loadFullPages = true)
            println("  章节: ${detail.playList.size} 集, 作者=${detail.author}, 播音=${detail.artist}")
            assertThat(detail.playList.size).isGreaterThan(0)
            assertThat(detail.coverUrl).isNotEmpty()

            val episode = detail.playList.first()
            val playPage = Jsoup.connect(episode.url).testConfig(true).referrer(book.bookUrl).get()
            val audioUrl = source.audioUrl(playPage.html())
            println("  音频: $audioUrl")
            assertPlayable(source, audioUrl)
        }
    }

    /**
     * 音质后缀改写规则：只补 storages 路径且没有后缀的，其它一律不动。
     */
    @Test
    fun addsQualitySuffixOnlyWhenNeeded() {
        val prefix = "var now="
        fun extract(url: String) = sites.first().audioUrl("$prefix\"$url\";")

        // storages 且无后缀 -> 补上，否则站点回 404
        assertThat(extract("https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA.m4a"))
            .isEqualTo("https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA-aacv2-48K.m4a")
        // 已经带后缀 -> 不动
        assertThat(extract("https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA-aacv2-48K.m4a"))
            .isEqualTo("https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA-aacv2-48K.m4a")
        // group 路径 -> 不动
        assertThat(extract("http://aod.cos.tx.xmcdn.com/group75/M09/99/AAA.m4a"))
            .isEqualTo("http://aod.cos.tx.xmcdn.com/group75/M09/99/AAA.m4a")
    }

    /**
     * 两个站内容不重复，才值得同时留着。重复的话应该只留一个。
     */
    @Test
    fun sitesCarryDifferentContent() {
        val titles = sites.map { source ->
            source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
                .list.take(6).map { it.title }.toSet()
        }
        println("听书吧: ${titles[0].take(3)}")
        println("乐听吧: ${titles[1].take(3)}")
        val overlap = titles[0].intersect(titles[1])
        println("重复: ${overlap.size} 本")
        assertThat(overlap.size < 3).isTrue()
    }
}
