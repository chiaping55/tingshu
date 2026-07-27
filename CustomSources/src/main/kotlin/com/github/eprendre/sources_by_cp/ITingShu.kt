package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlWebViewSniffExtractor
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import java.net.URLEncoder

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
            "近年的多人有声剧收录得最全。\n" +
            "打开播放页会多等几秒(音频要经内置浏览器加载)，手表等设备用不了这个源。"

    /** 音频靠 WebView 嗅探，所以这个源需要 WebView */
    override fun isWebViewNotRequired() = false

    /**
     * 写死一个较新的 UA，不用 app 设置里的。
     *
     * 当初的观察是：用旧 UA（Chrome 77）请求章节目录页会回 429、而书籍页照样放行。
     * **2026-07 复验时这个现象没有重现**（同一个目录页用 Chrome 77 也拿到 200），
     * 所以那次的 429 也可能本来就是次数型限流、UA 只是巧合。
     * 无论如何写死新 UA 是无害的防御，留着；但别再把它当成已确认的因果。
     */
    override val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 章节目录走手机站。
     *
     * 一千多集的书目录有 27 页，连着翻桌面站就会 429，而且额度用光后连别的书的
     * 列表都读不出来。实测手机站额度独立：同样连翻 8 页完全正常。
     */
    override val episodeDirectoryBaseUrl = "https://m.itingshu.net"

    override val episodeDirectoryUserAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 章节目录连着翻还是客气一点 */
    override val episodePageDelay = 400L..900L

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        // 不加过滤条件，站点播放器请求什么就嗅什么；传 null 是为了清掉别的源可能留下的过滤器
        AudioUrlWebViewSniffExtractor.setUp(false, null)
        return AudioUrlWebViewSniffExtractor
    }

    /**
     * 搜索第 1 页是 POST /novelsearch/search/result.html，body 只有 searchword；
     * **第 2 页起是普通 GET**，见 [searchPageUrl]。
     * 结果页结构与分类页相同，所以直接交给基类的 parseBooks。
     *
     * 原本这里写死「站点搜索不分页，固定返回 1 页」—— 那是错的，站方分页栏就写着「共 3 页」。
     * 后果是搜「斗罗大陆」只看得到 18 笔里的前 6 笔，续作《绝世唐门》《终极斗罗》
     * 全都搜不到，而使用者只会以为这个源没收录。
     */
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        // 站方库是简体的，繁体输入命中率归零 —— 先转简体(含第 2 页起的 searchPageUrl)
        val kw = ChineseConverter.toSimplified(keywords)
        val doc = if (page <= 1) {
            fetchPost(
                "$baseUrl/novelsearch/search/result.html",
                mapOf("searchword" to kw),
                "$baseUrl/"
            )
        } else {
            fetch(searchPageUrl(kw, page), "$baseUrl/")
        }
        return Pair(parseBooks(doc), parseTotalPage(doc, page))
    }

    /**
     * 搜索第 2 页起的地址。关键词的编码方式很特别:**URL-encode 之后把 `%` 换成 `oOo`**。
     *
     * 例如「斗罗大陆」→ `%E6%96%97...` → `oOoE6oOo96oOo97...`，
     * 完整地址是 `/search/{代号}/lastupdate/{页码}.html`(实测可用)。
     * 空格要一起处理 —— URLEncoder 会把空格编成 `+`，站方那边认的是 `oOo20`。
     */
    internal fun searchPageUrl(keywords: String, page: Int): String {
        val code = URLEncoder.encode(keywords, "UTF-8")
            .replace("+", "%20")
            .replace("%", "oOo")
        return "$baseUrl/search/$code/lastupdate/$page.html"
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
                    "ertong" to "儿童故事",
                    // 以下是站方导览上有、这里原本漏收的
                    "bjjt" to "百家讲坛",
                    "chuanji" to "人物传记",
                    "guanchangshangzhan" to "官场商战",
                    "jingji" to "网游竞技",
                    "jishi" to "经典纪实",
                    "yule" to "综艺娱乐",
                    "qita" to "其他有声"
                )
            )
        )
    }
}
