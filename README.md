# 我的听书 — 持續維護的書源訂閱

[「我的听书」](https://github.com/eprendre/tingshu)是一款支援自訂書源的 Android 聽書 app。
上游作者已停止維護,官方訂閱端點(`wdts.top`)也已失效 —— 如果你的書源大量斷掉、
或重裝後加不回來,這個倉庫提供一份**持續維護中的替代訂閱**。

## 快速開始

在 app 裡 **源管理 → 訂閱 → 右上角添加**,貼上這個網址:

```
https://raw.githubusercontent.com/chiaping55/tingshu/master/external_sources.json
```

添加後 app 每次啟動會自動檢查更新,書源修復或新增時不需要任何手動操作。

## 目前包含的書源

| 書源 | 站點 | 特點與注意事項 |
|---|---|---|
| 爱听书 | itingshu.net | 近年多人有聲劇收錄最全。音頻靠 WebView 嗅探,開播放頁會慢幾秒,手錶等無 WebView 裝置不能用 |
| 起点有声网 | qdysw.com | 實測可播率 95%,音頻直鏈 |
| 幻听网 | ting39.com | 實測可播率 95%,音頻直鏈 |
| 麒麟听书 | 70ts.com | 書多、更新勤,片源在蜻蜓FM(和其他源不同,同一本書可能是另一個演播版本)。連續快速跳集會暫時取不到音頻,等一兩分鐘即可,不是源壞了 |
| 有听网 | ting15.com | 武俠玄幻與都市言情是主體(各二三十頁)。相聲小品、曲藝戲曲別的源沒有,但書量很少。經典評書那一類的音頻伺服器在境外連不上(境內正常) |
| 听书吧 | ting8.cc | 書多(約 2 萬本),實測可播率約 60%。播不出來的多是喜馬拉雅把免費授權收回(檔案還在但回 403)。搜尋要先過一次站點的安全驗證頁 |
| 乐听吧 | leting8.com | ⚠️ 和听书吧**是同一份書庫**(實測重複度約 77%),可播率約 40% 更低。建議平常只開听书吧,把它當备用入口 |

這些數字都是**實測抽樣**:照著書源程式碼的邏輯走一遍分類 → 詳情 → 取址,
最後**真的下載音頻的開頭幾 KB** 確認能播 —— 「拿到地址」和「播得出來」是兩回事,
好幾個站的地址要補簽名參數或音質後綴,只斷言「非空」完全測不出來。
可播率不高的站,失敗原因幾乎都是喜馬拉雅收回免費授權(回 403、檔案還在),
換主機、補後綴都試過,救不回來。

挑選書源的標準是**「能不能聽到想聽的書」**:優先修可播率高的站、補其他源沒有的內容品類,
而不是把同樣的網文有聲重複收錄一遍。可播率都是真實抽測(實際下載音頻位元組),不是估的。

除爱听书外,所有書源的音頻都是 mp3/m4a 直鏈,不需要 WebView,手錶等裝置也能用。

**這個訂閱有人在顧嗎?** 有。倉庫設了[每週自動健康檢查](.github/workflows/health-check.yml) ——
測試會對每個站發真實請求、實際下載一段音頻,站掛了會自動通知,不必等到你想聽的時候才發現。

**遇到問題?** 「載入出錯」多半是站點暫時限流,過幾分鐘重進即可;某一集播不出來就換一集試試。
確定是書源壞了(整個源都打不開、持續數天)歡迎回報。

---

## 給想自己寫書源的人

這個 fork 除了訂閱檔,也整理了一套比上游範例更省力的寫源框架,程式碼都在
[CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/)。

### 按建站模板抽成基類

中文聽書站大多用同一批建站程式架的,同一模板的站點只差域名和路徑。這裡把三個常見模板
各抽成一個基類,**加同族站點只需要填 baseUrl、分類代號和 sourceId**:

- [PtcmsTingShu.kt](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/PtcmsTingShu.kt)
  —— PTCMS 站群。處理反爬 cookie 握手、分頁章節目錄、音頻地址拼接與線路重試。
  同族站之間的差異做成開關:章節目錄在獨立頁還是書頁本身、目錄頁走不走手機站、
  支不支援換線路、音頻後綴取 murl 還是播放器裡那段拼接
- [DedeCmsTingShu.kt](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/DedeCmsTingShu.kt)
  —— DedeCMS(織夢)站群。處理路徑段差異、喜馬拉雅 CDN 的防盜鏈與音質後綴
- [GxlCmsTingShu.kt](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/GxlCmsTingShu.kt)
  —— GXLCMS 家族。音頻靠 POST 換 JSON 拿直鏈,處理 unicode 轉義還原、非 ASCII 路徑的
  百分號編碼,以及取址接口的狀態語義(限流/缺章/付費要分清)

### 測試跑的是正式解析程式碼

基類把兩個只有 app 內才有實作的呼叫(`config()` / `notifyLoadingEpisodes()`)包成可覆寫方法,
所以單元測試直接跑正式的解析邏輯,不必像上游範例那樣在測試裡複製一份。
音頻相關的測試會**真的下載一段位元組**驗證能播 —— 「拿到地址了」和「播得出來」是兩回事,
好幾個站的地址要補簽名參數或後綴,只斷言非空完全測不出來。

### 訂閱檔的擺放規則(重要)

`external_sources.json` 和編譯好的 `sources_by_cp.jar` 必須放在**同一層目錄**
(這個倉庫放在根目錄)。app 並不按 JSON 裡的 `download_url` 抓 jar,而是按訂閱 JSON
所在的位置推算同目錄下的 `<entry_package>.jar` —— 放進子目錄訂閱會一直報
「外部源載入失敗」,而且錯誤訊息完全看不出原因。

### 建置環境

- **需要 JDK 17**(gradle 8.0 的 daemon 只支援 JDK 8–19,新機常見的 JDK 21+ 會直接失敗)。
  預設 JDK 不是 17 時,在 `~/.gradle/gradle.properties` 加 `org.gradle.java.home=/path/to/jdk-17`
- 倉庫源已改回 `mavenCentral()`(原本配的阿里雲舊路徑已改版、jcenter 已停服)
- bytecode 鎖定 Java 8 —— 倉庫自帶的 d8 認不得新版 class file,**也別用 Java 21 才有的
  API**(如 `List.reversed()`),Android 上不存在,而且會和 Kotlin 同名擴充函式撞名,
  本機測試照樣通過、上了手機才崩
- 建置:`./gradlew jar`,產物在 `build/libs/sources_by_cp.jar`

更多維護筆記(各站反爬型態、限流行為、除錯教訓)在 [sources/README.md](sources/README.md)。

### 相對上游的其他變更

清掉了 18 個站點已確認消失的失效源和 17 個一直紅的舊測試,`./gradlew test` 保持全綠。
判斷「站是否消失」不看單一網路能不能連上,而是比對 DNS 解析與域名停放主機的 IP,
並換出口複驗 —— 有些站只是封鎖境外 IP 或暫時故障,「我這裡連不上」不等於「站沒了」。

---

以下是上游原本的 README，写源的流程和订阅接口字段说明仍然适用。

---

# 我的听书

本项目停止维护。大部分代码已失效，仅供想要自己写源的同学参考。
本项目是教如何写源的，app仍会正常更新，不影响写源给自己使用，不过想要写源你需要了解基础的编程知识、http协议、html、css、json、java/kotlin相关。

## 下载

* [蓝奏云](https://pan.lanzoux.com/b873905)

## 自定义源

请参考 `CustomSources` 一个纯 java/kotlin 项目，用 IDEA 打开即可，不需要 Android Studio 以及安卓环境。零基础的同学请搭建好 java 开发环境，比如安装 **jdk18**, 并且添加 **环境变量**。
app 里面已经集成了网络请求库`Fuel` 以及HTML解析器 `Jsoup`， 此自定义源项目最好直接使用这两个库，不要引入额外的第三方库。

### 第一步：重构目录名

需要保证目录名独一无二, 比如我们取名为 `sources_by_xxx`，后续皆用此名举例。
![alter folder name](art/sources1.jpg)

### 第二步：开始编写自定义源

在第一步的目录下面新建一个类继承`TingShu`，参考注释和代码示例编写相应的代码。附一份粗浅的代码执行逻辑图。

![process](art/sources5.png)

### 第三步：自定义源入口

把第二步编写好的一个或多个源添加至 `SourceEntry` 的 `getSources` 里， app 端会通过这个方法获取源。

### 第四步：打包 jar 文件

1. 在`gradle.properties`里面修改`MY_SOURCES_PACKAGE=sources_by_xxx`
1. 打开命令行，在项目根目录输入: `./gradlew jar`。 windows 平台：`gradlew.bat jar` 或者 `.\gradlew.bat jar`。不喜欢命令行的同学可以直接在IDEA右方找到Gradle->CustomSources->build->jar 双击。Linux平台若报错需添加运行权限：`chmod +x dx/d8`。
1. 此时在项目目录/build/libs/ 里面出现 CustomSources-1.0-SNAPSHOT.jar。并生成 `sources_by_xxx.jar`, windows 系统还会生成一个 upload.bat 文件。

![jar](art/jar.png)

### 第五步：添加 sources_by_xxx.jar 包至 app


* 自动添加：运行 upload.bat 即可。（需要先配置好adb的环境变量，如果没有adb的同学可以去解压项目里的adb.zip，放到合适的地方并添加环境变量)

* 手动添加：把 jar 包移至手机 app 目录下: `/sdcard/Android/data/com.github.eprendre.tingshu/files/jars/`， app 会在启动时自动加载。

**订阅添加**: 写一个接口，然后在 app 的自定义源管理右上角添加。订阅方式的好处是源作者可以更轻松的维护源，只要在接口里修改版本号，app 每次启动时会自动检测更新。

接口返回内容举例：

```json
{
    "version": 27,
    "entry_package": "sources_by_eprendre",
    "download_url": "https://xxxxx.com/sources_by_eprendre.jar",
    "update_msg": "外置源兼容最新版",
    "support_url": ""
}
```

接口字段说明：

* `version`: 为数字类型，代表版本号。 app 以此来判断这个 jar 包是否有更新。
* `entry_package`: 为第一步提到的目录名，app下载 jar 包后也会自动命名为此名字。这是 app 找到相关类的关键。
* `download_url`: jar 包下载地址。
* `update_msg`: 更新信息。
* `support_url`: 此参数不为空时，在app长按订阅源将出现`支持`选项。源作者可以放自己的赞赏二维码图片链接，或者任意自己想放的链接。

接口或者 jar 包可在 github raw文件免费托管，不过国内用户有一定几率打不开 github 的链接 。

### 调试

app 将在最新版加入调试功能。
在源管理 -> 订阅 -> 选择一个源，长按 -> 调试，进入。

上方搜索框输入`cat`将自动调试分类相关逻辑， 输入其它关键词则调试搜索相关逻辑。

![debug](art/debug.png)
