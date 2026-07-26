package com.github.eprendre.sources_by_cp

import org.jsoup.Connection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder

/**
 * PTCMS 系站点的反爬挑战处理。
 *
 * 首次请求会拿到一个几百字节的挑战页，里面有段倒序的 base64；解开就是设置 cookie 再 reload 的
 * 明文 js。带上那些 cookie 重新请求才拿到真正的页面。
 *
 * cookie 名称各站不同（起点有声网是 `pt_guid`，爱听书是 `__51guid__`），而且写法也不一样：
 * 一种是先拼成变量再赋值给 document.cookie，一种是直接赋值。所以这里不写死名称，
 * 而是把脚本里的变量声明和 cookie 赋值都解出来自己对应 —— 同一份逻辑就能吃两种站，
 * 站方哪天改名也不用改代码。
 *
 * 挑战页还会用 Set-Cookie 下发一个 id（如 `pt_browser_id`），而 token 就是用它算出来的，
 * 所以两边的 cookie 都要带上，只补 token 那个会一直拿到挑战页。
 */
internal class PtcmsGuard {
    private val cookies = HashMap<String, String>()

    /**
     * @param build 每次调用都要产生一个新的 Connection（同一个不能重送）
     */
    fun request(build: () -> Connection): Connection.Response {
        var response = execute(build)
        cookies.putAll(response.cookies())
        val solved = solve(response.body()) ?: return response
        cookies.putAll(solved)
        response = execute(build)
        cookies.putAll(response.cookies())
        return response
    }

    /**
     * 有的站会限流(爱听书连续翻章节目录就会回 429)，退避重试。
     *
     * **任何失败都必须抛出，不能把错误页当正常内容交回去。** 那样解析不到东西会变成
     * 「这本书没有章节」，把故障伪装成内容缺失，排查时会往完全错误的方向找。
     * 调用方要的话可以自己接住(见 PtcmsTingShu 翻页时的处理:保留已抓到的部分)。
     *
     * 这里踩过两次同一个坑：
     * 1. 一开始 429 也不抛，限流被当成「这本书没有章节」；
     * 2. 修好 429 之后，`ignoreHttpErrors(true)` 让 404/500/502 照旧被当成正常页面交回去 ——
     *    而且因为不抛异常，`fetchDirectoryPage` 那条「手机站失败就退回桌面站」的退路
     *    根本不会触发(它是靠 catch 异常启动的)，等于整段白写。
     * 所以现在的规则是：**只有 2xx 才算成功**。ignoreHttpErrors 仍然要开，
     * 否则拿不到状态码去分辨是限流还是别的错误。
     */
    private fun execute(build: () -> Connection): Connection.Response {
        var wait = FIRST_BACKOFF_MS
        repeat(MAX_RETRY) { attempt ->
            val response = build().cookies(cookies).ignoreHttpErrors(true).execute()
            val status = response.statusCode()
            if (status in 200..299) {
                return response
            }
            if (status != TOO_MANY_REQUESTS) {
                // 不是限流就没必要退避重试(404 再等也是 404)，直接抛出去
                throw IOException("站点返回 HTTP $status，这不是限流，可能改版或临时故障")
            }
            // 最后一次尝试之后不必再等 —— 等完还是要抛，那 12 秒纯粹是让用户多看 12 秒转圈
            if (attempt < MAX_RETRY - 1) {
                Thread.sleep(wait)
                wait *= 2
            }
        }
        throw IOException("站点限流(429)，退避重试 $MAX_RETRY 次仍被拒绝，请过一会再试")
    }

    /**
     * 解挑战页。返回 null 表示这次拿到的就是正常页面。
     */
    private fun solve(html: String): Map<String, String>? {
        val reversed = Regex("""var\s+reversed\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1) ?: return null
        val script = String(base64Decode(reversed.reversed()), Charsets.UTF_8)

        // 脚本里的字符串与数字变量，cookie 值都是从这些变量拼出来的
        val values = HashMap<String, String>()
        Regex("""var\s+(\w+)\s*=\s*'([^']*)'""").findAll(script).forEach {
            values[it.groupValues[1]] = it.groupValues[2]
        }
        Regex("""var\s+(\w+)\s*=\s*(\d+)\s*;""").findAll(script).forEach {
            values[it.groupValues[1]] = it.groupValues[2]
        }

        // 'cookie名=' + encodeURIComponent(变量) 或 'cookie名=' + 变量
        val result = HashMap<String, String>()
        Regex("""'(\w+)='\s*\+\s*(encodeURIComponent\()?\s*(\w+)""")
            .findAll(script)
            .forEach { match ->
                val (name, encode, variable) = match.destructured
                val raw = values[variable] ?: return@forEach
                result[name] = if (encode.isNotEmpty()) URLEncoder.encode(raw, "UTF-8") else raw
            }
        return if (result.isEmpty()) null else result
    }

    /**
     * 自己实现 base64 解码：项目不引入 android 依赖，java.util.Base64 又要 api 26。
     */
    private fun base64Decode(input: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in input) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) continue// 跳过 '=' 与空白
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        const val TOO_MANY_REQUESTS = 429
        const val MAX_RETRY = 3
        const val FIRST_BACKOFF_MS = 3000L
    }
}
