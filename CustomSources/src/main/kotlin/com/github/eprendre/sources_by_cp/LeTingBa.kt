package com.github.eprendre.sources_by_cp

/**
 * 乐听吧 https://www.leting8.com
 *
 * 同样是 DedeCMS 站群，但内容和听书吧那一组不重叠(实测同一分类第一页书单完全不同)，
 * 所以两个都留着才有意义。路径段与听书吧不同：分类是 /le，详情是 /so。
 *
 * 这一组还有个镜像 www.ting17.com(内容相同、路径段和听书吧一样)，
 * 本站哪天挂了可以改 baseUrl 顶上。
 */
object LeTingBa : DedeCmsTingShu() {
    override val baseUrl = "https://www.leting8.com"

    override val categoryPath = "le"

    override val bookPath = "so"

    override fun getSourceId() = "2e8f7a3d61b94c05ae9d3f8027b64c1a"

    override fun getName() = "乐听吧"

    override fun getDesc() =
        "推荐指数:3星 ⭐⭐⭐\n" +
            "和听书吧同模板、书目不重复。抽样10本实测约六成能播(取址逻辑修好之前是四成)。\n" +
            "剩下播不出来的是站方托管在喜马拉雅与蜻蜓FM 上的文件真的没了，" +
            "属于内容缺失，改代码救不了。\n" +
            "搜索需要先过一次站点的安全验证页。"
}
