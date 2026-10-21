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
 * 博看有声 —— 博看网(期刊数字发行商)的有声库。
 *
 * 内容和网文有声站完全不重叠:名著解读、商业财经、心理、育儿、历史、儿童文学、评书。
 * 全部走公开 JSON 接口,音频是 m4a 直链,不需要 WebView。
 *
 * 从原作者停维护的 sources_by_eprendre 移植并精简(2026-10-20 逐个接口验过):
 * - 只保留「图书」(voice/book)这一类。原来的「专辑」(voice/album)接口分类全空、搜索回 0,
 *   已经没有内容;「音乐戏曲」(1317)接口回 null,也去掉。
 * - 章节每页改取 100 条(接口允许),200 集的书从 11 页变 3 页。
 * - 搜索接上繁→简,再把书名里的关键词还原成原词,好通过 app 聚合搜索的过滤(见 [ChineseConverter])。
 *
 * 逻辑全放在这个基类里,正式源 [BoKanYouSheng] 只是个空壳 —— 这样测试能用「真的源」
 * 跑,只换掉 app 的 notifyLoading(那是个 stub,JVM 上会抛),不用再维护一份会漂移的复本。
 */
abstract class BoKanYouShengBase : TingShu() {

    /** app 里显示「正在加载章节列表 x/y」;测试覆写成打印 */
    protected open fun notifyLoading(pageInfo: String?) = notifyLoadingEpisodes(pageInfo)

    // 不沿用 eprendre 版的 sourceId:使用者若还订着那包,同 id 会被 app 当成同一个源去重
    override fun getSourceId() = "eb75e0926885443783b0402afcf060db"

    override fun getUrl() = "https://voicewk.bookan.com.cn/25303/index"

    override fun getName() = "博看有声"

    override fun getDesc() = "推荐指数:4星 ⭐⭐⭐⭐\n" +
        "期刊社的有声库:名著解读、商业财经、心理、育儿、历史、儿童文学,和网文有声完全不重叠。\n" +
        "音频直链不用内置浏览器,手表也能用。"

    override fun isWebViewNotRequired() = true

    /** 章节接口分页(每页 100 条),让 app 走分页加载的优化逻辑 */
    override fun isMultipleEpisodePages() = true

    override fun getAudioUrlExtractor(): AudioUrlExtractor = AudioUrlDirectExtractor

    private fun getJson(url: String): JSONObject =
        Fuel.get(url).timeout(10000).timeoutRead(15000).responseJson().third.get().obj()

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return try {
            val kw = URLEncoder.encode(ChineseConverter.toSimplified(keywords), "utf-8")
            val data = getJson("$SEARCH_API?instanceId=$INSTANCE_ID&keyword=$kw&pageNum=$page&limitNum=20")
                .getJSONObject("data")
            val books = parseBooks(data.optJSONArray("list"))
            ChineseConverter.restoreKeywordInTitles(books, keywords)
            Pair(books, maxOf(data.optInt("last_page", page), 1))
        } catch (e: Exception) {
            Pair(emptyList(), 1)
        }
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        fun tab(name: String, categoryId: Int) = CategoryTab(name, listUrl(categoryId, 1))
        return listOf(
            CategoryMenu(
                "图书",
                listOf(
                    tab("经典必读", 1314),
                    tab("国学经典", 1320),
                    tab("文学文艺", 1306),
                    tab("历史小说", 1312),
                    tab("故事会", 1303),
                    tab("相声评书", 1319),
                    tab("商业财经", 1315),
                    tab("心理哲学", 1310),
                    tab("青春励志", 1307),
                    tab("科技科普", 1313),
                    tab("社会观察", 1318),
                    tab("红色岁月", 1316),
                    tab("育儿心经", 1309),
                    tab("家庭健康", 1311),
                    tab("少年读物", 1305),
                    tab("儿童文学", 1304)
                )
            )
        )
    }

    private fun listUrl(categoryId: Int, page: Int) =
        "$API/book/list?instance_id=$INSTANCE_ID&page=$page&category_id=$categoryId&num=24"

    override fun getCategoryList(url: String): Category {
        val data = getJson(url).getJSONObject("data")
        val currentPage = data.optInt("current_page", 1)
        val totalPage = maxOf(data.optInt("last_page", currentPage), currentPage)
        val categoryId = Regex("""category_id=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 0
        val nextUrl = if (currentPage < totalPage) listUrl(categoryId, currentPage + 1) else ""
        return Category(parseBooks(data.optJSONArray("list")), currentPage, totalPage, url, nextUrl)
    }

    /** 列表项:id / name / cover / intro / press(出版社) / total(集数) */
    private fun parseBooks(array: JSONArray?): List<Book> {
        val list = ArrayList<Book>()
        if (array == null) return list
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.optLong("id")
            if (id == 0L) continue
            list.add(
                Book(item.optString("cover"), bookUrl(id), item.optString("name"), "", item.optString("press")).apply {
                    intro = item.optString("intro")
                    status = "共 ${item.optInt("total")} 集"
                    sourceId = getSourceId()
                }
            )
        }
        return list
    }

    /** 书的地址用 info 接口的网址表示:是个真网址、也能从里面取回 id */
    private fun bookUrl(id: Long) = "$API/album/info?album_id=$id"

    private fun bookId(bookUrl: String): String =
        Regex("""album_id=(\d+)""").find(bookUrl)?.groupValues?.get(1) ?: bookUrl.trim()

    private val pageList = ArrayList<Int>()

    override fun reset() {
        pageList.clear()
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val id = bookId(bookUrl)
        val episodes = ArrayList<Episode>()
        var intro = ""
        var press = ""
        var cover = ""
        if (loadEpisodes) {
            try {
                val info = getJson("$API/album/info?album_id=$id").getJSONObject("data")
                intro = info.optString("intro")
                press = info.optString("press")
                cover = info.optString("cover")
            } catch (e: Exception) {
                // 简介拿不到不影响听,章节照常取
            }
            val first = getJson(unitsUrl(id, 1)).getJSONObject("data")
            val totalPage = first.optInt("last_page", 1)
            episodes.addAll(parseEpisodes(first.optJSONArray("list")))
            if (loadFullPages && totalPage > 1) {
                pageList.addAll(2..totalPage)
                while (pageList.isNotEmpty()) {
                    val page = pageList.removeAt(0)
                    notifyLoading("$page / $totalPage")
                    episodes.addAll(parseEpisodes(getJson(unitsUrl(id, page)).getJSONObject("data").optJSONArray("list")))
                    Thread.sleep(Random.nextLong(100, 400))
                }
                notifyLoading(null)
            }
        }
        return BookDetail(episodes, intro, press, "", 0, cover)
    }

    private fun unitsUrl(id: String, page: Int) =
        "$API/album/units?album_id=$id&page=$page&num=$UNITS_PER_PAGE&order=1"

    /** 章节项:title / file(m4a 直链) */
    private fun parseEpisodes(array: JSONArray?): List<Episode> {
        val list = ArrayList<Episode>()
        if (array == null) return list
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val file = item.optString("file")
            if (file.isEmpty()) continue
            list.add(Episode(item.optString("title"), file))
        }
        return list
    }

    companion object {
        const val INSTANCE_ID = "25304"
        private const val API = "https://api.bookan.com.cn/voice"
        private const val SEARCH_API = "https://es.bookan.com.cn/api/v3/voice/book"
        /** 接口接受的每页上限(实测 100 正常回 100 条) */
        const val UNITS_PER_PAGE = 100
    }
}

object BoKanYouSheng : BoKanYouShengBase()
