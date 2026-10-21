import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.eprendre.sources_by_cp2.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.junit.Test

/**
 * 换线路取址时**挑哪一条**的规则(纯逻辑，不碰网络)。
 *
 * 起点有声网 2026-10 的真实情况：cdn 改签名制，同一集的各线路有的给
 * `…m4a?sign=…`(能播)、有的给 `….mp3`(404)，而且同一线路前后两次都可能不同。
 * 原本拿到第一个非空地址就收，常把 404 那条交出去 —— 「地址取到了却播不了」。
 *
 * 候选是 lambda，每调一个就是一次 player 请求，所以这里同时钉住「找到就停」：
 * 多打的请求是实打实的成本(起点系几个站共用限流额度)。
 */
class AudioLinePreferenceTest {

    private fun source(prefersSigned: Boolean) = object : PtcmsTingShu() {
        override val baseUrl = "https://example.com"
        override val audioPrefersSignedUrl = prefersSigned
        override fun getSourceId() = "lines"
        override fun getName() = "lines"
        override fun getCategoryMenus(): List<CategoryMenu> = emptyList()
        override fun configure(connection: Connection): Connection = connection
        override fun toast(message: String) {}
    }

    /** 每条线路被真的「请求」一次就计一次，用来验证找到就停 */
    private class Lines(vararg items: String) {
        var taken = 0
        val candidates: List<() -> String> = items.map { item -> { taken++; item } }
    }

    @Test
    fun prefersSignedLineAndStopsThere() {
        val lines = Lines("", "https://cdn/a.mp3", "https://cdn/a.m4a?sign=x&soug=y", "https://cdn/never.mp3")
        assertThat(source(true).chooseAudioUrl(lines.candidates)).isEqualTo("https://cdn/a.m4a?sign=x&soug=y")
        assertThat(lines.taken).isEqualTo(3)
    }

    @Test
    fun fallsBackToFirstUnsignedWhenNoLineIsSigned() {
        val lines = Lines("", "https://cdn/first.mp3", "https://cdn/second.mp3")
        assertThat(source(true).chooseAudioUrl(lines.candidates)).isEqualTo("https://cdn/first.mp3")
        assertThat(lines.taken).isEqualTo(3)
    }

    /** 没开偏好的站行为不变：第一个非空就收，后面的线路一个都不打 */
    @Test
    fun defaultTakesFirstNonEmptyAndStops() {
        val lines = Lines("", "https://cdn/a.mp3", "https://cdn/a.m4a?sign=x")
        assertThat(source(false).chooseAudioUrl(lines.candidates)).isEqualTo("https://cdn/a.mp3")
        assertThat(lines.taken).isEqualTo(2)
    }

    @Test
    fun emptyWhenEveryLineIsEmpty() {
        assertThat(source(true).chooseAudioUrl(Lines("", "").candidates)).isEqualTo("")
        assertThat(source(false).chooseAudioUrl(emptyList())).isEqualTo("")
    }
}
