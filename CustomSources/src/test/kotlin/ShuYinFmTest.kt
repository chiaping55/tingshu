import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.ShuYinFmBase
import org.junit.Test

/**
 * 书音FM:接口真打一遍、音频真拉一段(含签名取址和 302 跟踪)。
 * 用真的源逻辑(基类),只把 app 的 notifyLoading 换成打印。
 */
class ShuYinFmTest {

    companion object {
        private val source = object : ShuYinFmBase() {
            override fun notifyLoading(pageInfo: String?) {
                println("loading episodes: $pageInfo")
            }
        }

        /** 《盗墓笔记（全 8 部）》TME 有声剧场版,790 集 → 章节两页 */
        private const val SAMPLE_BOOK = "https://m.mekui.com/ecmsapi/index.php?mod=movie&act=detail&id=126182"

        private val menus by lazy { source.getCategoryMenus() }
    }

    /** 签名拼法是纯字符串,离线钉住:顺序或 key 错一个字,表象只是「取不到地址」 */
    @Test
    fun signedQueryShape() {
        val query = source.signedAudioQuery("126182", "1", 1700000000L)
        println("query = $query")
        assertThat(query.startsWith("act=wapseries&id=126182&mod=movie&movieId=1&t=1700000000&token=")).isTrue()
        assertThat(query.substringAfter("token=").length).isEqualTo(32)
        // 同样的输入必须得到同样的签名;换个时间戳签名就要变
        assertThat(source.signedAudioQuery("126182", "1", 1700000000L)).isEqualTo(query)
        assertThat(source.signedAudioQuery("126182", "1", 1700000001L) != query).isTrue()
    }

    @Test
    fun searchTraditionalInput() {
        val (books, totalPage) = source.search("盜墓筆記", 1)
        books.take(3).forEach { println("  $it") }
        println("totalPage=$totalPage")
        assertThat(books).isNotEmpty()
        assertThat(totalPage).isGreaterThan(1)
        assertThat(books.any { it.title.contains("盜墓筆記") }).isTrue()
        assertThat(books.first().artist).isNotEmpty()
    }

    @Test
    fun categoryMenusFromNavigation() {
        menus.forEach { m -> println("  ${m.title}: ${m.tabs.size} tabs, e.g. ${m.tabs.take(4).map { it.title }}") }
        assertThat(menus.size).isGreaterThan(2)
        val fiction = menus.first { it.title == "有声小说" }
        assertThat(fiction.tabs.size).isGreaterThan(5)
    }

    @Test
    fun categoryListAndPaging() {
        val tab = menus.first { it.title == "有声小说" }.tabs.first()
        val category = source.getCategoryList(tab.url)
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isEqualTo(20)
        assertThat(category.totalPage).isGreaterThan(100)
        assertThat(category.nextUrl.contains("page=2")).isTrue()
        assertThat(category.list.first().coverUrl.startsWith("https://")).isTrue()
        val next = source.getCategoryList(category.nextUrl)
        assertThat(next.currentPage).isEqualTo(2)
        assertThat(next.list.first().bookUrl != category.list.first().bookUrl).isTrue()
    }

    @Test
    fun bookDetailFirstPageOnly() {
        val detail = source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = false)
        println("作者=${detail.author} 演播=${detail.artist} 章节=${detail.playList.size}/${detail.episodesCount} 封面=${detail.coverUrl}")
        detail.playList.take(2).forEach { println("  $it") }
        assertThat(detail.playList.size).isEqualTo(ShuYinFmBase.EPISODE_PAGE_SIZE)
        assertThat(detail.episodesCount).isGreaterThan(ShuYinFmBase.EPISODE_PAGE_SIZE)
        assertThat(detail.artist).isNotEmpty()
        assertThat(detail.coverUrl).isNotEmpty()
        assertThat(detail.playList.first().title.contains("001")).isTrue()
    }

    @Test
    fun bookDetailAllPagesAndRealAudio() {
        val detail = source.getBookDetailInfo(SAMPLE_BOOK, loadEpisodes = true, loadFullPages = true)
        println("全量章节=${detail.playList.size}")
        assertThat(detail.playList.size).isGreaterThan(ShuYinFmBase.EPISODE_PAGE_SIZE)

        val audioUrl = source.resolveAudioUrl(detail.playList.first().url)
        println("音频地址 = ${audioUrl.take(140)}")
        assertThat(audioUrl.startsWith("http")).isTrue()
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
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
