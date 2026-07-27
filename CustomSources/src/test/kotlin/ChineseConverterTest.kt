import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.eprendre.sources_by_cp2.ChineseConverter
import com.github.eprendre.tingshu.utils.Book
import org.junit.Test

private fun book(title: String) = Book("", "u", title, "a", "b")

/**
 * 繁→简转换。实测确认过:繁体关键词在爱听书/起点/有听网都搜到 0 本，
 * 换成简体才有结果，所以这个转换是「几乎必须」而不是「锦上添花」。
 */
class ChineseConverterTest {

    @Test
    fun convertsRealBookTitlesThatUsersActuallySearch() {
        // 这几个都是实测搜过、繁体归零的热门书
        assertThat(ChineseConverter.toSimplified("劍來")).isEqualTo("剑来")
        assertThat(ChineseConverter.toSimplified("聖墟")).isEqualTo("圣墟")
        assertThat(ChineseConverter.toSimplified("詭祕之主")).isEqualTo("诡秘之主")
        assertThat(ChineseConverter.toSimplified("無敵劍域")).isEqualTo("无敌剑域")
        assertThat(ChineseConverter.toSimplified("龍族")).isEqualTo("龙族")
    }

    @Test
    fun leavesSimplifiedAndAsciiUntouched() {
        // 已经是简体 —— 原样返回
        assertThat(ChineseConverter.toSimplified("剑来")).isEqualTo("剑来")
        assertThat(ChineseConverter.toSimplified("斗罗大陆")).isEqualTo("斗罗大陆")
        // 繁简同形的字不动(修、仙 两字繁简一样)
        assertThat(ChineseConverter.toSimplified("修仙")).isEqualTo("修仙")
        // 英文数字符号不动
        assertThat(ChineseConverter.toSimplified("abc 123")).isEqualTo("abc 123")
        assertThat(ChineseConverter.toSimplified("")).isEqualTo("")
    }

    @Test
    fun convertsMixedTraditionalAndOtherChars() {
        // 繁体字混英文/简体 —— 只动繁体那几个
        assertThat(ChineseConverter.toSimplified("聖墟 season2")).isEqualTo("圣墟 season2")
        assertThat(ChineseConverter.toSimplified("劍來广播剧")).isEqualTo("剑来广播剧")
    }

    // ---- restoreKeywordInTitles ----
    // app 的聚合搜索按用户输入的原词过滤书名(实测:探针只保留书名含原词的书)。
    // 繁体输入时我们转简体去抓，抓回的书名是简体、会被过滤光，所以要把书名里的简体词还原成原词。

    @Test
    fun restoresTraditionalKeywordIntoResultTitles() {
        // 用户打繁体「劍來」，站方回的书名是简体「剑来…」—— 还原成「劍來…」才过得了 app 过滤
        val books = listOf(book("剑来第八季广播剧"), book("剑来(下部)"))
        ChineseConverter.restoreKeywordInTitles(books, "劍來")
        assertThat(books[0].title).isEqualTo("劍來第八季广播剧")
        assertThat(books[1].title).isEqualTo("劍來(下部)")
    }

    @Test
    fun restoreIsNoOpForSimplifiedInput() {
        // 用户本来就打简体 —— 书名一个字都不该动
        val books = listOf(book("剑来第八季广播剧"))
        ChineseConverter.restoreKeywordInTitles(books, "剑来")
        assertThat(books[0].title).isEqualTo("剑来第八季广播剧")
    }

    @Test
    fun restoreLeavesUnrelatedTitlesUntouched() {
        // 书名不含关键词的(例如靠作者/别名命中的)不受影响
        val books = listOf(book("某某传"))
        ChineseConverter.restoreKeywordInTitles(books, "劍來")
        assertThat(books[0].title).isEqualTo("某某传")
    }
}
