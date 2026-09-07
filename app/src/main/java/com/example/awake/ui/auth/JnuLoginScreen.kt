package com.example.awake.ui.auth

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.awake.data.remote.JnuAuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JnuLoginScreen(
    auth: JnuAuthRepository,
    onAuthenticated: () -> Unit,
    onBack: () -> Unit
) {
    var webViewState by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("正在打开暨南大学登录页…") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("暨南大学登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "请仅在官方页面输入本人账号，Awake 不读取或保存密码。",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        status,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = {
                        val webView = webViewState ?: return@Button
                        auth.confirmCurrentPage(
                            webView,
                            onAuthenticated = onAuthenticated,
                            onFailure = { status = it }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Text("没有自动跳转？重新检查登录状态")
                }
            }
            // 扫码登录常见是 JS/AJAX 改写页面，不一定触发 WebView 的 onPageFinished。
            // 低频轮询当前 URL/Cookie 状态，避免必须手动点“重新检查”。
            LaunchedEffect(webViewState) {
                val webView = webViewState ?: return@LaunchedEffect
                while (isActive) {
                    delay(750)
                    auth.confirmCurrentPage(
                        webView,
                        onAuthenticated = onAuthenticated,
                        onFailure = { }
                    )
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).also { webView ->
                        webViewState = webView
                        auth.attach(
                            webView,
                            onStatus = { status = it },
                            onFailure = { status = it },
                            onAuthenticated = onAuthenticated
                        )
                    }
                },
                onRelease = { webView ->
                    webViewState = null
                    auth.cancel(webView)
                    webView.destroy()
                }
            )
        }
    }
}