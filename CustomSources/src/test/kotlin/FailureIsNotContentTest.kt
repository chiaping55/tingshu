import assertk.assertThat
import assertk.assertions.isTrue
import com.github.eprendre.sources_by_cp2.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.junit.Test
import java.io.IOException

/**
 * 「故障不能伪装成内容」的回归测试。
 *
 * 这个专案在同一个坑里踩过三次，症状每次都一样：站点其实是出错/限流，
 * 但代码把错误页当正常页面解析，结果交给 app 的是一本「没有章节的书」或「搜索没结果」，
 * 使用者完全看不出发生了什么，维护者也往错的方向查。
 *
 * 三次分别是：
 * 1. 429 不抛出，限流变成「这本书没有章节」
 * 2. `ignoreHttpErrors(true)` 让 404/500/502 照旧交回去 —— 而且因为不抛异常，
 *    「手机站失败退回桌面站」那条退路(靠 catch 启动)根本不会触发
 * 3. HTTP 200 的「访问过于频繁」提示页只在翻章节时检查，书籍页/搜索完全没防护
 *
 * 所以这些路径全部要有测试钉住：**宁可抛异常让 app 显示重试，也不要给出空内容。**
 * 这里不发真实请求 —— 用本地 http server 模拟各种故障，所以站点限流时也验得准。
 */
class FailureIsNotContentTest {

    private class FakeServer(private val handler: (String) -> Pair<Int, String>) {
        private val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0
        )
        val baseUrl: String

        init {
            server.createContext("/") { exchange ->
                val (status, body) = handler(exchange.requestURI.path)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        fun stop() = server.stop(0)
    }

    private fun source(base: String) = object : PtcmsTingShu() {
        override val baseUrl = base
        override fun getSourceId() = "failure-test"
        override fun getName() = "failure-test"
        override fun getCategoryMenus(): List<CategoryMenu> = emptyList()
        // 不走 app 的 config()(stub)，也不要跟着系统 UA
        override fun configure(connection: Connection): Connection =
            connection.timeout(5000).ignoreContentType(true)

        override fun notifyLoading(pageInfo: String?) {}
        override fun toast(message: String) {}
    }

    private fun assertThrows(what: String, block: () -> Unit) {
        var thrown: Exception? = null
        try {
            block()
        } catch (e: Exception) {
            thrown = e
        }
        println("$what → ${thrown?.javaClass?.simpleName}: ${thrown?.message}")
        assertThat(thrown != null).isTrue()
    }

    /**
     * 404 / 500 / 502 都必须抛出。
     *
     * 特别是 502：麒麟听书那次整站 PHP 后端挂掉就是回 502，
     * 而当时的代码会把 nginx 的错误页当书籍页解析。
     */
    @Test
    fun httpErrorsThrowInsteadOfReturningErrorPage() {
        for (status in listOf(404, 500, 502, 403)) {
            val server = FakeServer { _ ->
                status to "<html><body><h1>$status Error</h1></body></html>"
            }
            try {
                assertThrows("HTTP $status") {
                    source(server.baseUrl).getBookDetailInfo(
                        "${server.baseUrl}/book/1.html", loadEpisodes = true, loadFullPages = false
                    )
                }
            } finally {
                server.stop()
            }
        }
    }

    /**
     * HTTP 200 的「访问过于频繁」提示页也要抛 —— 这一种最阴，因为状态码是正常的。
     */
    @Test
    fun throttleInterstitialWithStatus200Throws() {
        val server = FakeServer { _ ->
            200 to "<html><body>访问过于频繁，请稍后再试</body></html>"
        }
        try {
            assertThrows("200 + 访问过于频繁") {
                source(server.baseUrl).getBookDetailInfo(
                    "${server.baseUrl}/book/1.html", loadEpisodes = true, loadFullPages = false
                )
            }
        } finally {
            server.stop()
        }
    }

    /**
     * 但一本简介里恰好写着「稍后再试」的书**不能**被误判成限流。
     *
     * 这就是为什么判断要加长度上限：提示页只有几百字，真实书籍页有几千字。
     * 少了这条，正常内容会被当成故障，那是把 bug 换了个方向而不是修掉。
     */
    @Test
    fun realBookPageMentioningThePhraseIsNotTreatedAsThrottled() {
        val filler = "这是一本很长的书的简介。".repeat(80)// 远超长度上限
        val server = FakeServer { _ ->
            200 to """
                <html><body>
                  <div class="book-img"><img src="/cover.jpg"></div>
                  <div class="book-des">$filler 书中人物说了一句「稍后再试」。</div>
                  <div class="book-info"><dd>演播：某人</dd></div>
                  <div id="playlist"><ul><li><a href="/play/1.html">第1集</a></li></ul></div>
                </body></html>
            """.trimIndent()
        }
        try {
            val detail = source(server.baseUrl).getBookDetailInfo(
                "${server.baseUrl}/book/1.html", loadEpisodes = true, loadFullPages = false
            )
            println("章节=${detail.playList.size} 演播=${detail.artist}")
            assertThat(detail.playList.isNotEmpty()).isTrue()
        } finally {
            server.stop()
        }
    }

    /**
     * 429 用尽退避后要抛，而且**不能**在最后一次尝试之后还白等一轮。
     */
    @Test
    fun rateLimitThrowsAfterBackoff() {
        val server = FakeServer { _ -> 429 to "too many requests" }
        try {
            val started = System.currentTimeMillis()
            assertThrows("429 × 退避用尽") {
                source(server.baseUrl).getBookDetailInfo(
                    "${server.baseUrl}/book/1.html", loadEpisodes = true, loadFullPages = false
                )
            }
            val elapsed = System.currentTimeMillis() - started
            println("总耗时 ${elapsed}ms")
            // 退避是 3s + 6s + 12s = 21s。最后一次失败后不该再睡第四轮(那会变成 45s)
            assertThat(elapsed < 30_000).isTrue()
        } finally {
            server.stop()
        }
    }

    /**
     * 搜索走的是 POST，同样要有防护 —— 之前只有 fetch() 那条路被保护到。
     */
    @Test
    fun postSearchAlsoDetectsThrottle() {
        val server = FakeServer { _ ->
            200 to "<html><body>访问过于频繁</body></html>"
        }
        try {
            val src = object : PtcmsTingShu() {
                override val baseUrl = server.baseUrl
                override fun getSourceId() = "failure-test"
                override fun getName() = "failure-test"
                override fun getCategoryMenus(): List<CategoryMenu> = emptyList()
                override fun configure(connection: Connection): Connection =
                    connection.timeout(5000).ignoreContentType(true)
                override fun toast(message: String) {}
                fun searchByPost() = fetchPost("$baseUrl/s", mapOf("k" to "v"), "$baseUrl/")
            }
            assertThrows("POST + 访问过于频繁") { src.searchByPost() }
        } finally {
            server.stop()
        }
    }

    /** 确认 Jsoup 真的能连本地 server —— 上面几个测试的前提 */
    @Test
    fun fakeServerWorks() {
        val server = FakeServer { _ -> 200 to "<html><body>ok</body></html>" }
        try {
            val doc = Jsoup.connect(server.baseUrl + "/x").timeout(5000).get()
            assertThat(doc.body().text().contains("ok")).isTrue()
        } finally {
            server.stop()
        }
    }
}
