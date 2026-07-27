package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.utils.CategoryMenu

/**
 * 起点有声网 https://www.qdysw.com
 *
 * PTCMS 模板站，纯 Jsoup 即可，音频是 kuwo cdn 上的 mp3 直链且没有防盗链。
 * 同模板的还有 乐听网(m.leting.vip)、奇听网(m.qiting.cc)、一夜幻听网(ting39.com)，
 * 要加的时候继承 [PtcmsTingShu] 换 baseUrl 就行。
 */
object QiDianYouSheng : PtcmsTingShu() {
    override val baseUrl = "https://www.qdysw.com"

    override fun getSourceId() = "3c0449a8f40b47a4b6f8b53fa2c9c6a5"

    override fun getName() = "起点有声网"

    override fun getDesc() = "推荐指数:5星 ⭐⭐⭐⭐⭐\n资源约1.5万本，mp3直链无防盗链，手表也能用。"

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
                "all" to "全部有声",
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
