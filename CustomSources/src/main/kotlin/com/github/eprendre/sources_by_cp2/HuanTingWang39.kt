package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.utils.CategoryMenu

/**
 * 幻听网 https://www.ting39.com
 *
 * 和起点有声网是同一套 PTCMS(实测两个域名解析到同一台服务器)，分类代号也完全一样，
 * 所以直接复用 [PtcmsTingShu]。
 *
 * 这个站和 22ting.com(源包里叫"一夜幻听")是同品牌。22ting.com 挂在 cloudflare 后面，
 * Jsoup 请求会被机器人防护拦掉(所以那个源列表永远是空的，只有 WebView 嗅探能播)，
 * 而 ting39.com 没有这层防护，可以正常抓，内容也基本重合，用它替代更省事。
 */
object HuanTingWang39 : PtcmsTingShu() {
    override val baseUrl = "https://www.ting39.com"

    override fun getSourceId() = "999ed242fe644e37a7e9ac5c8a652b55"

    override fun getName() = "幻听网"

    override fun getDesc() =
        "推荐指数:4星 ⭐⭐⭐⭐\n" +
            "和起点有声网是同一家，书目基本相同 —— 当它的备用入口就好，" +
            "平常不必两个都开(两个都开的话搜索结果会重复)。"

    override fun getCategoryMenus(): List<CategoryMenu> {
        return listOf(
            menu(
                baseUrl, "小说",
                "xhqh" to "玄幻奇幻",
                "wxxx" to "武侠仙侠",
                "cyjk" to "穿越架空",
                "xytl" to "悬疑推理",
                "khjj" to "科幻竞技",
                "lsjs" to "历史军事",
                "xdyq" to "现代言情",
                "gdyq" to "古代言情",
                "hxyq" to "幻想言情",
                "qcxy" to "青春校园",
                "xcsh" to "乡村生活",
                "ysyz" to "影视原著"
            ),
            menu(
                baseUrl, "评书曲艺",
                "pingshu" to "其他评书",
                "xsqy" to "相声曲艺",
                "mxdt" to "明星电台"
            ),
            menu(
                baseUrl, "其它",
                "wxmz" to "文学名著",
                "mjcj" to "名家传记",
                "gxjd" to "国学经典",
                "bxzt" to "博闻杂谈",
                "zcgh" to "职场干货",
                "dajs" to "档案纪实",
                "zwts" to "自我提升",
                "ertong" to "儿童频道",
                "qita" to "其他有声"
            )
        )
    }
}
