package com.example.awake.data.update

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUpdateState(
    val currentVersionName: String = "",
    val currentVersionCode: Int = 0,
    val checking: Boolean = false,
    val status: String? = null,
    val latest: GitHubRelease? = null,
    val showUpdateDialog: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val showMajorDialog: Boolean = false,
    val majorNotes: String = "",
    val openUpdateRequested: Boolean = false,
    val downloadedRelease: GitHubRelease? = null,
    val downloadedFile: File? = null,
    val readyInstall: Boolean = false
)

/**
 * 应用级更新协调器：让下载不跟随设置页销毁，并负责打开时的大版本提醒。
 */
class AppUpdateManager(
    context: Context,
    private val checker: GitHubReleaseChecker = GitHubReleaseChecker(),
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val initialPackageInfo = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    private val _state = MutableStateFlow(
        AppUpdateState(
            currentVersionName = initialPackageInfo?.versionName.orEmpty(),
            currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                initialPackageInfo?.longVersionCode?.toInt() ?: 0
            } else {
                @Suppress("DEPRECATION")
                initialPackageInfo?.versionCode ?: 0
            }
        )
    )
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private var majorCheckStarted = false

    /**
     * 打开应用时检查跨大版本更新。
     *
     * 每天最多检查一次：之前每次启动都会请求 Releases 接口，配合共享出口 IP
     * 很容易触发 GitHub 匿名限流。提醒展示后写入偏好，后续启动不再重复提醒。
     */
    fun checkMajorUpdate() {
        if (majorCheckStarted) return
        majorCheckStarted = true
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_MAJOR_CHECK_DAY, null) == today) return
        scope.launch {
            val release = runCatching { checker.fetchLatestRelease() }.getOrNull() ?: return@launch
            prefs.edit().putString(KEY_LAST_MAJOR_CHECK_DAY, today).apply()
            val current = _state.value
            if (release.versionCode <= current.currentVersionCode) return@launch
            val currentTrain = majorTrain(current.currentVersionName) ?: return@launch
            val latestTrain = majorTrain(release.versionName) ?: return@launch
            if (latestTrain <= currentTrain) return@launch
            if (prefs.getInt(KEY_ACK_MAJOR_TRAIN, -1) == latestTrain) return@launch

            val releases = runCatching { checker.fetchReleases() }.getOrDefault(emptyList())
            val notes = combinedNotes(releases, currentTrain, current.currentVersionCode, release)
            prefs.edit().putInt(KEY_ACK_MAJOR_TRAIN, latestTrain).apply()
            _state.update {
                it.copy(
                    latest = release,
                    showMajorDialog = true,
                    majorNotes = notes
                )
            }
        }
    }

    fun checkUpdate() {
        if (_state.value.checking || _state.value.downloading) return
        scope.launch {
            _state.update { it.copy(checking = true, status = "正在检查更新…") }
            runCatching { checker.fetchLatestRelease() }
                .onSuccess { release ->
                    _state.update {
                        val hasUpdate = GitHubReleaseChecker.hasUpdate(
                            release.versionCode,
                            it.currentVersionCode
                        )
                        it.copy(
                            latest = release,
                            showUpdateDialog = hasUpdate,
                            status = if (hasUpdate) {
                                "发现新版本 ${release.versionName}（当前 ${it.currentVersionName}）"
                            } else {
                                "已是最新版本（${it.currentVersionName}）"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        // 网络受限时回退到上次成功获取的版本信息，至少让用户知道有无更新。
                        val cached = it.latest
                        val fallback = if (cached != null &&
                            GitHubReleaseChecker.hasUpdate(cached.versionCode, it.currentVersionCode)
                        ) {
                            "连接 GitHub 失败，但上次检测到 ${cached.versionName} 可用。可手动访问 github.com/SCUT-HJM/awake/releases"
                        } else {
                            "检查失败：${error.message ?: "网络异常"}。可手动访问 github.com/SCUT-HJM/awake/releases"
                        }
                        it.copy(status = fallback)
                    }
                }
            _state.update { it.copy(checking = false) }
        }
    }

    fun startDownload(release: GitHubRelease) {
        if (_state.value.downloading) return
        scope.launch {
            _state.update {
                it.copy(
                    downloading = true,
                    downloadProgress = 0f,
                    showUpdateDialog = false,
                    status = "正在准备下载 ${release.versionName} …"
                )
            }
            // 订阅源推导出的地址可能是 404（asset 命名不一致），先探测，
            // 无效时回落 API 取准确地址；两者都拿不到就提示改用浏览器。
            val url = checker.resolveApkUrl(release)
            if (url == null) {
                _state.update {
                    it.copy(
                        downloading = false,
                        status = "未找到可用的安装包地址，请在浏览器中打开 Releases 页面下载"
                    )
                }
                return@launch
            }
            _state.update { it.copy(status = "正在下载 ${release.versionName} …") }
            runCatching {
                ApkUpdateSupport.downloadApk(
                    context = appContext,
                    url = url,
                    expectedSha256 = release.apkSha256
                ) { done, total ->
                    if (total > 0) {
                        _state.update { state ->
                            state.copy(downloadProgress = (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }.onSuccess { file ->
                _state.update {
                    it.copy(
                        downloading = false,
                        downloadProgress = 1f,
                        downloadedRelease = release,
                        downloadedFile = file,
                        readyInstall = true,
                        status = "下载完成，正在启动系统安装…"
                    )
                }
                installDownloaded()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        downloading = false,
                        status = "下载失败：${error.message ?: "网络异常"}。可改用浏览器下载"
                    )
                }
            }
        }
    }

    fun installDownloaded() {
        val current = _state.value
        val file = current.downloadedFile ?: return
        if (!file.exists()) {
            _state.update { it.copy(readyInstall = false, status = "安装包不存在，请重新下载") }
            return
        }
        _state.update { it.copy(readyInstall = false) }
        runCatching { ApkUpdateSupport.installApk(appContext, file) }
            .onSuccess {
                _state.update { it.copy(status = "已启动系统安装：请在系统弹窗中确认") }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        readyInstall = true,
                        status = "启动安装失败：${error.message ?: "未找到系统安装器"}。可改用浏览器下载"
                    )
                }
            }
    }

    fun dismissUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    fun showUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = true) }
    }

    fun dismissMajorDialog() {
        _state.update { it.copy(showMajorDialog = false) }
    }

    fun requestOpenUpdateScreen() {
        _state.update { it.copy(showMajorDialog = false, openUpdateRequested = true) }
    }

    fun consumeOpenUpdateRequest() {
        _state.update { it.copy(openUpdateRequested = false) }
    }

    private fun combinedNotes(
        releases: List<GitHubRelease>,
        currentTrain: Int,
        currentVersionCode: Int,
        latest: GitHubRelease
    ): String {
        val selected = releases
            .filter { release ->
                (majorTrain(release.versionName) ?: Int.MIN_VALUE) > currentTrain &&
                    release.versionCode > currentVersionCode
            }
            .ifEmpty { listOf(latest) }
        return selected.joinToString("\n\n") { release ->
            "${release.versionName}\n${release.notes}"
        }
    }

    private fun majorTrain(versionName: String): Int? {
        // 例：1.1.0 -> 11，1.2.0 -> 12；1.1.x 的补丁都属于 11，不触发大版本提醒。
        val match = Regex("""^v?(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)
            .find(versionName.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major * 1000 + minor
    }

    private companion object {
        const val PREFS_NAME = "awake_update_settings"
        const val KEY_ACK_MAJOR_TRAIN = "acknowledged_major_train"
        const val KEY_LAST_MAJOR_CHECK_DAY = "last_major_check_day"
    }
}
