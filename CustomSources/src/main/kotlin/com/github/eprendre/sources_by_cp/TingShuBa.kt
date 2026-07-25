package com.github.eprendre.sources_by_cp

/**
 * 听书吧 https://www.ting8.cc
 *
 * DedeCMS 站群里资源量最大的一个，24 个分类，玄幻一类就有 127 页(每页 48 本)。
 * 音频是 xmcdn 上的 m4a 直链、无防盗链，一次请求就能拿到，不需要 WebView。
 *
 * 同族站点(海洋听书 79ting、有声听书吧 18ting 等)用同一套模板但改了路径段名字，
 * 例如 79ting 是 /html 做分类、/books 做详情，与本站正好相反。要加的时候
 * 继承 [DedeCmsTingShu] 把 categoryPath / bookPath 填对即可，但务必先逐站实测过
 * 章节与音频再上，不要照抄。
 */
object TingShuBa : DedeCmsTingShu() {
    override val baseUrl = "https://www.ting8.cc"

    override val categoryPath = "books"

    override val bookPath = "mp3"

    override fun getSourceId() = "b7d41e5c9a084f36bc27de1058a93741"

    override fun getName() = "听书吧"

    override fun getDesc() =
        "推荐指数:5星 ⭐⭐⭐⭐⭐\n资源量很大，m4a直链无防盗链。搜索需要先过一次站点的安全验证页。"

    override val categories = listOf(
        "1" to "玄幻",
        "2" to "言情",
        "3" to "都市",
        "7" to "武侠",
        "10" to "穿越",
        "11" to "科幻",
        "12" to "网游",
        "8" to "历史",
        "9" to "军事",
        "4" to "恐怖",
        "5" to "惊悚",
        "6" to "推理",
        "13" to "评书",
        "23" to "相声小品",
        "14" to "戏曲",
        "15" to "笑话",
        "24" to "百家讲坛",
        "16" to "儿童",
        "17" to "财经",
        "18" to "广播",
        "19" to "诗歌",
        "20" to "文学",
        "21" to "粤语",
        "22" to "经典"
    )
}
