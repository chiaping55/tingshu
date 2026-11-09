import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.YunTuYouShengBase
import org.junit.Test

/**
 * 云图有声:接口真打一遍、音频真拉一段。用真的源逻辑(基类),只把 app 的 notifyLoading 换成打印。
 *
 * 这个源移植的直接动机是「每本书只有 200 集」:接口本身分页正常(第 2 页有货、一页 500 也给),
 * 是原作者的客户端只抓第一页。所以这里**一定要钉住超过 200 集的书能拿全**。
 */
class YunTuYouShengTest {

    companion object {
        private val source = object : YunTuYouShengBase() {
            override fun notifyLoading(pageInfo: String?) {
                println("loading episodes: $pageInfo")
            }
        }

        /** 《诡话三国》901 集:第一页 500、全量两页 —— 正好同时验「超过 200」和「超过一页」 */
        private const val LONG_BOOK = "https://open-service.yuntuys.com/api/w_ys/book/getBook/wechat:07955551-706c-4259-9aa0-db4627dfca57/26196"

        /** 《纵横千万里，只手遮天下》391 集:一页就够,但原作者那版只会显示 200 集 */
        private const val MID_BOOK = "https://open-service.yuntuys.com/api/w_ys/book/getBook/wechat:07955551-706c-4259-9aa0-db4627dfca57/18361"

        private val menus by lazy { source.getCategoryMenus() }
    }

    /** 繁体输入要搜得到,结果书名里的关键词要还原成繁体 */
    @Test
    fun searchTraditionalInput() {
        val (books, totalPage) = source.search("遮天", 1)
        books.take(3).forEach { println("  $it") }
        println("totalPage=$totalPage")
        assertThat(books).isNotEmpty()
        assertThat(books.any { it.title.contains("遮天") }).isTrue()
        assertThat(books.first().bookUrl.contains("/getBook/")).isTrue()
        assertThat(books.first().status.contains("集")).isTrue()
    }

    @Test
    fun categoryListAndPaging() {
        val tab = menus.first { it.title == "少儿教育" }.tabs.first { it.title == "儿童文学" }
        val category = source.getCategoryList(tab.url)
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isEqualTo(20)
        assertThat(category.totalPage).isGreaterThan(1)
        assertThat(category.nextUrl.contains("pageNum=2")).isTrue()
        assertThat(category.list.first().coverUrl).isNotEmpty()
        assertThat(category.list.first().artist).isNotEmpty()
        val next = source.getCategoryList(category.nextUrl)
        assertThat(next.currentPage).isEqualTo(2)
        assertThat(next.list.first().bookUrl != category.list.first().bookUrl).isTrue()
    }

    /**
     * 接口对好些分类回 totalPage=0 却给满 20 本 —— 这时不能把它当「只有 1 页」,
     * 要按基类约定报「当前页+1」让 app 继续翻。
     */
    @Test
    fun categoryWithZeroTotalPageStillPages() {
        val tab = menus.first { it.title == "经典文学" }.tabs.first { it.title == "世界名著" }
        val category = source.getCategoryList(tab.url)
        println("page ${category.currentPage}/${category.totalPage} n=${category.list.size}")
        assertThat(category.list.size).isEqualTo(20)
        assertThat(category.totalPage).isGreaterThan(1)
        assertThat(category.nextUrl).isNotEmpty()
    }

    /** 391 集的书一页就要拿全 —— 这正是原作者那版只给 200 集的场景 */
    @Test
    fun midBookGetsAllEpisodesInOnePage() {
        val detail = source.getBookDetailInfo(MID_BOOK, loadEpisodes = true, loadFullPages = false)
        println("作者=${detail.author} 演播=${detail.artist} 章节=${detail.playList.size}/${detail.episodesCount}")
        assertThat(detail.playList.size).isEqualTo(391)
        assertThat(detail.episodesCount).isEqualTo(391)
        assertThat(detail.author).isNotEmpty()
        assertThat(detail.coverUrl).isNotEmpty()
        assertThat(detail.playList.first().title).isEqualTo("第1章")
    }

    @Test
    fun longBookFirstPageThenAllPagesAndRealAudio() {
        val first = source.getBookDetailInfo(LONG_BOOK, loadEpisodes = true, loadFullPages = false)
        println("第一页=${first.playList.size}/${first.episodesCount}")
        assertThat(first.playList.size).isEqualTo(YunTuYouShengBase.EPISODE_PAGE_SIZE)
        assertThat(first.episodesCount).isGreaterThan(YunTuYouShengBase.EPISODE_PAGE_SIZE)

        val full = source.getBookDetailInfo(LONG_BOOK, loadEpisodes = true, loadFullPages = true)
        println("全量=${full.playList.size} 首集=${full.playList.first().title} 末集=${full.playList.last().title}")
        assertThat(full.playList.size).isEqualTo(full.episodesCount)
        assertThat(full.playList.size).isGreaterThan(YunTuYouShengBase.EPISODE_PAGE_SIZE)

        // 章节地址就是音频直链,真的拉一段
        val audioUrl = full.playList.last().url
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        val code = connection.responseCode
        val type = connection.contentType ?: ""
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试(末集): HTTP $code $type ${bytes}B $audioUrl")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(type.startsWith("audio") || type.contains("octet")).isTrue()
        assertThat(bytes > 0).isTrue()
    }
}
