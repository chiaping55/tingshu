package com.github.eprendre.sources_by_cp

/**
 * ting15 有声小说 (www.ting15.com)
 *
 * GXLCMS 家族。加它的理由不是"再多一个源"，而是**补上现有源缺的品类** ——
 * 经典评书、曲艺戏曲、相声小品、家庭伦理这几类，PTCMS/DedeCMS 那批网文有声站基本没有，
 * 而它们又是不需要更新的常青内容。
 *
 * 音频是 POST 换回来的 m4a/mp3 直链，不需要 WebView(已实测下载到真实音频字节)。
 */
object Ting15 : GxlCmsTingShu() {
    override val baseUrl = "https://www.ting15.com"

    override val audioApiPath = "/?s=api-getneoplay"

    /**
     * 分类顺序按**书量**排，大类在前。
     *
     * 曾经把相声小品排第一(理由是"别的源没有这个品类")，但那一整类实测只有 5 本书、1 页 ——
     * 使用者一进来就撞上一个近乎空的分类，会以为整个源是空的。
     * 「补品类」这件事成立，可不该拿它当门面：武侠玄幻有 27 页，那才是这个源的主体。
     *
     * **经典评书放最后**：那一整类的音频都在 cloud.guoguo.org.cn，那台机器在境外
     * 连不上(TCP 443 直接超时，抽了 3 本都一样，2026-07 复验仍然如此)。
     * 境内应该正常，所以留着不删，但分类名就写明白。
     */
    override val categories = listOf(
        "wuxiaxuanhuan" to "武侠玄幻",
        "dushiyanqing" to "都市言情",
        "jiatinglunli" to "家庭伦理",
        "wenxuemingzhu" to "官场职场",
        "tuilixuanyi" to "推理悬疑",
        "kongbulingyi" to "恐怖灵异",
        "yinyue" to "助眠音频",
        "quyixiqu" to "曲艺戏曲",
        "xiangshengxiaopin" to "相声小品(只有几本)",
        "jingdianpingshu" to "经典评书(境外多半连不上)"
    )

    override fun getSourceId() = "7b4e91c6d8a3425fa07e2c5b39f1d846"

    override fun getName() = "ting15"

    override fun getDesc() = "推荐指数:3星 ⭐⭐⭐\n" +
        "音频是直链、不需要 WebView，武侠玄幻与都市言情是主体(各二三十页)。\n" +
        "相声小品、曲艺戏曲这两类别的源没有，但**书量很少**(相声只有几本)，" +
        "当成顺便看看就好，别指望它撑起这个源。\n" +
        "两个已知问题：经典评书那一类的音频服务器在境外连不上(境内应该正常)；" +
        "站点限流较严，触发后会锁一小时，所以别连着快速切集。"
}
