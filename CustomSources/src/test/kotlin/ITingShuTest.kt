import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.ITingShu
import com.github.eprendre.sources_by_cp2.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import org.jsoup.Connection
import org.junit.Test

/**
 * 爱听书测试。
 *
 * 音频走 WebView 嗅探，JVM 里没有 WebView，所以这里测不到音频那一段 ——
 * 但搜索/分类/详情/章节都会真实请求验证。这个站带反爬挑战，能拿到内容本身就证明
 * PtcmsGuard 从脚本里读 cookie 名称是对的（这个站叫 `__51guid__`，不是起点系的 `pt_guid`）。
 */
class ITingShuTest {

    companion object {
        /**
         * 共用一份源实例。
         *
         * JUnit 每个测试方法都会新建测试类实例，源要是跟着重建，PtcmsGuard 的 cookie
         * 就无法复用，每个测试都得重新过一次反爬握手 —— 请求量翻好几倍，把这个站的限流
         * 直接触发了（实测 5 个测试跑到一半就全 429）。app 里的源本来就是单例
         * （Kotlin object），共用一份才是忠实的测试条件，顺带也不去打扰站方。
         */
        private val source = object : PtcmsTingShu() {
            override val baseUrl = "https://www.itingshu.net"

            override val titleSuffix = "有声小说"

            /**
             * 必须和 ITingShu 保持一致：这个站用旧 UA 请求章节目录页会回 429。
             * repo 的 testConfig 写死的是 Chrome 77（2019 年），正好会被挡，
             * 症状是书籍页正常、目录页 429，很容易误判成站点限流。
             */
            override val userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            /** 与 ITingShu 一致：章节目录走手机站，桌面站限流很紧 */
            override val episodeDirectoryBaseUrl = "https://m.itingshu.net"

            override val episodeDirectoryUserAgent =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

            override fun getSourceId() = "5a1c8f24e7b3406d92fd7c05b1e8a367"

            override fun getName() = "爱听书"

            override fun getCategoryMenus(): List<CategoryMenu> = listOf(
                CategoryMenu(
                    "小说",
                    listOf(
                        CategoryTab("玄幻修真", "$baseUrl/yousheng/xuanhuan/lastupdate.html"),
                        CategoryTab("长篇评书", "$baseUrl/yousheng/pingshu/lastupdate.html")
                    )
                )
            )

            override fun search(keywords: String, page: Int) = Pair(
                parseBooks(
                    fetchPost(
                        "$baseUrl/novelsearch/search/result.html",
                        mapOf("searchword" to keywords),
                        "$baseUrl/"
                    )
                ),
                1
            )

            override fun configure(connection: Connection): Connection =
                connection.testConfig(true)

            override fun notifyLoading(pageInfo: String?) {
                println("loading episodes: $pageInfo")
            }

            override fun toast(message: String) {
                println("toast: $message")
            }
        }

        private const val SAMPLE_BOOK = "https://www.itingshu.net/youshengxiaoshuo/12508/"

        /**
         * 页面抓一次、给所有断言共用。
         *
         * 这个站限流很紧：每个测试各自去抓的话，跑到后面几个就全 429 了（真的踩过）。
         * 所以整个套件只发最少的请求 —— 也就是一个用户浏览一本书的请求量。
         */
        private val bookPage by lazy { source.fetch(SAMPLE_BOOK) }

        private val searchResult by lazy { source.search("万古最强宗", 1) }

        private val category by lazy {
            source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
        }

        private val detail by lazy {
            source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = false)
        }
    }

    /**
     * 反爬挑战：cookie 名称是 `__51guid__`，写死 `pt_guid` 的话这里只会拿到挑战页。
     */
    @Test
    fun passesGuardChallenge() {
        println("title=${bookPage.title()}")
        assertThat(bookPage.selectFirst("div.book-info") != null).isTrue()
    }

    @Test
    fun search() {
        val (books, _) = searchResult
        books.forEach { println("  ${it.title} | 演播=${it.artist} | ${it.bookUrl}") }
        assertThat(books.size).isGreaterThan(0)
        // titleSuffix 要把书名末尾的"有声小说"去掉
        assertThat(books.none { it.title.endsWith("有声小说") }).isTrue()
    }

    @Test
    fun categoryList() {
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(5)
        // 这个站的分页栏写着"共 N 页"，玄幻一类有几百页
        assertThat(category.totalPage).isGreaterThan(100)
        assertThat(category.nextUrl).isNotEmpty()
        assertThat(category.list.first().coverUrl).isNotEmpty()
    }

    /**
     * 详情页要能取到作者与演播 —— 同一本书站上常有好几个版本，
     * 这两个字段是判断"是不是收藏里那一版"的唯一依据。
     */
    @Test
    fun bookDetailHasAuthorAndArtist() {
        println("作者=${detail.author} 演播=${detail.artist} 章节=${detail.playList.size}")
        println("简介=${detail.intro?.take(50)}")
        detail.playList.take(3).forEach { println("  $it") }
        assertThat(detail.author).isNotEmpty()
        assertThat(detail.artist).isNotEmpty()
        assertThat(detail.coverUrl).isNotEmpty()
        // 必须是目录页第一页（第1集起、正序、满 50 集），不能是详情页那"最新几集"——
        // 那 10 集会被 app 当成整本书的播放列表：一本 2640 集的书显示成 1/10、
        // 列表停在最后一页、播放位置全乱（用户实测踩到）。
        assertThat(detail.playList.size).isGreaterThan(40)
        assertThat(detail.playList.first().title).contains("第1集")
    }

    /**
     * 章节目录分页：验证第二页能取到内容就够了。
     *
     * 不跑全量（这本有 27 页）—— 那样会触发站点限流，而限流本身已经在 PtcmsGuard 里
     * 退避重试、并在翻页时保留已抓到的部分处理过了。
     */
    /**
     * 换域名这段是纯字符串处理，单独测一次 —— 不依赖网络，所以站点限流时也验得准。
     */
    @Test
    fun directoryUrlGoesToMobileHost() {
        val desktop = "https://www.itingshu.net/itingshus/ugRmCc/cbbhASafUAuaRqAa.html"
        val page3 = source.directoryPageUrl(desktop, 3)
        println("page3 = $page3")
        assertThat(page3).isEqualTo(
            "https://m.itingshu.net/itingshus/ugRmCc/cbbhASafUAuaRqAa.html?page=3&sort=asc"
        )
    }

    @Test
    fun secondEpisodePageLoadsFromMobileHost() {
        val dirUrl = bookPage.selectFirst("a.dirurl")!!.absUrl("href")
        val target = source.directoryPageUrl(dirUrl, 2)
        println("目录页地址 = $target")
        // 必须换到手机站，桌面站连翻会 429 并且波及其它书的浏览
        assertThat(target.startsWith("https://m.itingshu.net/")).isTrue()

        val secondPage = source.fetch(target, SAMPLE_BOOK, "Mozilla/5.0 (Linux; Android 13) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
        // 直接用正式代码解析，不在测试里复制选择器 —— 手机站的容器和桌面站不同
        // (ol.novel-text-list vs div#playlist)，测试自带选择器的话正式代码错了也测不出来
        val episodes = source.parseEpisodes(secondPage)
        println("第2页章节 ${episodes.size} 集, 首集=${episodes.firstOrNull()?.title}")
        assertThat(episodes.size).isGreaterThan(10)
        // 手机站章节名带 .mp3 尾巴，要去掉
        assertThat(episodes.none { it.title.endsWith(".mp3") }).isTrue()
        // 总页数从手机站的"选集"弹窗取，认不得会算成 1，全量加载就只剩 50 集
        val totalPage = source.parseEpisodeTotalPage(secondPage)
        println("总页数 = $totalPage")
        assertThat(totalPage).isGreaterThan(20)
    }

    /**
     * 搜索第 2 页起的地址组装 —— 纯字符串处理，不依赖网络。
     *
     * 这个站的关键词编码很特别:URL-encode 之后把 `%` 换成 `oOo`。
     * 原本 search() 写死「不分页、固定回 1 页」，搜「斗罗大陆」只看得到 18 笔里的前 6 笔，
     * 续作全都搜不到，而使用者只会以为这个源没收录。
     */
    @Test
    fun searchPageUrlUsesTheOddEncoding() {
        val url = ITingShu.searchPageUrl("斗罗大陆", 2)
        println("第2页 = $url")
        assertThat(url).isEqualTo(
            "https://www.itingshu.net/search/" +
                "oOoE6oOo96oOo97oOoE7oOoBDoOo97oOoE5oOoA4oOoA7oOoE9oOo99oOo86" +
                "/lastupdate/2.html"
        )
        // 空格不能留成 +，站方认的是 oOo20
        assertThat(ITingShu.searchPageUrl("a b", 3))
            .isEqualTo("https://www.itingshu.net/search/aoOo20b/lastupdate/3.html")
    }
}
