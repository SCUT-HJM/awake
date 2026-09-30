package com.example.awake.data.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {

    private fun releaseJson(
        tag: String = "v2.1.0",
        body: String,
        htmlUrl: String = "https://github.com/Lunaunde/awake/releases/tag/v2.1.0",
        assets: List<String> = listOf("https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk"),
        publishedAt: String = "2026-09-01T10:00:00Z"
    ): String = JSONObject()
        .put("tag_name", tag)
        .put("body", body)
        .put("html_url", htmlUrl)
        .put("published_at", publishedAt)
        .put(
            "assets",
            org.json.JSONArray(assets.map { url ->
                JSONObject().put("name", url.substringAfterLast('/')).put("browser_download_url", url)
            })
        )
        .toString()

    @Test
    fun parsesConventionalReleaseBody() {
        val body = """
            versionName: 2.1.0
            versionCode: 210
            apk-sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            更新内容：
            - 修复课表导入数量统计
            - 课表网格颜色加深
        """.trimIndent()
        val release = GitHubReleaseChecker.parseRelease(JSONObject(releaseJson(body = body)))

        assertEquals("2.1.0", release.versionName)
        assertEquals(210, release.versionCode)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", release.apkSha256)
        assertEquals("https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk", release.apkUrl)
        assertEquals("https://github.com/Lunaunde/awake/releases/tag/v2.1.0", release.pageUrl)
        assertEquals("2026-09-01T10:00:00Z", release.publishedAt)
        // 元信息行被剔除，只保留更新说明。
        assertFalse(release.notes.contains("versionName"))
        assertFalse(release.notes.contains("versionCode"))
        assertFalse(release.notes.contains("apk-sha256"))
        assertTrue(release.notes.contains("修复课表导入数量统计"))
        assertTrue(release.notes.contains("课表网格颜色加深"))
    }

    @Test
    fun fallsBackToTagNameAndZeroCodeWithoutMetadata() {
        val body = "一些没有约定字段的说明"
        val release = GitHubReleaseChecker.parseRelease(
            JSONObject(releaseJson(tag = "v2.1.0", body = body, assets = emptyList()))
        )
        assertEquals("v2.1.0", release.versionName)
        assertEquals(0, release.versionCode)
        assertNull(release.apkUrl)
        // body 非空时不使用兜底文案。
        assertEquals(body, release.notes)
    }

    @Test
    fun blankBodyUsesFallbackNotes() {
        val release = GitHubReleaseChecker.parseRelease(JSONObject(releaseJson(body = "  ")))
        assertEquals("请在 GitHub Releases 页面查看更新内容", release.notes)
    }

    @Test
    fun ignoresNonApkAssets() {
        val release = GitHubReleaseChecker.parseRelease(
            JSONObject(
                releaseJson(
                    body = "versionName: 2.1.0\nversionCode: 210",
                    assets = listOf(
                        "https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk.asc",
                        "https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk"
                    )
                )
            )
        )
        assertEquals(
            "https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk",
            release.apkUrl
        )
    }

    @Test
    fun comparesVersionCodes() {
        assertTrue(GitHubReleaseChecker.hasUpdate(latestVersionCode = 210, currentVersionCode = 200))
        assertFalse(GitHubReleaseChecker.hasUpdate(latestVersionCode = 200, currentVersionCode = 200))
        assertFalse(GitHubReleaseChecker.hasUpdate(latestVersionCode = 190, currentVersionCode = 200))
    }

    private fun atomEntry(
        tag: String,
        versionName: String,
        versionCode: Int,
        sha: String,
        updated: String
    ): String = """
        <entry>
          <id>tag:github.com,2008:Repository/1/$tag</id>
          <updated>$updated</updated>
          <link rel="alternate" type="text/html" href="https://github.com/SCUT-HJM/awake/releases/tag/$tag"/>
          <title>awake $versionName</title>
          <content type="html">&lt;p&gt;versionName: $versionName&lt;br&gt;
        versionCode: $versionCode&lt;br&gt;
        apk-sha256: $sha&lt;/p&gt;
        &lt;h2&gt;修复&lt;/h2&gt;
        &lt;ul&gt;&lt;li&gt;修复示例问题&lt;/li&gt;&lt;/ul&gt;</content>
        </entry>
    """.trimIndent()

    private fun atomFeed(vararg entries: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
        ${entries.joinToString("\n")}
        </feed>
    """.trimIndent()

    @Test
    fun parsesAtomFeedAndDerivesApkUrl() {
        val sha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val feed = atomFeed(
            atomEntry("v2.4.3", "2.4.3", 243, sha, "2026-09-30T02:57:30Z")
        )
        val releases = GitHubReleaseChecker.parseAtomFeed(feed)

        assertEquals(1, releases.size)
        val release = releases.single()
        assertEquals("2.4.3", release.versionName)
        assertEquals(243, release.versionCode)
        assertEquals(sha, release.apkSha256)
        assertEquals(
            "https://github.com/SCUT-HJM/awake/releases/download/v2.4.3/awake_2.4.3.apk",
            release.apkUrl
        )
        assertEquals("https://github.com/SCUT-HJM/awake/releases/tag/v2.4.3", release.pageUrl)
        // 元信息行被剔除，HTML 转成纯文本，只留发布说明。
        assertFalse(release.notes.contains("versionName"))
        assertFalse(release.notes.contains("versionCode"))
        assertFalse(release.notes.contains("apk-sha256"))
        assertTrue(release.notes.contains("修复示例问题"))
    }

    @Test
    fun atomHighestVersionCodeWinsRegardlessOfFeedOrder() {
        // 实测订阅源顺序不严格按时间倒序，必须按 versionCode 取最大。
        val feed = atomFeed(
            atomEntry("v2.4.2", "2.4.2", 242, "aa".repeat(32), "2026-09-15T14:23:02Z"),
            atomEntry("v2.4.3", "2.4.3", 243, "bb".repeat(32), "2026-09-30T02:57:30Z"),
            atomEntry("v2.4.2_fix", "2.4.2", 242, "cc".repeat(32), "2026-09-17T07:53:35Z")
        )
        val releases = GitHubReleaseChecker.parseAtomFeed(feed)

        assertEquals(3, releases.size)
        assertEquals(243, releases.maxByOrNull { it.versionCode }!!.versionCode)
    }
}
