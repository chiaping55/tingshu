import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.eprendre.sources_by_cp2.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.junit.Test

/**
 * 分页总数与列表字段的解析 —— 都是拿实机 HTML 结构写的离线测试。
 *
 * 这两处都是审计抓到的，共同点是**症状很轻、很容易被当成"就是这样"**：
 * 页数多显示几百页、演播者栏显示成作者，不会报错、不会崩，
 * 所以只有拿站点实际的 HTML 对着看才会发现。
 */
class PagerAndFieldsTest {

    private val source = object : PtcmsTingShu() {
        override val baseUrl = "https://www.itingshu.net"
        override fun getSourceId() = "pager-test"
        override fun getName() = "pager-test"
        override fun getCategoryMenus(): List<CategoryMenu> = emptyList()
        override fun configure(connection: Connection): Connection = connection
        override fun toast(message: String) {}
    }

    /**
     * 站方文案会写错，跳转链接不会。
     *
     * 爱听书玄幻分类的分页栏写着「共 607 页」，但「尾页」链接指向第 304 页 ——
     * 以前优先信文案，app 就显示 607 页、第 305 页之后全是空白。
     */
    @Test
    fun lastPageLinkWinsOverTheClaimedTotal() {
        val html = """
            <div class="fanye">
              共 607 页
              <a href="/yousheng/xuanhuan/lastupdate/1/1.html">首页</a>
              <strong>1</strong>
              <a href="/yousheng/xuanhuan/lastupdate/1/2.html">2</a>
              <a href="/yousheng/xuanhuan/lastupdate/1/2.html">下页</a>
              <a href="/yousheng/xuanhuan/lastupdate/1/304.html">尾页</a>
            </div>
        """.trimIndent()
        assertThat(source.parseTotalPage(Jsoup.parse(html), 1)).isEqualTo(304)
    }

    /** 没有尾页链接时才退回文案 —— 别的 PTCMS 站那个数字是准的 */
    @Test
    fun fallsBackToClaimedTotalWhenThereIsNoLastPageLink() {
        val html = """
            <div class="fanye">
              共 42 页 <strong>1</strong>
              <a href="/book/xhqh/lastupdate/2.html">2</a>
              <a href="/book/xhqh/lastupdate/2.html">下一页</a>
            </div>
        """.trimIndent()
        assertThat(source.parseTotalPage(Jsoup.parse(html), 1)).isEqualTo(42)
    }

    /** 两者都没有时看页码，并且用"下页/下一页"判断还有没有后续 */
    @Test
    fun usesPageNumbersWhenNeitherHintExists() {
        val html = """
            <div class="fanye">
              <strong>7</strong>
              <a href="/x/8.html">下页</a>
            </div>
        """.trimIndent()
        // 页码里最大的就是当前页 7，但有"下页"，所以至少还有第 8 页
        assertThat(source.parseTotalPage(Jsoup.parse(html), 7)).isEqualTo(8)
    }

    /**
     * 爱听书的列表项：`span.book-author` 是**作者**(站方还 display:none 藏起来)，
     * 演播在 `span.book-boyin`，多人剧会有好几个 a。
     *
     * 原本一律把 book-author 当演播，于是列表把「唐家三少」显示成演播者、作者栏空着 ——
     * 而演播者正是判断「是不是收藏里那一版」的唯一依据。
     */
    @Test
    fun itingshuListPutsAuthorAndArtistInTheRightFields() {
        val html = """
            <ul class="list-works"><li>
              <div class="list-imgbox"><img data-original="/cover/a.jpg"></div>
              <dl>
                <dt class="list-book-dt"><a href="/youshengxiaoshuo/31604/">斗罗大陆</a><span>连载</span></dt>
                <dd class="list-book-des">简介</dd>
                <dd class="list-book-cs">
                  <span class="book-author" style="display:none;">
                    <aria>作者：</aria><a href="/author/x">唐家三少</a>
                  </span>
                  <span class="book-boyin">演播：
                    <a href="/boyin/605.html">喜道公子</a>
                    <a href="/boyin/11558.html">安苏Ansu</a>
                  </span>
                </dd>
              </dl>
            </li></ul>
        """.trimIndent()
        val book = source.parseBooks(Jsoup.parse(html)).single()
        println("作者=${book.author} 演播=${book.artist}")
        assertThat(book.author).isEqualTo("唐家三少")
        assertThat(book.artist).isEqualTo("喜道公子 安苏Ansu")
    }

    /**
     * 起点系只有 `span.book-author`，而里面装的**确实是演播** —— 不能跟着一起改。
     * 这条是防止上面那个修正把已经对的站弄坏。
     */
    @Test
    fun qidianStyleListStillReadsArtistFromBookAuthor() {
        val html = """
            <ul class="list-works"><li>
              <div class="list-imgbox"><img data-original="/cover/b.jpg"></div>
              <dl>
                <dt class="list-book-dt"><a href="/book/12389.html">今天他飞升了吗</a><span>连载</span></dt>
                <dd class="list-book-cs">
                  <span class="book-author">演播：<a href="/boyin/1.html">LBK剧社</a></span>
                </dd>
              </dl>
            </li></ul>
        """.trimIndent()
        val book = source.parseBooks(Jsoup.parse(html)).single()
        println("作者=${book.author} 演播=${book.artist}")
        assertThat(book.artist).isEqualTo("LBK剧社")
        assertThat(book.author).isEqualTo("")
    }
}
