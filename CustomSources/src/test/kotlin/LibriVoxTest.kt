import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.LibriVox
import org.junit.Test

/**
 * LibriVox:直接用真的源对象(它不碰 app 的 stub),接口真打一遍、音频真拉一段。
 */
class LibriVoxTest {

    /** 书名前缀 + 作者两路合并:搜 pride 要有《Pride and Prejudice》,搜 Austen 也要有 */
    @Test
    fun searchByTitlePrefixAndAuthor() {
        val (byTitle, totalPage) = LibriVox.search("pride", 1)
        byTitle.take(3).forEach { println("  $it") }
        println("totalPage=$totalPage")
        assertThat(byTitle.any { it.title.startsWith("Pride and Prejudice") }).isTrue()
        assertThat(byTitle.first().bookUrl.contains("lvid=")).isTrue()
        assertThat(byTitle.first().coverUrl.contains("archive.org/services/img/")).isTrue()

        val (byAuthor, _) = LibriVox.search("Austen", 1)
        println("  by author: ${byAuthor.size} 本, 第一本=${byAuthor.firstOrNull()?.title}")
        assertThat(byAuthor.any { it.author.contains("Austen") }).isTrue()
    }

    /** 查不到的词要回空而不是抛(接口回 404) */
    @Test
    fun searchNoMatchIsEmpty() {
        val (books, totalPage) = LibriVox.search("zzzqqqxxx", 1)
        assertThat(books.size).isEqualTo(0)
        assertThat(totalPage).isEqualTo(1)
    }

    /** 题材要真的在过滤,而且翻页要翻得动 */
    @Test
    fun categoryFiltersByGenreAndPaginates() {
        val tab = LibriVox.getCategoryMenus().first().tabs.first { it.title == "Westerns" }
        val category = LibriVox.getCategoryList(tab.url)
        category.list.take(3).forEach { println("  $it") }
        println("page ${category.currentPage}/${category.totalPage} next=${category.nextUrl}")
        assertThat(category.list.size).isGreaterThan(5)
        assertThat(category.totalPage).isGreaterThan(1)
        assertThat(category.nextUrl.contains("offset=20")).isTrue()
        val next = LibriVox.getCategoryList(category.nextUrl)
        println("  next page: ${next.currentPage}, ${next.list.size} 本, 第一本=${next.list.firstOrNull()?.title}")
        assertThat(next.currentPage).isEqualTo(2)
        assertThat(next.list.first().bookUrl != category.list.first().bookUrl).isTrue()
    }

    /** 每个题材名都要是接口认的(写错名字接口会 500) */
    @Test
    fun everyGenreTabIsAccepted() {
        LibriVox.getCategoryMenus().flatMap { it.tabs }.forEach { tab ->
            val list = LibriVox.getCategoryList(tab.url).list
            println("  ${tab.title}: ${list.size} 本")
            assertThat(list).isNotEmpty()
            Thread.sleep(300)
        }
    }

    /** 《Pride and Prejudice》(id 253):37 段,朗读者、作者、简介都要有,第一段真的拉一段字节 */
    @Test
    fun bookDetailAndRealAudio() {
        val detail = LibriVox.getBookDetailInfo(
            "https://librivox.org/pride-and-prejudice-by-jane-austen/?lvid=253",
            loadEpisodes = true, loadFullPages = true
        )
        println("作者=${detail.author} 朗读=${detail.artist} 章节=${detail.playList.size} 简介=${detail.intro?.take(60)}")
        detail.playList.take(3).forEach { println("  $it") }
        assertThat(detail.playList.size).isEqualTo(37)
        assertThat(detail.author.contains("Austen")).isTrue()
        assertThat(detail.artist).isNotEmpty()
        assertThat(detail.intro ?: "").isNotEmpty()
        val audioUrl = detail.playList.first().url
        val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Range", "bytes=0-4096")
        connection.connectTimeout = 25000
        connection.readTimeout = 25000
        val code = connection.responseCode
        val type = connection.contentType ?: ""
        val bytes = connection.inputStream.use { it.readBytes().size }
        println("  播放测试: HTTP $code $type ${bytes}B $audioUrl")
        connection.disconnect()
        assertThat(code in 200..299).isTrue()
        assertThat(type.startsWith("audio")).isTrue()
        assertThat(bytes > 0).isTrue()
    }
}
