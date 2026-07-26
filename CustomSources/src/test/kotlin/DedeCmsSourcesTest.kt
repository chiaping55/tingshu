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
        // 不带 Referer —— 实测 fdfs.xmcdn.com 一带就回 403，aod 带不带都一样，
        // 所以基类已经不再加这个头。测试要和 app 实际发出的请求一致。
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
     * 音质后缀该怎么选 —— **试过才知道**，不能凭地址判断。
     *
     * 喜马拉雅 /storages/ 底下有些文件带 `-aacv2-48K` 有些不带，站点两种都可能写错，
     * 而两组地址长得一模一样。所以基类会实际探测一下再交出去。
     * 这里把探测换成假的，验证"选哪一个"的决策本身，不依赖网络。
     */
    @Test
    fun picksWhicheverVariantTheServerAccepts() {
        val storages = "https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA.m4a"
        val suffixed = "https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA-aacv2-48K.m4a"

        // 只有带后缀的能播 -> 用带后缀的
        assertThat(probeSite { it == suffixed }.pickPlayable(storages)).isEqualTo(suffixed)
        // 只有原地址能播 -> 保持原样(旧版无条件补后缀，这一种会被改坏)
        assertThat(probeSite { it == storages }.pickPlayable(storages)).isEqualTo(storages)
        // 两个都不行 -> 交回原地址，让 app 照常报错(站上文件真没了)
        assertThat(probeSite { false }.pickPlayable(storages)).isEqualTo(storages)
    }

    /**
     * 没有别的变体可试时不该浪费一个探测请求 —— 每集都多打一次是有代价的。
     */
    @Test
    fun doesNotProbeWhenThereIsNoAlternative() {
        val probed = ArrayList<String>()
        val site = probeSite { probed.add(it); true }
        // group 路径不适用音质后缀
        val group = "http://aod.cos.tx.xmcdn.com/group75/M09/99/AAA.m4a"
        assertThat(site.pickPlayable(group)).isEqualTo(group)
        // 本来就带后缀了
        val already = "https://aod.cos.tx.xmcdn.com/storages/1a11-x/12/D1/AAA-aacv2-48K.m4a"
        assertThat(site.pickPlayable(already)).isEqualTo(already)
        assertThat(probed.isEmpty()).isTrue()
    }

    /** 探测结果由测试指定的假规则决定，不发真实请求 */
    private fun probeSite(accept: (String) -> Boolean) = object : DedeCmsTingShu() {
        override val baseUrl = "https://www.ting8.cc"
        override val categoryPath = "books"
        override val bookPath = "mp3"
        override fun getSourceId() = "probe"
        override fun getName() = "probe"
        override fun configure(connection: Connection): Connection = connection.testConfig(true)
        override fun isPlayable(url: String) = accept(url)
    }

    /**
     * 两个站内容不该完全重复，否则聚合搜索里每本书都会出现两次，那就该只留一个。
     *
     * 阈值放宽到"多数重叠才算镜像"：这批站会互相抓同一批热门书，
     * 分类首页撞上一两本是正常的，卡太紧会变成随机失败（踩过一次）。
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
        val sampled = minOf(titles[0].size, titles[1].size)
        println("重复: ${overlap.size} / $sampled 本")
        assertThat(overlap.size < sampled - 1).isTrue()
    }
}
