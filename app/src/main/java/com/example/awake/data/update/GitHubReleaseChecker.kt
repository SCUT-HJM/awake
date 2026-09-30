package com.example.awake.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** GitHub Releases 上最新（非预发布）版本的信息。 */
data class GitHubRelease(
    val versionName: String,
    val versionCode: Int,
    /** 发布说明中约定的 APK SHA-256（小写 hex），用于下载后完整性校验。 */
    val apkSha256: String?,
    /** 说明、示例、上传的 APK 均可引用的发布页地址。 */
    val apkUrl: String?,
    val pageUrl: String,
    val notes: String,
    val publishedAt: String?
)

/**
 * 通过 GitHub Releases 检测新版本。
 *
 * 仓库与 Release body 约定见 README「发布与自动更新」：
 * body 前几行写 `versionName: x`、`versionCode: N`、`apk-sha256: ...`，
 * 其余内容作为更新说明展示；APK 从 assets 中按 `.apk` 后缀识别。
 *
 * 默认走 Releases 订阅源（`releases.atom`）：它由 GitHub 网页服务提供，
 * 不占用 `api.github.com` 的匿名配额（60 次/小时，按 IP 计算）。
 * 订阅源不提供 assets 列表，APK 地址按命名约定推导，下载前会探测有效性。
 * API 仅在订阅源不可用、或推导出的下载地址无效时作为兜底通道使用。
 */
class GitHubReleaseChecker(
    private val repo: String = GitHubReleaseChecker.DEFAULT_REPO,
    private val client: OkHttpClient = GitHubReleaseChecker.defaultClient()
) {
    /** 拉取最新正式 Release；网络或解析失败时抛 IOException。 */
    @Throws(IOException::class)
    fun fetchLatestRelease(): GitHubRelease {
        // 优先订阅源：不消耗 API 配额，避免共享出口 IP 被限流时无法检查更新。
        return runCatching { fetchLatestReleaseFromAtom() }
            .getOrElse { atomError ->
                runCatching { fetchLatestReleaseFromApi() }
                    .getOrElse { apiError ->
                        throw IOException(describeFailure(apiError, atomError))
                    }
            }
    }

    /**
     * 确认可用的 APK 下载地址。
     *
     * 订阅源推导出的地址先做一次 HEAD 探测；若因 asset 命名不符而 404，
     * 再调 API 拿准确地址（此时才消耗 1 次配额），都失败返回 null。
     */
    fun resolveApkUrl(release: GitHubRelease): String? {
        val candidate = release.apkUrl
        if (candidate != null && probeDownloadUrl(candidate)) return candidate
        return runCatching { fetchLatestReleaseFromApi().apkUrl }.getOrNull()
    }

    /** HEAD 探测下载地址是否真实存在（GitHub 会 302 到 CDN，客户端自动跟随）。 */
    private fun probeDownloadUrl(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "Awake-Android")
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    @Throws(IOException::class)
    private fun fetchLatestReleaseFromApi(): GitHubRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Awake-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub 返回 ${response.code}${if (text.isBlank()) "" else "：${text.take(120)}"}")
            }
            return parseRelease(JSONObject(text))
        }
    }

    /**
     * 从 Releases 订阅源取版本号最大的正式 Release。
     *
     * 订阅源条目的排列顺序不保证严格按时间倒序（实测出现过较新版本排在后面），
     * 因此按正文里的 versionCode 取最大值，而不是直接取第一条。
     */
    @Throws(IOException::class)
    fun fetchLatestReleaseFromAtom(): GitHubRelease {
        val request = Request.Builder()
            .url("https://github.com/$repo/releases.atom")
            .header("Accept", "application/atom+xml")
            .header("User-Agent", "Awake-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Releases 订阅源返回 ${response.code}")
            }
            val releases = parseAtomFeed(text)
            if (releases.isEmpty()) throw IOException("Releases 订阅源中没有可用版本")
            return releases.filter { it.versionCode > 0 }.maxByOrNull { it.versionCode }
                ?: releases.first()
        }
    }

    private fun describeFailure(apiError: Throwable, atomError: Throwable): String {
        val apiMessage = apiError.message.orEmpty()
        val rateLimited = apiMessage.contains("403") && apiMessage.contains("rate limit", ignoreCase = true) ||
            apiMessage.contains("429")
        return if (rateLimited) {
            "GitHub 接口请求过于频繁，备用通道也不可用（${atomError.message ?: "网络异常"}）"
        } else {
            apiMessage.ifBlank { atomError.message ?: "网络异常" }
        }
    }

    /** 拉取最近的正式 Release 列表，用于汇总跨版本更新说明。 */
    @Throws(IOException::class)
    fun fetchReleases(limit: Int = 100): List<GitHubRelease> {
        // 同样优先订阅源，避免大版本说明聚合额外消耗 API 配额。
        return runCatching { fetchReleasesFromAtom() }
            .getOrElse {
                fetchReleasesFromApi(limit)
            }
    }

    /** 订阅源目前一次性返回最近若干条 Release，足够覆盖跨大版本说明。 */
    private fun fetchReleasesFromAtom(): List<GitHubRelease> {
        val request = Request.Builder()
            .url("https://github.com/$repo/releases.atom")
            .header("Accept", "application/atom+xml")
            .header("User-Agent", "Awake-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Releases 订阅源返回 ${response.code}")
            }
            val releases = parseAtomFeed(text)
            if (releases.isEmpty()) throw IOException("Releases 订阅源中没有可用版本")
            return releases
        }
    }

    @Throws(IOException::class)
    private fun fetchReleasesFromApi(limit: Int = 100): List<GitHubRelease> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=${limit.coerceIn(1, 100)}")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Awake-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub 返回 ${response.code}${if (text.isBlank()) "" else "：${text.take(120)}"}")
            }
            val array = JSONArray(text)
            return (0 until array.length())
                .mapNotNull { index -> array.optJSONObject(index) }
                .filterNot { it.optBoolean("prerelease") }
                .map(::parseRelease)
        }
    }

    companion object {
        const val DEFAULT_REPO = "SCUT-HJM/awake"

        private val METADATA_LINE = Regex(
            """^\s*(versionName|versionCode|apk-sha256)\s*[:：]""",
            setOf(RegexOption.MULTILINE)
        )
        private val VERSION_NAME_LINE = Regex("""(?m)^\s*versionName\s*[:：]\s*(\S+)""")
        private val VERSION_CODE_LINE = Regex("""(?m)^\s*versionCode\s*[:：]\s*(\d+)""")
        private val APK_SHA256_LINE = Regex("""(?m)^\s*apk-sha256\s*[:：]\s*([0-9A-Fa-f]{32,128})""")

        /** 旧版本号是否小于最新版本的 versionCode（versionCode 是唯一比较依据）。 */
        fun hasUpdate(latestVersionCode: Int, currentVersionCode: Int): Boolean =
            latestVersionCode > currentVersionCode

        /** 解析 /releases/latest 返回的 JSON（纯函数，便于单元测试）。 */
        fun parseRelease(json: JSONObject): GitHubRelease {
            val tag = json.optString("tag_name").trim()
            val body = json.optString("body").orEmpty()
            val versionName = VERSION_NAME_LINE
                .find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: tag
            val versionCode = VERSION_CODE_LINE
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            val apkSha256 = APK_SHA256_LINE
                .find(body)?.groupValues?.get(1)
                ?.lowercase()
                ?.takeIf { it.length == 64 }
            val apkUrl = runCatching {
                val assets = json.optJSONArray("assets") ?: return@runCatching null
                (0 until assets.length()).mapNotNull { index ->
                    assets.optJSONObject(index)
                        ?.optString("browser_download_url")
                        .orEmpty()
                        .takeIf { it.endsWith(".apk", ignoreCase = true) }
                }.firstOrNull()
            }.getOrNull()
            // 去掉约定元信息行，保留更新说明供界面展示。
            val notes = body.lines()
                .filterNot { METADATA_LINE.containsMatchIn(it) }
                .joinToString("\n")
                .trim()
            return GitHubRelease(
                versionName = versionName,
                versionCode = versionCode,
                apkSha256 = apkSha256,
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url")
                    .ifBlank { "https://github.com/$DEFAULT_REPO/releases" },
                notes = notes.ifBlank { "请在 GitHub Releases 页面查看更新内容" },
                publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
            )
        }

        /**
         * 解析 Releases 订阅源（Atom）。订阅源与 API 返回同源数据，
         * 但不消耗 API 配额，作为限额时的备用通道。
         *
         * APK 下载地址按仓库既有命名约定 `awake_<versionName>.apk` 推导，
         * 订阅源不提供 assets 列表。
         */
        fun parseAtomFeed(xml: String): List<GitHubRelease> =
            Regex("(?s)<entry>(.*?)</entry>")
                .findAll(xml)
                .mapNotNull { parseAtomEntry(it.groupValues[1]) }
                .toList()

        private fun parseAtomEntry(entry: String): GitHubRelease? {
            val link = Regex("""<link[^>]*rel="alternate"[^>]*href="([^"]+)"""")
                .find(entry)?.groupValues?.get(1)
                ?: Regex("""<link[^>]*href="([^"]+)"""")
                    .find(entry)?.groupValues?.get(1)
                ?: return null
            val body = htmlToText(tagText(entry, "content").orEmpty())
            val tag = link.substringAfterLast('/').ifBlank { tagText(entry, "title").orEmpty() }
            val json = JSONObject()
                .put("tag_name", tag)
                .put("body", body)
                .put("html_url", link)
                .put("published_at", tagText(entry, "updated").orEmpty())
                .put("assets", JSONArray())
            val release = parseRelease(json)
            val versionName = release.versionName.removePrefix("v")
            val baseUrl = link.substringBefore("/releases/tag/")
            return release.copy(
                apkUrl = "$baseUrl/releases/download/$tag/awake_$versionName.apk"
            )
        }

        private fun tagText(entry: String, tag: String): String? =
            Regex("(?s)<$tag[^>]*>(.*?)</$tag>")
                .find(entry)?.groupValues?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        /** 订阅源里的 content 是 HTML 转义后的发布说明，先还原再转纯文本。 */
        private fun htmlToText(html: String): String = unescapeHtml(html)
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|li|h1|h2|h3|h4|div|ul|ol|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        private fun unescapeHtml(text: String): String = text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
