package com.github.eprendre.sources_by_cp2

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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * LibriVox https://librivox.org —— 志愿者朗读的公版英文有声书,名著基本都有。
 *
 * 走官方 JSON 接口(librivox.org/api/feed/audiobooks),音频是 archive.org 上的 mp3 直链,
 * 不需要 WebView;境内连 archive.org 可能不通。
 *
 * 和原作者 sources_by_eprendre 版本的差别:那版爬的是网站搜索页的 HTML(get_results /
 * advanced_search),2026-10 实测那两个端点已经只回空白;JSON 接口反而一直稳定。
 *
 * 接口的几个脾气(2026-10-20 实测):
 * - `title=` 是**整串精确匹配**,要前缀匹配得写 `title=^关键词`(不分大小写);
 *   `author=` 按姓名匹配。所以搜索同时查「书名开头」和「作者」两路再合并。
 * - 没有总数/总页数,只有 limit/offset —— 回满一页就当还有下一页(app 文档允许这样)。
 * - 没有封面字段;封面用 archive.org 的缩略图服务 `archive.org/services/img/{标识}`,
 *   标识从 url_iarchive 最后一段取 —— 而 url_iarchive **只在 extended=1 时才给**。
 *   全量 extended 会把每本书的全部章节都带上(20 本约 190KB),所以列表查询再加
 *   `fields={...}` 只要需要的栏位,同样 20 本只剩 2.5KB(实测)。详情才取整本 extended。
 * - 查不到时回 HTTP 404 + {"error":...},Fuel 会抛,统一当空结果。
 * - `genre=` 要用完整题材名(如 Fantasy Fiction),缩写或 Non-fiction 这种会 500。
 * - 一本书的全部章节 `id=<id>&extended=1` 一次给齐,不用翻页。
 */
object LibriVox : TingShu() {

    private const val API = "https://librivox.org/api/feed/audiobooks/"
    private const val PAGE_SIZE = 20

    /** 列表查询只取这些栏位(见类注释):够组一个 Book,又不会把章节全拖下来 */
    private const val LIST_FIELDS =
        "extended=1&fields=%7Bid,title,url_iarchive,url_librivox,authors,totaltime,language,description%7D"

    override fun getSourceId() = "67fc11d726294eb3a4ec8e3cba633862"

    override fun getUrl() = "https://librivox.org"

    override fun getName() = "LibriVox"

    override fun getDesc() = "推荐指数:4星 ⭐⭐⭐⭐\n" +
        "全球最大的公版英文有声书库,志愿者朗读,经典名著基本都有。\n" +
        "用英文搜索:按书名开头或作者。音频在 archive.org,境内可能连不上;手表也能用。"

    override fun isWebViewNotRequired() = true

    override fun getAudioUrlExtractor(): AudioUrlExtractor = AudioUrlDirectExtractor

    /** 查接口,查不到(404)或出错一律当空 */
    private fun query(params: String): List<JSONObject> {
        return try {
            val obj = Fuel.get("$API?$params&format=json")
                .timeout(10000).timeoutRead(15000)
                .responseJson().third.get().obj()
            val books = obj.optJSONArray("books") ?: return emptyList()
            (0 until books.length()).map { books.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val kw = URLEncoder.encode(keywords.trim(), "utf-8")
        val offset = (page - 1) * PAGE_SIZE
        val byTitle = query("title=%5E$kw&limit=$PAGE_SIZE&offset=$offset&$LIST_FIELDS")
        val books = LinkedHashMap<String, Book>()
        byTitle.map(::toBook).forEach { books[it.bookUrl] = it }
        // 作者那一路只在第一页并进来:它没有稳定的翻页语义,翻页只翻书名那一路
        if (page == 1) {
            query("author=$kw&limit=$PAGE_SIZE&$LIST_FIELDS").map(::toBook).forEach { books.putIfAbsent(it.bookUrl, it) }
        }
        val totalPage = if (byTitle.size == PAGE_SIZE) page + 1 else page
        return Pair(books.values.toList(), totalPage)
    }

    private fun toBook(obj: JSONObject): Book {
        val id = obj.optString("id")
        val authors = obj.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).joinToString(", ") { i ->
                val a = arr.getJSONObject(i)
                "${a.optString("first_name")} ${a.optString("last_name")}".trim()
            }
        } ?: ""
        val identifier = obj.optString("url_iarchive").trimEnd('/').substringAfterLast('/')
        val cover = if (identifier.isNotEmpty()) "https://archive.org/services/img/$identifier" else ""
        val page = obj.optString("url_librivox").ifEmpty { "https://librivox.org/" }
        return Book(cover, "$page?lvid=$id", obj.optString("title"), authors, "").apply {
            intro = Jsoup.parse(obj.optString("description")).text()
            status = listOf(obj.optString("totaltime"), obj.optString("language"))
                .filter { it.isNotEmpty() }.joinToString(" · ")
            sourceId = getSourceId()
        }
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        fun tab(genre: String) = CategoryTab(genre, genreUrl(genre, 0))
        return listOf(
            CategoryMenu(
                "Fiction",
                listOf(
                    "General Fiction", "Literary Fiction", "Historical Fiction", "Action & Adventure Fiction",
                    "Crime & Mystery Fiction", "Detective Fiction", "Science Fiction", "Fantasy Fiction",
                    "Horror & Supernatural Fiction", "Romance", "Humorous Fiction", "Short Stories",
                    "War & Military Fiction", "Westerns", "Nautical & Marine Fiction",
                    "Myths, Legends & Fairy Tales", "Epistolary Fiction", "Satire", "Plays", "Poetry",
                    "Children's Fiction"
                ).map(::tab)
            ),
            CategoryMenu(
                "Non-fiction",
                listOf(
                    "History", "Biography & Autobiography", "Memoirs", "Philosophy", "Psychology", "Religion",
                    "Science", "Nature", "Animals & Nature", "Travel & Geography", "Business & Economics",
                    "Political Science", "Self-Help", "Essays & Short Works", "Classics (Greek & Latin Antiquity)",
                    "Family Life", "Medical", "Children's Non-fiction"
                ).map(::tab)
            )
        )
    }

    private fun genreUrl(genre: String, offset: Int) =
        "$API?genre=${URLEncoder.encode(genre, "utf-8")}&limit=$PAGE_SIZE&offset=$offset&$LIST_FIELDS"

    override fun getCategoryList(url: String): Category {
        val offset = Regex("""[?&]offset=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 0
        val genre = Regex("""[?&]genre=([^&]+)""").find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "utf-8") } ?: ""
        val books = query(url.substringAfter('?')).map(::toBook)
        val currentPage = offset / PAGE_SIZE + 1
        val hasNext = books.size == PAGE_SIZE
        val totalPage = if (hasNext) currentPage + 1 else currentPage
        val nextUrl = if (hasNext) genreUrl(genre, offset + PAGE_SIZE) else ""
        return Category(books, currentPage, totalPage, url, nextUrl)
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val id = Regex("""[?&]lvid=(\d+)""").find(bookUrl)?.groupValues?.get(1)
            ?: return BookDetail(emptyList())
        if (!loadEpisodes) return BookDetail(emptyList())
        val obj = query("id=$id&extended=1").firstOrNull() ?: return BookDetail(emptyList())
        val sections = obj.optJSONArray("sections")
        val episodes = ArrayList<Episode>()
        var reader = ""
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val s = sections.getJSONObject(i)
                val url = s.optString("listen_url")
                if (url.isEmpty()) continue
                episodes.add(Episode(s.optString("title").ifEmpty { "Section ${i + 1}" }, url))
                if (reader.isEmpty()) {
                    reader = s.optJSONArray("readers")?.optJSONObject(0)?.optString("display_name") ?: ""
                }
            }
        }
        val book = toBook(obj)
        return BookDetail(episodes, book.intro, reader, book.author, episodes.size, book.coverUrl)
    }
}
