# 我的听书 — 自维护书源 fork

这是 [eprendre/tingshu](https://github.com/eprendre/tingshu) 的 fork。上游已停止维护，
官方的订阅端点 `wdts.top/api/sources/*.json` 现在全部返回 HTTP 526（源站证书失效），
所以这里接手维护自己用的一份书源。

**app 本身不在这个仓库里**，它是闭源的；这里只有「外置书源」的代码 —— app 启动时会从
`/sdcard/Android/data/com.github.eprendre.tingshu/files/jars/` 加载 jar，所以不需要改 app 就能自己更新书源。

## 订阅地址

app 里 **源管理 → 订阅 → 右上角添加**，粘贴（结尾不要加斜杠）：

```
https://raw.githubusercontent.com/chiaping55/tingshu/master/external_sources.json
```

包含的源、实测可播率、以及踩过的坑都记在 [sources/README.md](sources/README.md)。

---

## 这个 fork 改了什么

### 加了什么

**新的源包 `sources_by_cp`**（[CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/)），
5 个源按站点模板分成两个基类，加同模板的站点只需要填 baseUrl 和路径段：

| 源 | 站点 | 模板 | 实测可播率 |
|---|---|---|---|
| 爱听书 | www.itingshu.net | PTCMS | 音频走 WebView，未测 |
| 起点有声网 | www.qdysw.com | PTCMS | 95% |
| 幻听网 | www.ting39.com | PTCMS | 95% |
| 听书吧 | www.ting8.cc | DedeCMS | 48% |
| 乐听吧 | www.leting8.com | DedeCMS | 40% |

- [PtcmsTingShu.kt](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/PtcmsTingShu.kt)
  —— 处理 PTCMS 站群共有的 guard 反爬 cookie 握手、分页章节目录、音频地址拼接与线路重试
- [DedeCmsTingShu.kt](CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/DedeCmsTingShu.kt)
  —— 处理 DedeCMS 站群的路径段差异、喜马拉雅 CDN 的防盗链与音质后缀

音频全部是 mp3/m4a 直链，`isWebViewNotRequired = true`，手表等没有 WebView 的设备也能用。

**发布用的订阅文件**在 [sources/](sources/)：`update.json` + 编译好的 jar，改完源更新版本号，app 下次启动自动更新。

**测试会真的下载一段音频**验证能播，而不是只断言「拿到地址了」。基类把两个只有 app 才有实现的调用
（`config()` / `notifyLoadingEpisodes()`）包成可覆写方法，所以单元测试跑的是正式解析代码本身，
不是像上游那样在测试里复制一份逻辑。

### 清了什么

删掉了 **18 个源 + 17 个测试**，都是站点确认消失的（域名过期停放、跳转广告页、DNS 无记录）：

- 听书源（15）：幻听网(ting89)、六听网(6ting)、456听书(ting456)、听书宝(tingshubao)、
  声波FM(shengbo)、56听书(ting56)、静听网(audio698)、心魔听书(ixinmoo)、芒果听书(mgting)、
  中版有声(3eol)、爱听书(2uxs)、520听书(fushu520)、我爱听评书(tpsge)、麻辣听书(malatingshu)、
  有兔阅读(mituyuedu)
- 视频源（3）：九州影视(unss)、南瓜影视(nangua55)、樱花动漫(yinghuacd)
- 测试：上述源的测试，加上 7 个站点还活着但页面已改版、跑起来一直红的旧测试
  （海洋听书、洛奇Town、经典老歌、机核、酷我、声音巴士、云图有声 —— 源代码保留了，只删测试）

判断标准不是「本机连不上」，而是从**手机端实测 DNS** 再比对域名停放主机的 IP。
例如 `unss.net` 解析到 `208.98.40.223`，那是 4.cn 域名拍卖站的主机，所以判定为停放；
反过来海洋听书、口袋微课堂、听中国、聚听网从手机能正常解析，就保留了 —— 有些站只是屏蔽境外 IP。

清理后 `./gradlew test` 全部通过，不再有一堆红字盖掉真正的问题。

### 修了什么

构建在 2026 年的新环境上跑不起来，改了三处（[CustomSources/build.gradle](CustomSources/build.gradle)）：

- 仓库源改回 `mavenCentral()` —— 原本配的阿里云旧 nexus 路径已改版、jcenter 2021 年就停服了
- 去掉 `jvmToolchain(18)` —— JDK 18 已 EOL，而且 settings.gradle 没装 foojay resolver，
  gradle 不会自动下载工具链，会直接报 no matching toolchain
- 锁定 Java 8 bytecode —— 仓库自带的 d8 认不了新版 class file

**跑 gradle 需要 JDK 17**（gradle 8.0 的 daemon 只支持 JDK 8–19，新机常见的 JDK 21+ 会直接失败）。
本机默认 JDK 不是 17 时，在 `~/.gradle/gradle.properties` 里加 `org.gradle.java.home=/path/to/jdk-17`。
下面上游教程里写的「安装 jdk18」按现在的情况读作 JDK 17 即可。

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
