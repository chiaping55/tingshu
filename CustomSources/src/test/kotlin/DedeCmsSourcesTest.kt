import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.DedeCmsTingShu
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
     *
     * 「没有别的变体」现在只剩一种情况：地址本来就带 `-aacv2-48K`。
     * (原本还包括「不是 /storages/ 路径」，但那道守卫已经拿掉 ——
     *  实测 group75/… 这种老路径原地址 404、补后缀 206 能播，
     *  守着它等于一次都不试就放弃。见 suffixCandidateIsTriedOnNonStoragesPathsToo。)
     */
    @Test
    fun doesNotProbeWhenThereIsNoAlternative() {
        val probed = ArrayList<String>()
        val site = probeSite { probed.add(it); true }
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

    /**
     * 简介只能取本书自己那一段，不能把侧栏推荐吃进来。
     *
     * 详情页有 27 个 p.f-gray，本书只占 3 个 —— 原本全抓来串成一行，
     * `内容介绍[：:](.*)` 的 `.*` 就一路吃到结尾，每本书的简介尾巴都黏上
     * 「XXX作者：…，由…播音」这类侧栏文案(实测两站 12 本全中)。
     */
    @Test
    fun introDoesNotSwallowSidebarRecommendations() {
        val html = """
            <div class="tx-box mb10"><div class="style-img clearfix pd10">
              <div class="img-100"><span class="img-box"><img src="/cover.jpg"></span></div>
              <section>
                <h1 class="style-title">最狂战神</h1>
                <p class="mb10 f-12 f-gray"><a href="#">竟然先生</a> <span>已完结</span></p>
                <p class="f-gray mb10">最狂战神作者：<a href="#">竟然先生</a>，由<a href="#">我本王少</a>播音，听书吧提供收听平台。</p>
                <p class="f-gray mb10">内容介绍：他，是西北战王，守护夏国山河以北。</p>
              </section>
            </div></div>
            <div class="side">
              <p class="f-gray">超品相师作者：九灯和善，由大音希声播音。</p>
              <p class="f-gray">百炼飞升录作者：青阳小栈，由某某播音。</p>
            </div>
            <div id="yuedu"><ul class="ul-36"><li><a href="/play/1-0-0.html" title="第001集	">第001集</a></li></ul></div>
        """.trimIndent()
        val site = probeSite { true }
        val detail = site.parseDetailForTest(org.jsoup.Jsoup.parse(html))
        println("简介=${detail.intro}")
        println("作者=${detail.author} 演播=${detail.artist}")
        assertThat(detail.intro).isEqualTo("他，是西北战王，守护夏国山河以北。")
        assertThat(detail.author).isEqualTo("竟然先生")
        assertThat(detail.artist).isEqualTo("我本王少")
        // 章节名要 trim —— 站方有些书整本都带尾 tab
        assertThat(detail.playList.single().title).isEqualTo("第001集")
    }

    /**
     * 音质后缀候选**不该限制在 /storages/ 路径**。
     *
     * 实测 group75/… 这种老路径原地址 404、补后缀 206 能播，
     * 而原本那道守卫让它一次都不试就放弃。
     */
    @Test
    fun suffixCandidateIsTriedOnNonStoragesPathsToo() {
        val plain = "http://aod.cos.tx.xmcdn.com/group75/M09/8F/AC/wKgO3V6SuBrysbuSAEGnDInNYiU675.m4a"
        val suffixed = plain.replace(".m4a", "-aacv2-48K.m4a")
        // 只有带后缀的能播 -> 必须选它(旧版会直接回原地址、根本不试)
        assertThat(probeSite { it == suffixed }.pickPlayable(plain)).isEqualTo(suffixed)
    }
}
