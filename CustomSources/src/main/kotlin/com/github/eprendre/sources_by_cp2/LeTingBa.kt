package com.github.eprendre.sources_by_cp2

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
            "和听书吧是同一家、书目基本相同，可播率还低一些(约四成)。\n" +
            "当听书吧的备用就好，平常不必两个都开(两个都开搜索结果会重复)。\n" +
            "第一次搜索会先跳一个验证页，过一次即可。"
}
