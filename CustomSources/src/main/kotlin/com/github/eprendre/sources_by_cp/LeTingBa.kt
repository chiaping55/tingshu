package com.github.eprendre.sources_by_cp

/**
 * 乐听吧 https://www.leting8.com
 *
 * 同样是 DedeCMS 站群，路径段与听书吧不同：分类是 /le，详情是 /so。
 *
 * **和听书吧其实是同一份数据库。** 原本这里写「内容不重叠(实测同一分类第一页书单完全不同)，
 * 所以两个都留着才有意义」—— 那个结论是错的，2026-07-26 复验推翻了它：
 * 玄幻第 1 页 11/16 同名、第 2 页 10/16 同名，对齐书籍 id 区间后重复度 77%，
 * 深页(id 1881–1891)4/4 完全相同。两站只差书籍 id 偏移 2~8。
 * 当初「书单完全不同」的观察大概只是撞上了 id 偏移造成的视窗错位。
 *
 * 后果是聚合搜索里同一本书会出现两次。留着它的唯一理由是**当听书吧那个域名出事时顶上**，
 * 平常没必要两个都开 —— 而且它的可播率比听书吧低(见 getDesc)。
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
        "推荐指数:2星 ⭐⭐\n" +
            "和听书吧是**同一份书库**(实测重复度约 77%)，聚合搜索里同一本书会出现两次。" +
            "建议平常只开听书吧，把这个源当听书吧域名出事时的备用入口。\n" +
            "抽样实测约四成能播 —— 比听书吧(约六成)低。\n" +
            "播不出来的主要不是文件消失，而是喜马拉雅把免费授权收回了(回 403、文件还在)，" +
            "换主机、补音质后缀都试过，救不回来。\n" +
            "搜索需要先过一次站点的安全验证页。"
}
