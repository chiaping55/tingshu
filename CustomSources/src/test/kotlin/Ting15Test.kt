import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.GxlCmsTingShu
import org.jsoup.Connection
import org.junit.Test

/**
 * GXLCMS 模板基类的**离线**测试(样本来自有听网 ting15.com)。
 *
 * 有听网 2026-11-02 起整站断线、一周未恢复，源已下架、Ting15.kt 删了；
 * 但它逼出来的基类行为(JSON unicode 转义还原、中文路径百分号编码、防盗链 Referer、
 * 取址接口 status 语义、搜索分页的链接形状)还在 GxlCmsTingShu 里，这些纯逻辑测试继续钉住。
 * 网络测试(分类、详情、真下载)已随站一起删除。
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
