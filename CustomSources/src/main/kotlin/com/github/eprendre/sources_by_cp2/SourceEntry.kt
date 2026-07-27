package com.github.eprendre.sources_by_cp2

import com.github.eprendre.tingshu.sources.TingShu

/**
 * app 启动时按包名反射调用这里的静态方法来发现书源。
 * 包名要和 gradle.properties 里的 MY_SOURCES_PACKAGE、以及订阅 json 的 entry_package 一致。
 */
object SourceEntry {

    @JvmStatic
    fun getDesc(): String {
        return "自维护听书源"
    }

    /**
     * 分类名和其它订阅源保持一致，app 才会把内容聚合到同一处浏览、搜索。
     */
    @JvmStatic
    fun getCategory(): String {
        return "听书"
    }

    @JvmStatic
    fun getSources(): List<TingShu> {
        // 乐听网(leting.vip)已移除:社区 ting29 订阅里的 LeTing 指向同一个站，
        // 两个源会让聚合搜索每本书出现两次。
        return listOf(
            ITingShu,
            QiDianYouSheng,
            HuanTingWang39,
            // 麒麟听书:书多、更新勤，音频在蜻蜓FM，和上面几个源的片源不同
            QiLinTingShu,
            TingShuBa,
            LeTingBa,
            // 第三个模板家族(GXLCMS)。加它是为了补品类而不是凑数量:
            // 相声小品、曲艺戏曲这些上面几个源都没有，音频还是直链、不用 WebView。
            Ting15
        )
    }
}
