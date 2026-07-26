package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import java.net.URLEncoder

/**
 * 麒麟听书网 https://www.70ts.com
 *
 * PTCMS 家族，但和起点系有三处不一样，都在基类做成了开关：
 *
 * 1. **没有单独的章节目录页**（没有 a.dirurl）。书页本身就是章节第 1 页(30 集、正序)，
 *    后面的页是 /tingshu/{id}/p{N}.html，总页数看"快速选集"那个列表。
 * 2. **搜索在 /so/ 底下**，不是根目录。
 * 3. **音频地址要接 setMedia 里那段后缀**，因为签名参数 auth_key 只写在那里：
 *    `setMedia({mp3: ''+url4401744813+'.m4a?auth_key=6a663e1c-...'})`。
 *    murl 变量写的是 `.mp3`，拿它拼出来的地址 cdn 一律回 403。
 *    另外播放器地址带一次性 token，重打同一个会拿到 "Access Denied"，
 *    所以不能像起点系那样换线路重试，改成重取章节页换新 token。
 *
 * 音频托管在蜻蜓FM(qtfm.cn)，和现有几个源的 cdn 都不同，同一本书有机会是另一个演播版本。
 */
object QiLinTingShu : PtcmsTingShu() {
    override val baseUrl = "https://www.70ts.com"

    override val titleSuffix = "有声小说"

    /** 书页就是章节第 1 页，翻页走 /tingshu/{id}/p{N}.html */
    override val bookPageIsFirstEpisodePage = true

    /** 播放器 token 一次性，换线路这条路走不通 */
    override val audioFallbackSites = emptyList<Int>()

    /**
     * 音频在 vohwod-sign.qtfm.cn(签名 cdn)上，没有 `?auth_key=...` 一律 403。
     * 所以站方没给签名时要回空、照实提示，而不是交出一个注定 403 的地址。
     */
    override val audioRequiresSignedUrl = true

    /**
     * 取址冷却是分钟级的(实测隔 25 秒都还不给)，所以别重试 —— 见基类注释。
     * 正常听书一集听几十分钟才换下一集，完全碰不到这个限制；
     * 只有连着快速跳集才会撞上。
     */
    override val noAudioMessage =
        "站点限制取音频的频率了，等一两分钟再点这一集(不是源坏了，正常听不会碰到)"

    override fun getSourceId() = "e4a7d95c1b8f43629d0e5a76c2b18f3d"

    override fun getName() = "麒麟听书"

    override fun getDesc() = "书多、更新勤，音频在蜻蜓FM 上，和其它源的片源不同，" +
        "同一本书可能是另一个演播版本。\n" +
        "站点对取音频地址有风控：连着快速切集会暂时不给地址，" +
        "过一会就好，不是源坏了。"

    /** 搜索路径带 /so/ 前缀，其余参数和起点系一样 */
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val url = "$baseUrl/so/search.html?searchtype=name" +
            "&searchword=${URLEncoder.encode(keywords, "UTF-8")}&page=$page"
        val doc = fetch(url, "$baseUrl/")
        return Pair(parseBooks(doc), parseTotalPage(doc, page))
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        fun tabs(vararg items: Pair<String, String>) = items.map { (slug, name) ->
            CategoryTab(name, "$baseUrl/yousheng/$slug.html")
        }
        return listOf(
            CategoryMenu(
                "小说",
                tabs(
                    "xuanhuan" to "玄幻武侠",
                    "dushi" to "都市言情",
                    "junshi" to "军事历史",
                    "lingyi" to "恐怖灵异",
                    "tongren" to "职场有声"
                )
            ),
            CategoryMenu(
                "其它",
                tabs(
                    "bjjt" to "评书相声",
                    "ertong" to "儿童故事"
                )
            )
        )
    }
}
