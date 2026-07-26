import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp.DedeCmsTingShu
import org.jsoup.Connection
import org.junit.Assume
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

    private data class Rate(val name: String, val playable: Int, val tried: Int)

    private fun measure(name: String, base: String, cat: String, book: String): Rate {
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
        return Rate(name, playable, tried)
    }

    @Test
    fun measurePlayRates() {
        // 平常不跑：请求量大(每本书要抓详情页+播放页+探测音频)，会打扰站方。
        // 这个开关就是类注解里写的那个 —— 之前注解写了 -Dplayrate=1 但代码里没实现，
        // 而 @Ignore 也漏掉了，结果每次 gradlew test 都会全跑，还因为零断言永远不会红。
        Assume.assumeTrue(
            "要量可播率请加 -Dplayrate=1",
            System.getProperty("playrate") != null
        )

        val rates = listOf(
            measure("听书吧", "https://www.ting8.cc", "books", "mp3"),
            measure("乐听吧", "https://www.leting8.com", "le", "so")
        )

        // 必须有断言，否则这个测试永远绿、量出 0/10 也看不见。
        // 门槛设在三成：低于这个数不是「站方内容失效」的正常损耗，
        // 而是取址逻辑坏了(Referer 那次就把可播率压到了三成以下)。
        for (rate in rates) {
            println("${rate.name}: ${rate.playable}/${rate.tried}")
            assertThat(rate.tried).isGreaterThan(0)
            assertThat(rate.playable * 10 >= rate.tried * 3).isTrue()
        }
    }
}
