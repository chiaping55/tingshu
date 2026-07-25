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
     * 重试用尽后**必须抛出**，不能把错误页当正常内容交回去 —— 那样解析不到东西会变成
     * 「这本书没有章节」，把限流伪装成内容缺失，排查时会往完全错误的方向找。
     * 调用方要的话可以自己接住(见 PtcmsTingShu 翻页时的处理:保留已抓到的部分)。
     */
    private fun execute(build: () -> Connection): Connection.Response {
        var wait = FIRST_BACKOFF_MS
        repeat(MAX_RETRY) {
            val response = build().cookies(cookies).ignoreHttpErrors(true).execute()
            if (response.statusCode() != TOO_MANY_REQUESTS) {
                return response
            }
            Thread.sleep(wait)
            wait *= 2
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
