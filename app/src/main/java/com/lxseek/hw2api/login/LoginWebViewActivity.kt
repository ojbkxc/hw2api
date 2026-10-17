package com.lxseek.hw2api.login

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.lxseek.hw2api.core.auth.StsClient
import com.lxseek.hw2api.core.model.ModelRegistry
import com.lxseek.hw2api.core.util.Iso8601
import com.lxseek.hw2api.store.CredentialManager
import com.lxseek.hw2api.util.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OAuth 登录页（plan.md §3.2 / 附录 A.1）：WebView 加载 CodeArts portal authorize URL，
 * 拦截双形态回调（`http://127.0.0.1:8789/oauth/callback?code=...` 与 `codearts-agent://`），
 * 拿到 code 后走 StsClient.exchangeCode 交换凭证入库，成功后返回主界面。
 *
 * 安全边界（plan.md §4.3）：无 addJavascriptInterface；file/content access 全关；
 * JS 默认关闭（R1：portal 页若白屏可临时改 [ENABLE_JS] 后实机验证）。
 */
class LoginWebViewActivity : ComponentActivity() {

    private val session = LoginSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handled = AtomicBoolean(false)

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView

    private val stsClient = StsClient(Http.client)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        webView.loadUrl(session.authorizeUrl())
    }

    /** 纯代码构建视图（项目无 XML 布局）：WebView + 顶部进度条 + 底部错误重试面板 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildContentView(): View {
        val root = FrameLayout(this)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            // ── 安全基线（plan.md §4.3）：仅拦 callback，无 JS 桥、file access 全关 ──
            settings.javaScriptEnabled = ENABLE_JS
            settings.domStorageEnabled = ENABLE_JS
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            webViewClient = LoginWebViewClient()
        }
        root.addView(webView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP
            )
            max = 100
        }
        root.addView(progressBar)

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }
        errorText = TextView(this).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(Color.DKGRAY)
            textSize = 14f
        }
        val retryButton = Button(this).apply {
            text = "重试"
            setOnClickListener {
                errorPanel.visibility = View.GONE
                webView.loadUrl(session.authorizeUrl())
            }
        }
        errorPanel.addView(errorText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })
        errorPanel.addView(retryButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(errorPanel)
        return root
    }

    /** 登录 WebView 客户端：只拦双形态回调与未知 scheme，其余 http(s) 正常加载 */
    private inner class LoginWebViewClient : WebViewClient() {

        /** 页面加载进度（顶部细进度条） */
        override fun onPageFinished(view: WebView?, url: String?) {
            progressBar.visibility = View.GONE
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val urlText = url.toString()
            return when {
                // 形态 1：本地回调（不真发请求，直接取 code；端口固定 8789）
                urlText.startsWith(
                    "http://127.0.0.1:${session.callbackPort}${ModelRegistry.OAUTH_REDIRECT_PATH}",
                    ignoreCase = true,
                ) -> {
                    onCallbackArrived(url)
                    true
                }
                // 形态 2：自定义 scheme 回调（portal 可能改走 codearts-agent://）
                url.scheme.equals(CUSTOM_CALLBACK_SCHEME, ignoreCase = true) -> {
                    onCallbackArrived(url)
                    true
                }
                // 正常页面继续加载
                url.scheme == "http" || url.scheme == "https" -> false
                // 未知 scheme（R2）：一律吞掉，避免系统弹外部 App 选择框
                else -> true
            }
        }

        /** 主文档加载失败：显示可重试错误面板 */
        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                showError("登录页加载失败：${error?.description ?: "网络错误"}，请检查网络后重试")
            }
        }
    }

    /** 解析回调 URL 中的 code / error 并发起 STS 交换 */
    private fun onCallbackArrived(uri: Uri) {
        // AtomicBoolean 防重：WebView 对同一回调可能触发多次回调
        if (!handled.compareAndSet(false, true)) return
        val code = extractParam(uri, "code")
        val error = extractParam(uri, "error")
        when {
            !code.isNullOrBlank() -> exchangeCode(code)
            !error.isNullOrBlank() -> showError("登录被拒绝：$error")
            else -> showError("回调参数异常（缺少 code），请返回重试")
        }
    }

    /** query 优先，其次解析 fragment（自定义 scheme 回调可能把参数放 # 后） */
    private fun extractParam(uri: Uri, name: String): String? {
        uri.getQueryParameter(name)?.let { return it }
        val fragment = uri.encodedFragment ?: return null
        return Uri.parse("http://placeholder/?$fragment").getQueryParameter(name)
    }

    /** code → STS authorization_code 交换 → 入库激活 → 返回主界面 */
    private fun exchangeCode(code: String) {
        Toast.makeText(this, "已收到授权码，正在交换凭证…", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        scope.launch {
            try {
                val account = stsClient.exchangeCode(code, session.context)
                // label 为空时回退 AK 前 8 位（core Account 字段注释约定）
                val labeled = if (account.label.isBlank()) {
                    account.copy(label = "hw-" + account.credentials.accessKeyId.take(8))
                } else {
                    account
                }
                // 切 IO：saveAndActivate 含 AccountRepository 同步 commit 落盘，避免主线程写盘
                withContext(Dispatchers.IO) {
                    CredentialManager.get(appContext).saveAndActivate(labeled)
                }
                Toast.makeText(
                    appContext,
                    "已登录：${labeled.label}（凭证至 ${labeled.credentials.expiresAt.ifEmpty { Iso8601.formatEpochMillis(labeled.credentials.expiration) }}）",
                    Toast.LENGTH_LONG,
                ).show()
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                handled.set(false) // 允许重试
                showError("凭证获取失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** 自定义回调 scheme（附录 A.1 uri_scheme 参数） */
        private const val CUSTOM_CALLBACK_SCHEME = "codearts-agent"

        /**
         * JS 开关（plan.md R1）：portal 登录页是前端应用，无 JS 可能白屏；
         * 安全基线默认关闭，〔实机〕白屏时临时置 true 验证（仍无 JS 桥、file access 全关）。
         */
        private const val ENABLE_JS = false
    }
}