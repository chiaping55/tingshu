import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.GxlCmsTingShu
import org.jsoup.Connection
import org.junit.Test

/**
 * ting15(GXLCMS 模板)测试。
 *
 * 这个站限流很严 —— 触发后锁一小时，所以整个套件只发一个用户听一集的请求量：
 * 页面共用(companion + by lazy)，取址只测一集。
 */
class Ting15Test {

    companion object {
        private val source = object : GxlCmsTingShu() {
            override val baseUrl = "https://www.ting15.com"

            override val audioApiPath = "/?s=api-getneoplay"

            override val categories = listOf("wuxiaxuanhuan" to "武侠玄幻")

            override fun getSourceId() = "7b4e91c6d8a3425fa07e2c5b39f1d846"

            override fun getName() = "有听网"

            override fun configure(connection: Connection): Connection = connection.testConfig(true)

            override fun toast(message: String) {
                println("toast: $message")
            }
        }

        /**
         * 《无敌剑域》—— 3977 集、分类有十几页，章节和分页的断言都撑得起来，
         * 而且它的音频在 oss-links 上、境外可以直连。
         *
         * 特意**不用**经典评书那一类当样本：那一整类的音频都在 cloud.guoguo.org.cn，
         * 境外 TCP 443 直接超时(抽了 3 本都一样)，拿它当样本会让测试在境外永远红，
         * 而问题根本不在代码里。相声小品那类音频在微信 CDN、境外实测能播，
         * 但只有 5 本 1 页、集数也少，撑不起分页和章节的断言。
         */
        private const val SAMPLE_BOOK = "https://www.ting15.com/wuxiaxuanhuan/2117.html"

        private val category by lazy {
            source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
        }

        private val detail by lazy {
            source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = true)
        }
    }

    @Test
    fun categoryList() {
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(5)
        // 分页栏带"尾页"，能拿到真实总页数
        assertThat(category.totalPage).isGreaterThan(5)
        assertThat(category.nextUrl).isNotEmpty()
        assertThat(category.list.first().coverUrl).isNotEmpty()
        // 列表页就给了作者和播音，不必进详情页
        assertThat(category.list.first().author).isNotEmpty()
    }

    @Test
    fun bookDetail() {
        println("作者=${detail.author} 演播=${detail.artist} 章节=${detail.playList.size}")
        println("简介=${detail.intro?.take(60)}")
        detail.playList.take(3).forEach { println("  $it") }
        assertThat(detail.author).isNotEmpty()
        assertThat(detail.artist).isNotEmpty()
        assertThat(detail.coverUrl).isNotEmpty()
        assertThat(detail.intro.isNullOrEmpty()).isFalse()
        // 章节全在详情页一次拿到，不分页
        assertThat(detail.playList.size).isGreaterThan(100)
        assertThat(detail.playList.first().title).isNotEmpty()
    }

    /**
     * 取址 + **真的下载一段音频**。
     *
     * 只断言"拿到地址了"是不够的 —— 起点有声网那次就是地址拿到了却播不出来
     * (线路返回空、地址缺后缀)，所以这里一定要真的读到字节。
     */
    @Test
    fun audioUrlIsPlayable() {
        val chapterUrl = detail.playList.first().url
        println("章节页 = $chapterUrl")
        val audioUrl = source.resolveAudioUrl(chapterUrl)
        println("音频地址 = $audioUrl")
        assertThat(audioUrl).isNotEmpty()

        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        // 这个 cdn 校验防盗链，不带 Referer 直接 400 —— headers() 就是为它准备的
        source.headers(audioUrl).forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试: HTTP $code ${connection.contentType} ${bytes}B")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(bytes > 0).isTrue()
    }

    /**
     * JSON 的 unicode 转义必须还原。
     *
     * 这个站有些音频路径带中文(评书那类是 ps/田连元_孙庞斗智/01.mp3)，JSON 里写成
     * 反斜杠加 u7530 这种转义。只处理斜杠转义的话，地址里会留着字面上的转义序列，
     * 表象是"地址取到了却播不出来" —— 正是"只断言拿到地址"会漏掉的那类 bug。
     *
     * 输入用字符串拼接构造：直接写反斜杠 u 会被工具链误改，写成双反斜杠又等于
     * 测了一个站点根本不会送来的输入 —— 第一版就是这么错的，测试红了却是测试自己的错。
     */
    @Test
    fun decodesUnicodeEscapesInAudioUrl() {
        val bs = "\\"
        val body = "{\"url\":\"https:$bs/$bs/cloud.x.cn$bs/a.php?uid=ps$bs/" +
            "${bs}u7530${bs}u8fde${bs}u5143$bs/01.mp3\",\"status\":1,\"ourl\":\"\"}"
        println("原始 = $body")
        val url = source.parseAudioResponse(body)
        println("解码 = $url")
        // 解开转义之后还要百分号编码 —— 带原始中文去请求 cdn 回 404(实测)
        assertThat(url).isEqualTo(
            "https://cloud.x.cn/a.php?uid=ps/%E7%94%B0%E8%BF%9E%E5%85%83/01.mp3"
        )
        // 地址里绝不能残留转义序列
        assertThat(url.contains(bs)).isFalse()
    }

    /**
     * 编码只该动非 ASCII，结构字符和已经编码好的 %XX 都不能碰。
     */
    @Test
    fun encodingLeavesAsciiUrlsAlone() {
        val plain = "https://cdn.example.com/a/b.m4a?x=1&y=2"
        assertThat(source.encodeNonAscii(plain)).isEqualTo(plain)
        val already = "https://cdn.example.com/%E7%94%B0/b.m4a"
        assertThat(source.encodeNonAscii(already)).isEqualTo(already)
    }

    /**
     * 防盗链这条必须验：漏了 Referer 站点回 400，表象是"音频坏了"。
     */
    @Test
    fun audioNeedsReferer() {
        assertThat(source.headers("https://oss-links.guoguo.org.cn/x.m4a")["Referer"])
            .isEqualTo("https://www.ting15.com/")
    }

    /**
     * 取址接口的 status 语义 —— 纯字符串处理，不用网络，所以站点限流时也验得准。
     *
     * 最要紧的是 -2：那是限流(锁一小时)，必须抛出去让上层知道"过一会再试"，
     * 而不能返回空地址 —— 空地址在 app 里的表现是"这本书失效了"，会让人以为源坏了。
     */
    @Test
    fun audioResponseStatusSemantics() {
        assertThat(source.parseAudioResponse("""{"url":"https://cdn/a.m4a","status":1,"ourl":""}"""))
            .isEqualTo("https://cdn/a.m4a")
        // ourl 优先于 url
        assertThat(source.parseAudioResponse("""{"url":"https://b","status":1,"ourl":"https://a"}"""))
            .isEqualTo("https://a")
        // 带 BOM 也要能解(站点实际就带 BOM)
        assertThat(source.parseAudioResponse("﻿{\"url\":\"https://c\",\"status\":1,\"ourl\":\"\"}"))
            .isEqualTo("https://c")
        // 缺章、付费返回空地址并提示
        assertThat(source.parseAudioResponse("""{"url":null,"status":0,"ourl":""}""")).isEqualTo("")
        assertThat(source.parseAudioResponse("""{"url":null,"status":-1,"ourl":""}""")).isEqualTo("")
        // 限流必须抛出，不能伪装成"没有音频"
        var threw = false
        try {
            source.parseAudioResponse("""{"url":null,"status":-2,"ourl":""}""")
        } catch (e: java.io.IOException) {
            threw = true
            println("限流异常讯息: ${e.message}")
        }
        assertThat(threw).isTrue()
    }

    /**
     * 搜索**有分页** —— 原本写死回 1 页，使用者只拿得到前 10 笔。
     *
     * 实测搜「天」有 14 页(约 140 笔)。而且搜索页的分页链接形式和分类页完全不同
     * (`-p-{N}.html` vs `/index{N}.html`)，所以不能重用 parseTotalPage ——
     * 套错的话一页都找不到、又退回「只有 1 页」。
     * 纯字符串处理，不依赖网络，所以站点限流时也验得准。
     */
    @Test
    fun searchPaginationIsReadFromItsOwnLinkShape() {
        val html = """
            <div class="c-page">
              <span class="current">1</span>
              <a data="p-2" href="?s=ting-search-wd-%E5%A4%A9-p-2.html">2</a>
              <a data="p-14" href="?s=ting-search-wd-%E5%A4%A9-p-14.html">尾页</a>
            </div>
        """.trimIndent()
        val doc = org.jsoup.Jsoup.parse(html)
        assertThat(source.parseSearchTotalPage(doc, 1)).isEqualTo(14)
        // 分类页那套正则配不上搜索页的链接，会退回当前页 —— 这就是不能重用的理由
        assertThat(source.parseTotalPage(doc, 1)).isEqualTo(1)
    }

    /** 搜索翻页地址的组装 */
    @Test
    fun searchPageUrlShape() {
        val url = source.searchPageUrlForTest("天", 2)
        println("第2页 = $url")
        assertThat(url).isEqualTo(
            "https://www.ting15.com/?s=ting-search-wd-%E5%A4%A9-p-2.html"
        )
    }
}
