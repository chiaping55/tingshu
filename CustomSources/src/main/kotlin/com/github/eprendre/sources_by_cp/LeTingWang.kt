package com.github.eprendre.sources_by_cp

import com.github.eprendre.tingshu.utils.CategoryMenu

/**
 * 乐听网 https://www.leting.vip
 *
 * 同样是 PTCMS 模板，实测分类代号、列表选择器、章节目录页、player.html 全部与
 * 起点有声网一致，所以复用 [PtcmsTingShu]。
 *
 * 注意要用 www：m.leting.vip 是另一套手机模板，没有 list-works 也没有搜索表单。
 */
object LeTingWang : PtcmsTingShu() {
    override val baseUrl = "https://www.leting.vip"

    override fun getSourceId() = "6f2b1c94e5a8471d9c3e0d76ab5f2418"

    override fun getName() = "乐听网"

    override fun getDesc() = "推荐指数:4星 ⭐⭐⭐⭐\n资源约1万本，与起点有声网同模板，mp3直链。"

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
                "pingshu" to "长篇评书",
                "xsqy" to "相声曲艺",
                "mxdt" to "明星电台"
            ),
            menu(
                baseUrl, "其它",
                "all" to "有声小说",
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
