package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import com.github.eprendre.tingshu.sources.AudioUrlDirectExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import com.github.eprendre.tingshu.utils.Episode
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.json.responseJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

/**
 * 云图有声 —— 云图数字有声图书馆(图书馆系统采购的那种有声库):名著、历史、少儿、职场、养生,
 * 和网文有声站完全不同的一批内容。全部走公开 JSON 接口,音频是七牛上的 mp3 直链,不需要 WebView。
 *
 * 从原作者停维护的 sources_by_eprendre 移植。移植的直接原因是使用者反映「每本书都只有 200 集」:
 * 2026-11-09 实测**接口本身没有锁** —— 章节接口第 2 页照给、pageSize=500 一次也给 ——
 * 是那版客户端只抓第一页 200 条、后面几页要靠 app 第二次进播放页才补。这里改成一页 500,
 * 大多数书一次拿全;更长的书再翻页,并照 app 的分页加载约定来。
 *
 * 接口(2026-11-09 逐个验过,https 可用):
 *   搜索  /book/search/wechat:{id}/{关键词}?pageSize=20&pageNum=      -> data{totalPage, list[]}
 *   分类  /book/getBookListByType/wechat:{id}/{typeId}?pageNum=&pageSize=20 -> data{pageNumber, totalPage, list[]}
 *         ⚠ 不少分类回 totalPage=0 却给满 20 本,这时按「当前页+1」继续翻,别当成只有一页。
 *   详情  /book/getBook/wechat:{id}/{bookId}                            -> data{cover, anchorName, authorName, summary, chapters, finished}
 *   章节  /book/getChapters/wechat:{id}/{bookId}/true/asc?pageSize=500&pageNum= -> data.pageQuery{totalPage, totalRow, list[{name, audioUrl}]}
 *         ⚠ totalPage 也不可信(901 集、一页 500 时回 1),总页数拿 totalRow 自己算。
 * 列表项字段:bookId / bookName / cover / anchorName(演播) / authorName / chapters / summary / finished。
 *
 * 逻辑全放基类,正式源 [YunTuYouSheng] 是空壳 —— 测试用真逻辑跑,只换掉 app 的 notifyLoading。
 */
abstract class YunTuYouShengBase : TingShu() {

    /** app 里显示「正在加载章节列表 x/y」;测试覆写成打印 */
    protected open fun notifyLoading(pageInfo: String?) = notifyLoadingEpisodes(pageInfo)

    // 不沿用 eprendre 版的 sourceId:使用者若还订着那包,同 id 会被 app 当成同一个源去重
    override fun getSourceId() = "c4a7e1f93b5d4e8a9f0b2c6d8e1a3f57"

    override fun getUrl() = "https://yuntuwechat.yuntuys.com/home"

    override fun getName() = "云图有声"

    override fun getDesc() = "推荐指数:4星 ⭐⭐⭐⭐\n" +
        "数字图书馆的有声库:名著、历史、少儿、职场、养生,和网文有声完全不重叠。\n" +
        "章节一次拿全(不再只有 200 集)。音频直链不用内置浏览器,手表也能用。"

    override fun isWebViewNotRequired() = true

    /** 章节一页 500,超过的书再翻页 */
    override fun isMultipleEpisodePages() = true

    /** 章节地址就是七牛上的 mp3 */
    override fun getAudioUrlExtractor(): AudioUrlExtractor = AudioUrlDirectExtractor

    private fun getJson(url: String): JSONObject =
        Fuel.get(url).timeout(15000).timeoutRead(20000).responseJson().third.get().obj()

    private fun bookUrl(id: String) = "$API/getBook/wechat:$WECHAT_ID/$id"

    private fun bookId(bookUrl: String) = Regex("""/getBook/wechat:[^/]+/(\d+)""").find(bookUrl)?.groupValues?.get(1)
        ?: Regex("""^\d+$""").find(bookUrl.trim())?.value // 旧收藏可能存的是裸 id

    private fun categoryUrl(typeId: String, page: Int) =
        "$API/getBookListByType/wechat:$WECHAT_ID/$typeId?pageNum=$page&pageSize=$PAGE_SIZE"

    private fun episodesUrl(id: String, page: Int) =
        "$API/getChapters/wechat:$WECHAT_ID/$id/true/asc?pageSize=$EPISODE_PAGE_SIZE&pageNum=$page"

    private fun toBook(item: JSONObject): Book? {
        val id = item.optString("bookId")
        if (id.isEmpty()) return null
        val chapters = item.optInt("chapters")
        return Book(item.optString("cover"), bookUrl(id), item.optString("bookName"),
            item.optString("authorName"), item.optString("anchorName")).apply {
            intro = item.optString("summary").trim()
            status = (if (chapters > 0) "共 $chapters 集" else "") +
                (if (item.optString("finished") == "Y") " · 已完结" else "")
            isCompleted = item.optString("finished") == "Y"
            sourceId = getSourceId()
        }
    }

    private fun toBooks(array: JSONArray?): List<Book> {
        val list = ArrayList<Book>()
        if (array == null) return list
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { toBook(it) }?.let { list.add(it) }
        }
        return list
    }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return try {
            val kw = URLEncoder.encode(ChineseConverter.toSimplified(keywords), "utf-8")
            val data = getJson("$API/search/wechat:$WECHAT_ID/$kw?pageSize=$PAGE_SIZE&pageNum=$page")
                .optJSONObject("data") ?: JSONObject()
            val books = toBooks(data.optJSONArray("list"))
            ChineseConverter.restoreKeywordInTitles(books, keywords)
            Pair(books, maxOf(data.optInt("totalPage", page), page))
        } catch (e: Exception) {
            Pair(emptyList(), 1)
        }
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        fun menu(title: String, vararg tabs: Pair<String, String>) =
            CategoryMenu(title, tabs.map { (name, typeId) -> CategoryTab(name, categoryUrl(typeId, 1)) })
        return listOf(
            menu("特色", "四史专栏" to "585", "远读重洋" to "571", "儿童防疫" to "122", "听见真知" to "124",
                "豆瓣高分" to "125", "广播剧" to "126", "影视同期" to "127", "云图学院" to "421"),
            menu("经典文学", "世界名著" to "131", "中国文学" to "132", "国学经典" to "134", "外国文学" to "133",
                "诗词散文" to "135", "人物传记" to "136"),
            menu("畅销小说", "国风古韵" to "137", "青春校园" to "138", "科学幻想" to "139", "官场商战" to "140",
                "军事谍战" to "141", "悬疑推理" to "142", "现代都市" to "143", "怪奇物语" to "144", "侠义江湖" to "335"),
            menu("职场财经", "创业学院" to "148", "职场指南" to "149", "商界大咖" to "150", "金融理财" to "151"),
            menu("少儿教育", "儿童文学" to "152", "童话名著" to "153", "国学启蒙" to "154", "儿歌故事" to "155",
                "百科知识" to "156", "亲子教育" to "157"),
            menu("文化艺术", "民俗文化" to "161", "世界之窗" to "163", "哲学思想" to "164"),
            menu("历史风云", "古代历史" to "165", "近现代史" to "166", "世界历史" to "167", "传奇史话" to "168"),
            menu("军事文学", "军事纪实" to "173", "战争烽火" to "174"),
            menu("军政人物", "革命先驱" to "176", "政治领袖" to "177"),
            menu("健康养生", "养生保健" to "181", "养颜减肥" to "182", "食疗课堂" to "183", "孕产育儿" to "184"),
            menu("情感生活", "心理健康" to "185", "婚恋家庭" to "186", "心灵励志" to "187", "生活百科" to "188", "娱乐休闲" to "189")
        )
    }

    override fun getCategoryList(url: String): Category {
        val typeId = Regex("""/getBookListByType/wechat:[^/]+/(\d+)""").find(url)?.groupValues?.get(1) ?: "0"
        val currentPage = Regex("""[?&]pageNum=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 1
        val data = getJson(url).optJSONObject("data") ?: JSONObject()
        val books = toBooks(data.optJSONArray("list"))
        // 接口对不少分类回 totalPage=0 却给满一页:满页就当还有下一页(app 约定允许「当前页+1」)
        val reported = data.optInt("totalPage", 0)
        val totalPage = when {
            reported >= currentPage -> reported
            books.size >= PAGE_SIZE -> currentPage + 1
            else -> currentPage
        }
        val nextUrl = if (currentPage < totalPage) categoryUrl(typeId, currentPage + 1) else ""
        return Category(books, currentPage, totalPage, url, nextUrl)
    }

    private val pageList = ArrayList<Int>()

    override fun reset() {
        pageList.clear()
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val id = bookId(bookUrl) ?: return BookDetail(emptyList())
        val book = try {
            getJson("$API/getBook/wechat:$WECHAT_ID/$id").optJSONObject("data")?.let { toBook(it) }
        } catch (e: Exception) {
            null
        }
        val episodes = ArrayList<Episode>()
        var count = 0
        if (loadEpisodes) {
            val first = pageQuery(id, 1)
            count = first.optInt("totalRow")
            // 接口的 totalPage 不可信(901 集、一页 500 时回 1;翻页时还会回 0),
            // 总页数自己用 totalRow 算 —— 这正是「只有 200 集」那类 bug 的温床
            val byCount = if (count > 0) (count + EPISODE_PAGE_SIZE - 1) / EPISODE_PAGE_SIZE else 1
            val totalPage = maxOf(first.optInt("totalPage", 1), byCount, 1)
            episodes.addAll(parseEpisodes(first))
            if (loadFullPages && totalPage > 1) {
                pageList.addAll(2..totalPage)
                while (pageList.isNotEmpty()) {
                    val page = pageList.removeAt(0)
                    notifyLoading("$page / $totalPage")
                    episodes.addAll(parseEpisodes(pageQuery(id, page)))
                    Thread.sleep(Random.nextLong(100, 400))
                }
                notifyLoading(null)
            }
        }
        return BookDetail(episodes, book?.intro ?: "", book?.artist ?: "", book?.author ?: "",
            if (count > 0) count else episodes.size, book?.coverUrl ?: "")
    }

    private fun pageQuery(id: String, page: Int): JSONObject =
        getJson(episodesUrl(id, page)).optJSONObject("data")?.optJSONObject("pageQuery") ?: JSONObject()

    private fun parseEpisodes(pageQuery: JSONObject): List<Episode> {
        val list = ArrayList<Episode>()
        val array = pageQuery.optJSONArray("list") ?: return list
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val audio = item.optString("audioUrl")
            if (audio.isEmpty()) continue
            list.add(Episode(item.optString("name"), audio))
        }
        return list
    }

    companion object {
        /** 站方微信端用的馆号,原作者的包就是这一个,多年没变 */
        const val WECHAT_ID = "07955551-706c-4259-9aa0-db4627dfca57"
        const val API = "https://open-service.yuntuys.com/api/w_ys/book"
        private const val PAGE_SIZE = 20
        /** 接口实测一页给到 500;原作者那版是 200 且只抓第一页 */
        const val EPISODE_PAGE_SIZE = 500
    }
}

object YunTuYouSheng : YunTuYouShengBase()
