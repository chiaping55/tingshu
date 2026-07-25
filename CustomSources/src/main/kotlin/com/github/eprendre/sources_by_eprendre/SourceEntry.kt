package com.github.eprendre.sources_by_eprendre

import com.github.eprendre.tingshu.sources.TingShu

object SourceEntry {

    /**
     * 说明
     */
    @JvmStatic
    fun getDesc(): String {
        return "听书源"
    }

    /**
     * 获取内容分类标识（如"听书"）。
     *
     * 相同分类名称（如"听书"）用于聚合不同订阅源的内容，
     * 使用户能够统一浏览或搜索同一分类下的所有内容。
     *
     * @return 当前内容的分类名称，例如"听书"、"视频"或"音乐"等
     */
    @JvmStatic
    fun getCategory(): String {
        return "听书"
    }

    /**
     * 返回此包下面的源。
     *
     * 已移除站点确认消失(域名过期停放、跳转广告页、DNS 无记录)的源:
     * 幻听网(ting89)、六听网(6ting)、456听书(ting456)、听书宝(tingshubao)、
     * 声波FM(shengbo)、56听书(ting56)、静听网(audio698)、心魔听书(ixinmoo)、
     * 芒果听书(mgting)、中版有声(3eol)、爱听书(2uxs)、520听书(fushu520)、
     * 我爱听评书(tpsge)、麻辣听书(malatingshu)、有兔阅读(mituyuedu)。
     */
    @JvmStatic
    fun getSources(): List<TingShu> {
        return listOf(
            YunTuYouSheng,
            KuWo,
            KouDaiWeiKeTang,
            BoKanYouSheng,
            CCTV,
            YouShengXiaoShuoBa,
            IFish,
            TingChina,
            BiliBili,
            TingShu74,
            SouGou,
            LianTingWang,
            JiHe,
            HaiYangTingShu,
            VBus,
            JuTingWang,
            LanRenTingShu
        )
    }
}
