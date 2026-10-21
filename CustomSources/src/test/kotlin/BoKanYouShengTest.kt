import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.BoKanYouShengBase
import org.junit.Test

/**
 * 博看有声:接口真打一遍。用的是真的源逻辑(基类),只把 app 的 notifyLoading 换成打印。
 */
class BoKanYouShengTest {

    companion object {
        private val source = object : BoKanYouShengBase() {
            override fun notifyLoading(pageInfo: String?) {
                println("loading episodes: $pageInfo")
            }
        }

        /** 《黑帮·贩毒集团神秘内幕大全》203 集,够验三页章节 */
        private const val SAMPLE_BOOK = "https://api.bookan.com.cn/voice/album/info?album_id=3335495"

        private val category by lazy {
            source.getCategoryList(source.getCategoryMenus().first().tabs.first().url)
        }
    }

    /** 繁体输入要搜得到,而且结果书名里的关键词要还原成繁体,否则 app 聚合搜索会把它滤掉 */
    @Test
    fun searchTraditionalInput() {
        val (books, totalPage) = source.search("歷史", 1)
        books.take(3).forEach { println("  $it") }
        println("totalPage=$totalPage")
        assertThat(books).isNotEmpty()
        assertThat(totalPage).isGreaterThan(1)
        assertThat(books.any { it.title.contains("歷史") }).isTrue()
        assertThat(books.first().bookUrl.contains("album_id=")).isTrue()
    }

    @Test
    fun categoryList() {
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(5)
        assertThat(category.totalPage).isGreaterThan(1)
        assertThat(category.nextUrl).isNotEmpty()
        assertThat(category.list.first().coverUrl).isNotEmpty()
        assertThat(category.list.first().status.contains("集")).isTrue()
    }

    /** 每个分类都要有货 —— 原作者那版有两个分类的接口已经空了/回 null,这里钉住剩下的都活着 */
    @Test
    fun everyCategoryHasBooks() {
        source.getCategoryMenus().flatMap { it.tabs }.forEach { tab ->
            val list = source.getCategoryList(tab.url).list
            println("  ${tab.title}: ${list.size} 本")
            assertThat(list).isNotEmpty()
            Thread.sleep(300)
        }
    }

    @Test
    fun bookDetailFirstPageOnly() {
        val detail = source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = false)
        println("出版社=${detail.artist} 封面=${detail.coverUrl} 章节=${detail.playList.size}")
        detail.playList.take(3).forEach { println("  $it") }
        assertThat(detail.playList.size).isEqualTo(BoKanYouShengBase.UNITS_PER_PAGE)
        assertThat(detail.coverUrl).isNotEmpty()
    }

    @Test
    fun bookDetailAllPagesAndRealAudio() {
        val detail = source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = true)
        println("全量章节=${detail.playList.size}")
        assertThat(detail.playList.size).isGreaterThan(BoKanYouShengBase.UNITS_PER_PAGE)
        // 章节地址就是音频,真的拉一段字节确认能播
        val audioUrl = detail.playList.first().url
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val type = connection.contentType ?: ""
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试: HTTP $code $type ${bytes}B $audioUrl")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(type.startsWith("audio") || type.contains("mp4") || type.contains("octet")).isTrue()
        assertThat(bytes > 0).isTrue()
    }
}
