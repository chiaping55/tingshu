package com.github.eprendre.sources_by_cp

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
        return listOf(
            QiDianYouSheng,
            HuanTingWang39,
            LeTingWang,
            TingShuBa
        )
    }
}
