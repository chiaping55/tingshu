import com.github.eprendre.sources_by_cp.DedeCmsTingShu
import org.jsoup.Connection
import org.junit.Ignore
import org.junit.Test

/**
 * 量测两个 DedeCMS 站的实测可播率。
 *
 * 请求量大(每本书要抓详情页+播放页+探测音频)，所以**平常不跑**，
 * 只在改了取址逻辑、想知道到底提升多少时手动跑：
 *
 *     ./gradlew test --tests DedeCmsPlayRateTest -Dplayrate=1
 *
 * 数字要写进源的 getDesc 时以这里的输出为准 —— 别凭感觉估。
 */
class DedeCmsPlayRateTest {

    private fun source(base: String, cat: String, book: String) =
        object : DedeCmsTingShu() {
            override val baseUrl = base
            override val categoryPath = cat
            override val bookPath = book
            override fun getSourceId() = "playrate-probe"
            override fun getName() = base
            override fun configure(connection: Connection): Connection =
                connection.testConfig(true)
        }

    private fun measure(name: String, base: String, cat: String, book: String) {
        val src = source(base, cat, book)
        val bookUrls = LinkedHashSet<String>()
        for (categoryId in listOf("1", "13", "3")) {
            try {
                val list = src.getCategoryList("$base/$cat/$categoryId.html")
                list.list.take(4).forEach { bookUrls.add(it.bookUrl) }
            } catch (e: Exception) {
                println("  分类 $categoryId 取不到: ${e.javaClass.simpleName}")
            }
            Thread.sleep(1200)
        }
        var playable = 0
        var tried = 0
        for (bookUrl in bookUrls.take(10)) {
            try {
                val detail = src.getBookDetailInfo(bookUrl, loadEpisodes = true, loadFullPages = false)
                val first = detail.playList.firstOrNull() ?: continue
                tried++
                Thread.sleep(1000)
                val html = org.jsoup.Jsoup.connect(first.url).testConfig(true).get().html()
                val audio = src.audioUrl(html)
                val ok = audio.isNotEmpty() && src.isPlayableForTest(audio)
                if (ok) playable++
                println("  ${if (ok) "▶" else "✗"} ${first.title.take(20)}  ${audio.take(80)}")
            } catch (e: Exception) {
                tried++
                println("  ✗ ${bookUrl.takeLast(20)} ${e.javaClass.simpleName}")
            }
            Thread.sleep(1500)
        }
        println("=== $name 可播 $playable/$tried ===\n")
    }

    @Test
    fun measurePlayRates() {
        measure("听书吧", "https://www.ting8.cc", "books", "mp3")
        measure("乐听吧", "https://www.leting8.com", "le", "so")
    }
}
