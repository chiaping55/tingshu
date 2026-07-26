package com.github.eprendre.sources_by_cp

/**
 * 听书吧 https://www.ting8.cc
 *
 * DedeCMS 站群里资源量最大的一个，24 个分类，玄幻一类就有 127 页(每页 16 本，约 2000 本)。
 * 音频是 xmcdn 上的 m4a 直链、无防盗链，一次请求就能拿到，不需要 WebView。
 *
 * 注意**不要给音频加 Referer** —— 这个站有不少书在 fdfs.xmcdn.com 上，
 * 那个主机一带 Referer 就回 403(实测 4/4 干净翻转)。详见 [DedeCmsTingShu] 的说明。
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
        "推荐指数:3星 ⭐⭐⭐\n" +
            "书目约2万本，DedeCMS 这一组里最大的一个。抽样实测约六成能播" +
            "(按集数算约七到八成) —— 之前只有一半，其中一部分是我们自己加的 Referer 害的，" +
            "现在已经改掉，并且会先试原地址、不行再试带音质后缀的那个。\n" +
            "播不出来的主要不是文件消失，而是喜马拉雅把免费授权收回了(回 403、文件还在)，" +
            "换主机、补后缀都试过，救不回来 —— 换一本或换别的源。\n" +
            "搜索需要先过一次站点的安全验证页。"

}
