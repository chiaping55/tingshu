package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlWebViewSniffExtractor
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab

/**
 * 爱听书 https://www.itingshu.net
 *
 * 也是 PTCMS，列表/详情/章节目录的结构与起点有声网一致，所以复用 [PtcmsTingShu]，
 * 这里只覆写三处站点特有的地方：
 *
 * 1. 分类路径是 `/yousheng/{代号}/lastupdate.html`（起点系是 `/book/...`），
 *    翻页格式也不同，是 `/lastupdate/1/{页码}.html`。
 * 2. 搜索是 POST 表单，不是 GET query。
 * 3. **音频拿不到直链** —— 真实 mp3 地址由 `player/mian.js` 生成，那个文件被
 *    jsjiami.com.v7 商业混淆，纯 Jsoup 解不出来，所以只能让 WebView 跑一遍 js
 *    再嗅探它发出的音频请求。代价是这个源需要 WebView，手表之类的设备用不了。
 *
 * 反爬挑战与起点系同机制但 cookie 叫 `__51guid__`，[PtcmsGuard] 会从脚本里自己读名称，
 * 所以这边不用管。
 *
 * 之所以专门做这个源：社区 ting29 订阅里那个「爱听书」的分类路径已经过时（站方改版），
 * 打开只会显示「载入出错了」；而这个站是实测里唯一大量收录近年多人有声剧的可爬站点。
 */
object ITingShu : PtcmsTingShu() {
    override val baseUrl = "https://www.itingshu.net"

    override val titleSuffix = "有声小说"

    override fun getSourceId() = "5a1c8f24e7b3406d92fd7c05b1e8a367"

    override fun getName() = "爱听书"

    override fun getDesc() =
        "推荐指数:5星 ⭐⭐⭐⭐⭐\n" +
            "近年的多人有声剧收录得最全。音频要靠 WebView 嗅探，所以打开播放页会慢一点，" +
            "手表等没有 WebView 的设备用不了这个源。"

    /** 音频靠 WebView 嗅探，所以这个源需要 WebView */
    override fun isWebViewNotRequired() = false

    /**
     * 这个站把 UA 当风控依据：用旧 UA（例如 Chrome 77）请求章节目录页会直接回 429，
     * 而书籍页照样放行，症状看起来很像限流，实际换个较新的 UA 就好。
     * 所以这里不用 app 设置里的 UA，写死一个较新的。
     */
    override val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 章节目录连着翻还是客气一点 */
    override val episodePageDelay = 400L..900L

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        // 不加过滤条件，站点播放器请求什么就嗅什么；传 null 是为了清掉别的源可能留下的过滤器
        AudioUrlWebViewSniffExtractor.setUp(false, null)
        return AudioUrlWebViewSniffExtractor
    }

    /**
     * 搜索是 POST /novelsearch/search/result.html，body 只有 searchword。
     * 结果页结构与分类页相同，所以直接交给基类的 parseBooks。
     * 站点搜索不分页，固定返回 1 页。
     */
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val doc = fetchPost(
            "$baseUrl/novelsearch/search/result.html",
            mapOf("searchword" to keywords),
            "$baseUrl/"
        )
        return Pair(parseBooks(doc), 1)
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        fun tabs(vararg items: Pair<String, String>) = items.map { (code, name) ->
            CategoryTab(name, "$baseUrl/yousheng/$code/lastupdate.html")
        }
        return listOf(
            CategoryMenu(
                "小说",
                tabs(
                    "xuanhuan" to "玄幻修真",
                    "dushi" to "都市言情",
                    "lingyi" to "恐怖灵异",
                    "junshi" to "军事历史",
                    "kehuan" to "科幻",
                    "wenxue" to "通俗文学"
                )
            ),
            CategoryMenu(
                "其它",
                tabs(
                    "all" to "全部有声",
                    "pingshu" to "长篇评书",
                    "xiangsheng" to "相声戏曲",
                    "ertong" to "儿童故事"
                )
            )
        )
    }
}
