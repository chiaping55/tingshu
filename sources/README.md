# 自维护听书源 sources_by_cp

给「我的听书」app 用的外置源。官方 `wdts.top` 的订阅端点已全部失效（返回 HTTP 526），这里是自己接手维护的一份。

## 订阅地址

app 里 **源管理 → 订阅 → 右上角添加**，粘贴（结尾不要加斜杠）：

```
https://raw.githubusercontent.com/chiaping55/tingshu/master/sources/update.json
```

app 每次启动会比对 `version`，有更新自动下载。

## 手动导入（订阅连不上时）

```bash
adb push sources_by_cp.jar /sdcard/Android/data/com.github.eprendre.tingshu/files/jars/
adb shell chmod 444 /sdcard/Android/data/com.github.eprendre.tingshu/files/jars/sources_by_cp.jar
```

放进去后重启 app，再去 **设置 → 选择源站点** 把源勾上 —— app 会记住旧的勾选状态，新源默认是关闭的。

## 包含的源

| 源 | 站点 | 模板 | 备注 |
|---|---|---|---|
| 起点有声网 | www.qdysw.com | PTCMS | 约 1.5 万本 |
| 幻听网 | www.ting39.com | PTCMS | 与 22ting.com（一夜幻听）同品牌，但没有 cloudflare 拦截 |
| 乐听网 | www.leting.vip | PTCMS | 约 1 万本 |
| 听书吧 | www.ting8.cc | DedeCMS | 资源量最大，玄幻一类就 127 页；搜索需先过站点验证页 |

音频全部是 mp3/m4a 直链，不需要 WebView，手表等设备也能用。

两套模板各自有基类（[PtcmsTingShu](../CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/PtcmsTingShu.kt) /
[DedeCmsTingShu](../CustomSources/src/main/kotlin/com/github/eprendre/sources_by_cp/DedeCmsTingShu.kt)），
加同模板的站点只需要填 baseUrl 和分类清单。

## 开发

```bash
cd CustomSources
./gradlew test    # 测试会发真实网络请求，并下载一段音频确认能播
./gradlew jar     # 产出 build/libs/sources_by_cp.jar
```

需要 JDK 17 跑 gradle（gradle 8.0 不支持 JDK 21+）。本机默认 JDK 不是 17 时，在 `~/.gradle/gradle.properties` 里加：

```
org.gradle.java.home=/path/to/jdk-17
```

### 踩过的坑

站点改版或者写同类站时会用到：

**PTCMS 系（起点/幻听/乐听）**

- **guard 反爬要两个 cookie**：挑战页用 `Set-Cookie` 下发 `pt_browser_id`，混淆 js 里算出的 token 内容是 `IP|pt_browser_id|时间戳|hash`。只补 `pt_guid` 会一直拿到挑战页。
- **音频地址分两段存**：`urlXXX` 是主体、`murlXXX` 是扩展名。有些线路给的主体不带扩展名，不接上 cdn 直接回 403。
- **默认线路经常是空的**：player.html 的 `site` 参数是线路号，站点默认那条对不少章节返回空字符串，要换其它线路重试。
- **章节目录页的 hash 每次请求都变**，不能写死，要从详情页的 `a.dirurl` 现取。

**DedeCMS 系（听书吧）**

- 音频明文写在播放页的 `var now="…"`，同页还有 `var next`（下一集），正则要区分开。
- 新的喜马拉雅 cdn（`aod.cos.tx.xmcdn.com`）校验防盗链，不带 Referer 直接 403，旧的 `audio.xmcdn.com` 不校验 —— 用 `AudioUrlExtraHeaders` 补，并且要严格判断域名，否则会弄坏其它书源的音频请求。
- 搜索接口有验证码闸（标题「系统安全验证」），实现 `ISearchVerification` 让 app 弹页面由用户自己过一次。

**测试写法**

基类把两个只有 app 才有实现的调用（`config()` / `notifyLoadingEpisodes()`）包成 `configure()` / `notifyLoading()`，
测试用匿名子类覆写掉，所以单元测试跑的是正式解析代码本身，而不是复制一份逻辑。

音频测试一定要真的下载一段字节 —— 只断言「地址非空」会漏掉「地址拿到了但 cdn 回 403」这类 bug，这个坑真踩过。

## 已移除的源

原仓库里站点确认消失（域名过期停放、跳转广告页、DNS 无记录）的源已删除，对应测试也一并清掉：

幻听网(ting89)、六听网(6ting)、456听书(ting456)、听书宝(tingshubao)、声波FM(shengbo)、
56听书(ting56)、静听网(audio698)、心魔听书(ixinmoo)、芒果听书(mgting)、中版有声(3eol)、
爱听书(2uxs)、520听书(fushu520)、我爱听评书(tpsge)、麻辣听书(malatingshu)、有兔阅读(mituyuedu)、
九州影视(unss)、南瓜影视(nangua55)、樱花动漫(yinghuacd)

判断依据是从手机端实测 DNS 加上比对域名停放主机的 IP，不是只看本机连不上 —— 有些站只是屏蔽境外 IP，那些保留了。
