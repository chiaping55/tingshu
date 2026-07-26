import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.eprendre.sources_by_cp.PtcmsTingShu
import com.github.eprendre.tingshu.utils.CategoryMenu
import org.jsoup.Connection
import org.junit.Test

/**
 * PTCMS 站群 player 页的**三种写法**都要取得到音频地址。
 *
 * 这批站共用同一个 player 模板家族，但同一台服务器上不同域名、不同线路的写法都不一样，
 * 而且都是实机抓到的真实样本(2026-07-26 存在 scratchpad/verify0726/)：
 *
 * - 幻听网默认线路：地址填在 `urlN` 变量里
 * - 起点有声网默认线路：`urlN = ''`，地址**写死在 setMedia 的字面量里**
 * - 麒麟听书：`urlN` 有值，但真后缀(带签名)在 setMedia 的拼接里
 *
 * 少了任何一条的后果都不是「播不出来」这么直观 —— 起点那条缺了会退化成
 * 每集多打 1~3 个换线路的请求(两站共用限流额度，成本是实在的)，
 * 而且一旦其它线路也改成同样写法就变成整站播不出来。
 */
class PlayerUrlShapesTest {

    private val source = object : PtcmsTingShu() {
        override val baseUrl = "https://example.com"
        override fun getSourceId() = "shapes"
        override fun getName() = "shapes"
        override fun getCategoryMenus(): List<CategoryMenu> = emptyList()
        override fun configure(connection: Connection): Connection = connection
        override fun toast(message: String) {}
    }

    /** 幻听网默认线路：地址在变量里、缺扩展名，要接 murl */
    @Test
    fun urlInVariableNeedingMurlSuffix() {
        val html = """
            murl3011157698 = '.mp3';
            url3011157698 = 'https://car-lv.kuwo.cn/289d/resource/1307392909/trackmedia/long/M5000044Xa8T1rkFfQ';
            ${'$'}(this).jPlayer("setMedia", { mp3:''+url3011157698+'' }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo(
            "https://car-lv.kuwo.cn/289d/resource/1307392909/trackmedia/long/M5000044Xa8T1rkFfQ.mp3"
        )
    }

    /**
     * 起点有声网默认线路：`url` 变量是空的，地址写死在 setMedia 字面量里(已带扩展名)。
     *
     * 这一条是审计抓到的 —— 修之前这里会回空字符串，然后白跑最多 8 条备用线路。
     */
    @Test
    fun urlOnlyInSetMediaLiteral() {
        val html = """
            murl9164132555 = '.mp3';
            url9164132555 = '';
            ${'$'}(this).jPlayer("setMedia", {
              mp3:'https://car-lv.kuwo.cn/289d/resource/1307392909/trackmedia/long/M5000044Xa8T1rkFfQ.mp3'
            }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo(
            "https://car-lv.kuwo.cn/289d/resource/1307392909/trackmedia/long/M5000044Xa8T1rkFfQ.mp3"
        )
    }

    /** 同上，但字面量缺扩展名 —— 还要接 murl(实机第二个样本就是这种) */
    @Test
    fun setMediaLiteralNeedingMurlSuffix() {
        val html = """
            murl1787233091 = '.mp3';
            url1787233091 = '';
            ${'$'}(this).jPlayer("setMedia", {
              mp3:'https://car-lv.kuwo.cn/c3d1/resource/1307392909/trackmedia/long/M500001Mc2jC4aDfpC'+url1787233091+''
            }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo(
            "https://car-lv.kuwo.cn/c3d1/resource/1307392909/trackmedia/long/M500001Mc2jC4aDfpC.mp3"
        )
    }

    /**
     * 麒麟听书：变量有值、真后缀带签名参数在 setMedia 的拼接里。
     *
     * 这一条特别要钉住：新加的「认 setMedia 字面量」那条路**不能**抢走它。
     * 麒麟的写法是 `mp3:''+urlN+'...'`，`mp3:` 后面不是 `'http`，所以不该命中。
     */
    @Test
    fun setMediaConcatenationKeepsSignedSuffix() {
        val html = """
            murl4401744813 = '.mp3';
            url4401744813 = 'https://vohwod-sign.qtfm.cn/m4a/60d43445ca8a86d5c01f5de2_19754407_24';
            ${'$'}(this).jPlayer("setMedia", {
              mp3:''+url4401744813+'.m4a?auth_key=6a663e1c-764400-0-581a574ed6f62622ecbc3aee2abeac7d'
            }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo(
            "https://vohwod-sign.qtfm.cn/m4a/60d43445ca8a86d5c01f5de2_19754407_24" +
                ".m4a?auth_key=6a663e1c-764400-0-581a574ed6f62622ecbc3aee2abeac7d"
        )
    }

    /** 两边都没有地址时要回空字符串，让上层去换线路 —— 不能拼出个只有后缀的假地址 */
    @Test
    fun emptyWhenNeitherShapeHasAnUrl() {
        val html = "murl6280764085 = '.mp3';\nurl6280764085 = '';\n" +
            "\$(this).jPlayer(\"setMedia\", { mp3:''+url6280764085+'' });"
        assertThat(source.audioUrl(html)).isEqualTo("")
    }

    /**
     * setMedia 引用的变量和带地址的变量**不同名**时，也要取到签名后缀。
     *
     * 麒麟听书实机上出现过这种页面。只认同名的话会退回接 murl，
     * 拼出 `....mp3` —— 那个地址 cdn 一律回 403，表象又是"地址取到了却播不出来"。
     */
    @Test
    fun signedSuffixFoundEvenWhenVariableNamesDiffer() {
        val html = """
            murl111 = '.mp3';
            url111 = '';
            url222 = 'https://vohwod-sign.qtfm.cn/m4a/abc_123_24';
            ${'$'}(this).jPlayer("setMedia", {
              mp3:''+url111+'.m4a?auth_key=deadbeef-1-0-cafe'
            }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo(
            "https://vohwod-sign.qtfm.cn/m4a/abc_123_24.m4a?auth_key=deadbeef-1-0-cafe"
        )
    }

    /**
     * 但 `''+urlN+''`(后缀是空的)不能被当成"找到后缀" ——
     * 幻听网就是这种写法，它要走的是接 murl 那条路。
     */
    @Test
    fun emptyConcatenationDoesNotCountAsASuffix() {
        val html = """
            murl333 = '.mp3';
            url333 = 'https://car-lv.kuwo.cn/x/y/M500';
            ${'$'}(this).jPlayer("setMedia", { mp3:''+url333+'' }).jPlayer("play");
        """.trimIndent()
        assertThat(source.audioUrl(html)).isEqualTo("https://car-lv.kuwo.cn/x/y/M500.mp3")
    }

    /**
     * 扩展名要看**路径**、不是整串结尾。
     *
     * 站方有时把完整签名地址放进变量:`.../xxx.m4a?auth_key=...d3a4`。
     * 看整串结尾会判成"没有扩展名"，于是又接一次 `.mp3`，把能播的地址改成 403。
     * 这个 bug 藏了很久，是测试真的下载音频才逼出来的。
     */
    @Test
    fun queryStringDoesNotHideTheExtension() {
        val signed = "https://cdn.example.com/m4a/abc_24.m4a?auth_key=deadbeef-1-0-cafe"
        val html = "murl77 = '.mp3';\nurl77 = '$signed';"
        assertThat(source.audioUrl(html)).isEqualTo(signed)
    }
}
