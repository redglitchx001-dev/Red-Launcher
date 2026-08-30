/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.context.COPY_LABEL_DEVICE_CODE
import com.movtery.zalithlauncher.game.account.microsoft.MicrosoftDeviceCode
import com.movtery.zalithlauncher.game.account.microsoft.MicrosoftLoginState
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.IconTextButton
import com.movtery.zalithlauncher.ui.components.MarqueeText
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.navigateTo
import com.movtery.zalithlauncher.utils.copyText
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.string.isNotEmptyOrBlank
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.ScreenBackStackViewModel
import java.util.Locale

private const val TAG = "WebViewScreen"

/**
 * 导航至WebViewScreen并访问特定网址
 */
fun NavBackStack<TitledNavKey>.navigateToWeb(webUrl: String) = this.navigateTo(
    screenKey = NormalNavKey.WebScreen(webUrl),
    useClassEquality = true
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    key: NormalNavKey.WebScreen,
    backStackViewModel: ScreenBackStackViewModel,
    eventViewModel: EventViewModel
) {
    BaseScreen(
        screenKey = key,
        currentKey = backStackViewModel.mainScreen.currentKey,
        useClassEquality = true
    ) {
        var webUrl by remember {
            mutableStateOf(key.url)
        }

        val urlAvailable = remember(webUrl) {
            webUrl.isNotEmptyOrBlank() && webUrl != "about:blank"
        }

        val context = LocalContext.current
        var isWebLoading by remember { mutableStateOf(true) }

        val openExternal = remember(eventViewModel) {
            { url: String -> eventViewModel.sendEvent(EventViewModel.Event.OpenLink(url)) }
        }

        //微软设备码：登录期间把码一直显示在屏幕上，用户不再依赖一闪而过的Toast
        val deviceCode by MicrosoftLoginState.deviceCode.collectAsStateWithLifecycle()

        /**
         * 整个屏幕生命周期内只创建一个 WebView 实例。
         *
         * 之前 WebView 在 [AndroidView.factory] 中创建、并在每次离开界面时连同 Cookie 一起销毁，
         * 微软登录页面在跳转链（microsoft.com → login.live.com → login.microsoftonline.com）中
         * 一旦丢失会话，就会退化成 "silent sign-in" 并直接返回 AADSTS50058 的 JSON 错误页。
         */
        val webView = remember(context) {
            WebView(context).apply {
                configureForLogin(openExternal) { url, loading ->
                    webUrl = url
                    isWebLoading = loading
                }
            }
        }

        DisposableEffect(webView) {
            onDispose {
                releaseWebView(webView)
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface)
            ) {
                AnimatedVisibility(
                    visible = isWebLoading
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    )
                }

                //网址，可供用户复制；右侧提供刷新按钮，页面报错时可以重试
                AnimatedVisibility(
                    visible = webUrl.isNotEmptyOrBlank()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MarqueeText(
                            modifier = Modifier
                                .weight(1f)
                                //登录进行中时锁定在应用内浏览器，不允许跳到外部浏览器
                                .clickable(enabled = urlAvailable && deviceCode == null) {
                                    openExternal(webUrl)
                                },
                            text = webUrl,
                            style = MaterialTheme.typography.bodySmall
                        )

                        IconButton(
                            onClick = { webView.reload() }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.generic_refresh)
                            )
                        }
                    }
                }

                //设备码常驻显示，并提供复制入口
                deviceCode?.let { code ->
                    DeviceCodeBanner(
                        deviceCode = code,
                        onCopyCode = {
                            copyText(COPY_LABEL_DEVICE_CODE, code.userCode, context)
                        }
                    )
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        webView.apply {
                            //只在 WebView 首次进入界面时加载一次
                            loadUrl(key.url)
                        }
                    },
                    update = {
                        //不在此处重复加载 url，避免打断登录会话
                    }
                )
            }
        }
    }
}

/**
 * 把 WebView 配置成“可以完成一次完整登录”的状态。
 *
 * 关键点是 Cookie 与 DOM 存储必须可用且能持久化：微软登录页面依赖 sessionStorage/localStorage
 * 保存流程状态，并依赖跨域 Cookie 维持会话。缺少任何一项时，页面都会放弃交互式登录、
 * 转而尝试静默登录，然后返回 `AADSTS50058` 的 JSON 错误页。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForLogin(
    openExternal: (String) -> Unit,
    onNavigationStateChange: (url: String, loading: Boolean) -> Unit
) {
    //Cookie：允许接收 Cookie，并且允许第三方 Cookie（微软登录会跨多个域名跳转）
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForLogin, true)
    }

    settings.apply {
        javaScriptEnabled = true
        //微软登录页面依赖 sessionStorage / localStorage，默认关闭会导致登录流程直接失败
        domStorageEnabled = true
        databaseEnabled = true
        //允许 window.open() 在同一个 WebView 中打开，避免弹出窗口把会话带到新窗口里
        javaScriptCanOpenWindowsAutomatically = true
        //注意：WebSettings 的 getter 叫 supportMultipleWindows()，没有 get 前缀，
        //所以 Kotlin 不会合成同名属性，必须显式调用 setter，否则编译不过
        setSupportMultipleWindows(false)
        //走正常缓存策略：LOAD_NO_CACHE 会让登录跳转链反复重新请求，容易丢掉中间态
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        useWideViewPort = true
        loadWithOverviewMode = true
        //保留默认的 Android WebView User-Agent，微软对它是接受的
    }

    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onNavigationStateChange(url ?: "", true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onNavigationStateChange(url ?: "", false)
            //登录进行中时把 Cookie 落盘，进程被回收后重建 WebView 也不会丢会话
            if (MicrosoftLoginState.isLoggingIn) {
                runCatching { CookieManager.getInstance().flush() }
                    .onFailure { Logger.warning(TAG, "Failed to flush cookies", it) }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            return when (Uri.parse(url).scheme?.lowercase(Locale.ROOT)) {
                null, "http", "https", "about", "javascript", "blob", "data" -> false
                else -> {
                    //mailto: / market: / intent: 等交给系统处理
                    Logger.debug(TAG, "Opening non-http url externally: $url")
                    openExternal(url)
                    true
                }
            }
        }
    }

    //没有 WebChromeClient 时 WebView 会直接吞掉 JS 弹窗，登录页面的确认框会点了没反应
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.let {
                Logger.debug(
                    TAG,
                    "WebView console: ${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                )
            }
            return super.onConsoleMessage(consoleMessage)
        }
    }
}

/** 释放 WebView。登录进行中时保留 Cookie / DOM 存储，避免登录会话被中途清掉。 */
private fun releaseWebView(webView: WebView) {
    val loginActive = MicrosoftLoginState.isLoggingIn

    runCatching {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }.onFailure { th ->
        Logger.warning(TAG, "Failed to release WebView", th)
    }

    if (!loginActive) {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }.onFailure { th ->
            Logger.warning(TAG, "Failed to clear WebView storage", th)
        }
    } else {
        Logger.debug(TAG, "Microsoft login still running, keeping WebView cookies alive")
    }
}

/**
 * 设备码提示条：把待验证的码大字显示出来，并提供复制按钮。
 * 登录仅在应用内 WebView 完成；一旦在页面中被批准，轮询会自动完成并添加账号。
 */
@Composable
private fun DeviceCodeBanner(
    deviceCode: MicrosoftDeviceCode,
    onCopyCode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.account_microsoft_device_code_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )

        Text(
            modifier = Modifier
                .clickable(onClick = onCopyCode)
                .padding(vertical = 2.dp),
            text = deviceCode.userCode,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            ),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )

        Text(
            text = stringResource(R.string.account_microsoft_device_code_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )

        Spacer(modifier = Modifier.height(6.dp))

        IconTextButton(
            onClick = onCopyCode,
            painter = painterResource(R.drawable.ic_content_copy_filled),
            text = stringResource(R.string.generic_copy),
            style = MaterialTheme.typography.labelMedium,
            iconSize = 18.dp
        )
    }
}
