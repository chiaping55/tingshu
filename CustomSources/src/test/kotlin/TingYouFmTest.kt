import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.TingYouFmBase
import org.junit.Test

/**
 * 听友听书:接口真打一遍、音频真拉一段。用真的源逻辑(基类),不碰 app 的 stub。
 */
class TingYouFmTest {

    companion object {
        private val source = object : TingYouFmBase() {}

        /** 《剑来》(大斌),5000 多集,章节一次全给 */
        private const val SAMPLE_BOOK = "https://www.tingyou.fm/listening/album/9783312385"

        private val menus by lazy { source.getCategoryMenus() }
    }

    /** filters 要撑出「排行 + 有声小说 + 评书」三组,子分类从接口来 */
    @Test
    fun categoryMenusComeFromFilters() {
        menus.forEach { m -> println("  ${m.title}: ${m.tabs.size} tabs, e.g. ${m.tabs.take(4).map { it.title }}") }
        assertThat(menus.size).isGreaterThan(2)
        val fiction = menus.first { it.title == "有声小说" }
        assertThat(fiction.tabs.size).isGreaterThan(10)
        assertThat(fiction.tabs.any { it.title == "相声小品" }).isTrue()
        val pingshu = menus.first { it.title == "评书" }
        assertThat(pingshu.tabs.any { it.title == "单田芳" }).isTrue()
    }

    @Test
    fun categoryListAndPaging() {
        val tab = menus.first { it.title == "排行" }.tabs.first()
        val category = source.getCategoryList(tab.url)
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isEqualTo(20)
        assertThat(category.totalPage).isGreaterThan(5)
        assertThat(category.nextUrl.contains("page=2")).isTrue()
        assertThat(category.list.first().coverUrl).isNotEmpty()
        assertThat(category.list.first().artist).isNotEmpty()
        assertThat(category.list.first().status.contains("集")).isTrue()
        val next = source.getCategoryList(category.nextUrl)
        assertThat(next.currentPage).isEqualTo(2)
        assertThat(next.list.first().bookUrl != category.list.first().bookUrl).isTrue()
    }

    /** 子分类(有声小说 > 相声小品)要过滤得出东西 */
    @Test
    fun subCategoryHasBooks() {
        val tab = menus.first { it.title == "有声小说" }.tabs.first { it.title == "相声小品" }
        val category = source.getCategoryList(tab.url)
        category.list.take(3).forEach { println("  $it") }
        assertThat(category.list).isNotEmpty()
    }

    /**
     * 繁体输入要搜得到,结果书名里的关键词要还原成繁体(app 聚合搜索按原词过滤)。
     * 注意别用 Git Bash 的 curl 验证这个接口:中文会被送成乱码、永远 0 条。
     */
    @Test
    fun searchTraditionalInput() {
        val (books, totalPage) = source.search("劍來", 1)
        books.take(3).forEach { println("  $it") }
        println("search -> ${books.size} 本, totalPage=$totalPage")
        assertThat(books).isNotEmpty()
        assertThat(books.any { it.title.contains("劍來") }).isTrue()
        assertThat(books.first().bookUrl.contains("/listening/album/")).isTrue()
    }

    @Test
    fun bookDetailAndRealAudio() {
        val detail = source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = true)
        println("作者=${detail.author} 演播=${detail.artist} 章节=${detail.playList.size} 封面=${detail.coverUrl}")
        detail.playList.take(3).forEach { println("  $it") }
        assertThat(detail.artist).isNotEmpty()
        assertThat(detail.coverUrl).isNotEmpty()
        assertThat(detail.playList.size).isGreaterThan(1000)
        assertThat(detail.playList.first().title.contains("001")).isTrue()

        val audioUrl = source.resolveAudioUrl(detail.playList.first().url)
        println("音频地址 = ${audioUrl.take(120)}")
        assertThat(audioUrl.startsWith("https://")).isTrue()
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        // 故意不带 UA/Referer:CDN 实测裸请求就给 206,钉住这一点,免得以后有人加 header 反而弄坏
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val type = connection.contentType ?: ""
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试: HTTP $code $type ${bytes}B")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(type.startsWith("audio") || type.contains("mp4") || type.contains("octet")).isTrue()
        assertThat(bytes > 0).isTrue()
    }
}
