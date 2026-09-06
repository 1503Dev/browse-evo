package dev1503.browseevo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev1503.browseevo.data.HistoryManager
import dev1503.browseevo.evo.EvoUri
import dev1503.browseevo.ui.widgets.EvoWebViewWrapper
import dev1503.browseevo.R
import kotlin.concurrent.thread
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class EvoWebViewTab(
    private val context: Context,
    private val geckoRuntime: GeckoRuntime,
    private val historyManager: HistoryManager,
) {
    private val TAG = "EvoWebViewTab"

    private val sessionStack: MutableList<GeckoSession> = mutableListOf()
    private val forwardStack: MutableList<GeckoSession> = mutableListOf()
    private val pendingSessions = mutableSetOf<GeckoSession>()
    private val errorSessions = mutableSetOf<GeckoSession>()
    private val canGoBackMap = mutableMapOf<GeckoSession, Boolean>()
    private val canGoForwardMap = mutableMapOf<GeckoSession, Boolean>()
    private val loadingSessions = mutableSetOf<GeckoSession>()
    private val titleMap = mutableMapOf<GeckoSession, String>()
    private val urlMap = mutableMapOf<GeckoSession, String>()
    private val faviconMap = mutableMapOf<GeckoSession, Bitmap>()
    private val faviconUrlMap = mutableMapOf<GeckoSession, String>()
    private val faviconCacheDir = File(context.cacheDir, "favicons")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launcherIcon: Bitmap? by lazy {
        try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_logo)
        } catch (e: Exception) {
            null
        }
    }
    private val builtInPageResourcePrefix = "resource://android/assets/built-in_page"
    private val builtInPageUrl = "$builtInPageResourcePrefix/index.html"
    private val builtInPageIndexUri = EvoUri.build(EvoUri.AUTHORITY_INDEX)
    private val browserSchemes = setOf(
        "http", "https", "file", "resource", "about", "data", "javascript", "blob", "view-source"
    )

    companion object {
        private val pendingTargetUrls = java.util.concurrent.ConcurrentHashMap<GeckoSession, String>()
    }

    var onNewTabRequested: ((GeckoSession) -> Unit)? = null
    var onTitleChanged: ((String?) -> Unit)? = null
    var onNavigationStateChanged: (() -> Unit)? = null
    var onNavigationRequested: ((GeckoSession, String) -> Unit)? = null
    var onPageStarted: ((String?) -> Unit)? = null
    var onPageStopped: ((Boolean) -> Unit)? = null
    var onProgressChanged: ((Int) -> Unit)? = null
    var onLoadingChanged: ((Boolean) -> Unit)? = null
    var onExternalSchemeRequested: ((String) -> Unit)? = null
    var onNavigateRequested: ((String) -> Unit)? = null
    var onDownloadRequested: ((url: String, filename: String?, contentLength: Long, body: java.io.InputStream?) -> Unit)? = null
    var onContextMenu: ((screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) -> Unit)? = null

    val currentUrl: String
        get() = currentSession?.let { urlMap[it] } ?: ""
    val currentTitle: String
        get() = currentSession?.let { titleMap[it] } ?: ""

    val currentSession: GeckoSession?
        get() = sessionStack.lastOrNull()
    val depth: Int get() = sessionStack.size
    val isCurrentSessionLoading: Boolean
        get() = currentSession?.let { it in loadingSessions } ?: false
    val currentFavicon: Bitmap?
        get() {
            val session = currentSession ?: return null
            if (currentUrl == builtInPageIndexUri) return launcherIcon
            return faviconMap[session]
        }
    val canGoBack: Boolean
        get() {
            val session = currentSession ?: return false
            if (canGoBackMap[session] == true) return true
            return sessionStack.size > 1
        }
    val canGoForward: Boolean
        get() {
            val session = currentSession ?: return false
            if (canGoForwardMap[session] == true) return true
            return forwardStack.isNotEmpty()
        }

    private fun notifyLoadingChanged() {
        onLoadingChanged?.invoke(isCurrentSessionLoading)
    }

    fun createSession(): GeckoSession {
        val session = GeckoSession()
        applyUserAgentOverride(session)
        session.open(geckoRuntime)
        session.navigationDelegate = createNavigationDelegate()
        session.contentDelegate = createContentDelegate()
        session.progressDelegate = createProgressDelegate()
        session.promptDelegate = createPromptDelegate()
        return session
    }

    fun adoptSession(session: GeckoSession): GeckoSession {
        applyUserAgentOverride(session)
        session.navigationDelegate = createNavigationDelegate()
        session.contentDelegate = createContentDelegate()
        session.progressDelegate = createProgressDelegate()
        session.promptDelegate = createPromptDelegate()
        seedPendingTarget(session)
        pendingSessions.add(session)
        return session
    }

    private fun seedPendingTarget(session: GeckoSession) {
        pendingTargetUrls[session]?.let { url ->
            urlMap[session] = url
            titleMap[session] = url
        }
    }

    private fun setPendingTarget(session: GeckoSession, url: String) {
        if (url.isBlank()) return
        pendingTargetUrls[session] = url
        urlMap[session] = url
        titleMap[session] = url
    }

    private fun applyUserAgentOverride(session: GeckoSession) {
        EvoWebViewWrapper.userAgentOverride?.let { session.settings.userAgentOverride = it }
    }

    fun updateAllSessionsUserAgent() {
        for (session in sessionStack) {
            applyUserAgentOverride(session)
        }
    }

    private fun createNavigationDelegate(): GeckoSession.NavigationDelegate {
        return object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val newSession = GeckoSession()
                setPendingTarget(newSession, uri)
                onNewTabRequested?.invoke(newSession)
                return GeckoResult.fromValue(newSession)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                Log.d(TAG, request.uri + " " + session.toString() + " " + request.target)
                val evoUri = EvoUri.parse(request.uri)
                val resourceUrl = evoUri?.let { translateToResource(it) }
                if (resourceUrl != null) {
                    session.loadUri(resourceUrl)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                if (session in pendingSessions) {
                    pendingSessions.remove(session)
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT) {
                    if (currentUrl.isNotEmpty() && samePageWithoutFragment(translateToEvo(request.uri), currentUrl)) {
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                    val scheme = schemeOf(request.uri)
                    if (scheme == "evo") {
                        val source = request.triggerUri ?: currentUrl
                        if (isBuiltInPageUrl(source)) {
                            evoUri?.let { handleEvoUri(it) }
                        } else {
                            Log.w(TAG, "evo navigation from non built-in page: $source")
                        }
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    if (scheme != null && scheme !in browserSchemes) {
                        Log.w(TAG, "external scheme: $scheme")
                        onExternalSchemeRequested?.invoke(request.uri)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    if (request.isRedirect) {
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                    if (isDownloadUrl(request.uri)) {
                        Log.w(TAG, "download intercepted by extension: ${request.uri}")
                        onDownloadRequested?.invoke(request.uri, null, -1L, null)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    Log.w(TAG, "target current")
                    session.stop()
                    val newSession = createSession()
                    pendingSessions.add(newSession)
                    loadingSessions.add(newSession)
                    sessionStack.add(newSession)
                    forwardStack.clear()
                    setPendingTarget(newSession, translateToEvo(request.uri))
                    onNavigationStateChanged?.invoke()
                    onTitleChanged?.invoke(currentTitle)
                    notifyLoadingChanged()
                    onNavigationRequested?.invoke(newSession, request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackMap[session] = canGoBack
                if (session == currentSession) {
                    onNavigationStateChanged?.invoke()
                }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canGoForwardMap[session] = canGoForward
                if (session == currentSession) {
                    onNavigationStateChanged?.invoke()
                }
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                rejectedPermissions: Boolean
            ) {
                if (url != null) {
                    if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) {
                        if (pendingTargetUrls.containsKey(session) || !urlMap[session].isNullOrEmpty()) {
                            return
                        }
                    } else {
                        pendingTargetUrls.remove(session)
                    }
                    val oldUrl = urlMap[session] ?: ""
                    val isDataErrorPage = url.startsWith("data:", ignoreCase = true) && session in errorSessions
                    val displayed = if (isDataErrorPage) {
                        EvoUri.build(EvoUri.AUTHORITY_ERROR)
                    } else {
                        if (!url.startsWith("data:", true)) errorSessions.remove(session)
                        translateToEvo(url)
                    }
                    urlMap[session] = displayed
                    if (session == currentSession) {
                        fetchFavicon(session, url)
                        if (shouldRecordUrl(url) && withoutFragment(url).isNotEmpty() && withoutFragment(oldUrl) != withoutFragment(url)) {
                            historyManager.record(url, currentTitle)
                        }
                    }
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                Log.e(TAG, "onLoadError uri=$uri category=${error.category} code=${error.code}")
                val failedUri = uri ?: return null
                val scheme = schemeOf(failedUri) ?: return null
                if (scheme != "http" && scheme != "https") return null
                val html = buildErrorHtml(errorCodeName(error.code), error.code) ?: return null
                val encoded = android.util.Base64.encodeToString(
                    html.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                errorSessions.add(session)
                pendingSessions.add(session)
                return GeckoResult.fromValue("data:text/html;charset=utf-8;base64,$encoded")
            }
        }
    }

    private fun createContentDelegate(): GeckoSession.ContentDelegate {
        return object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                var displayed = title ?: ""
                if (displayed.isBlank() || displayed.equals("about:blank", ignoreCase = true)) {
                    pendingTargetUrls[session]?.let { displayed = it }
                } else {
                    pendingTargetUrls.remove(session)
                }
                titleMap[session] = displayed
                if (session == currentSession) {
                    onTitleChanged?.invoke(displayed)
                    val url = currentUrl
                    if (shouldRecordUrl(url)) {
                        historyManager.record(url, displayed)
                    }
                }
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                Log.w(TAG, "onExternalResponse: ${response.uri} headers=${response.headers}")
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val disposition = response.headers["Content-Disposition"]
                val filename = disposition?.let { header ->
                    Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""", RegexOption.IGNORE_CASE)
                        .find(header)?.groupValues?.get(1)
                }?.let { Utils.decodeUrlEncoded(it) }
                // body 是触发下载时的原始响应流：交给下载器直接落盘，
                // 避免对（可能一次性的）下载地址发起第二次请求。
                onDownloadRequested?.invoke(response.uri, filename, contentLength, response.body)
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                onContextMenu?.invoke(screenX, screenY, element)
            }
        }
    }

    private fun createPromptDelegate(): GeckoSession.PromptDelegate {
        return object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                filePrompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val launched = FileChooserHelper.launch(context, filePrompt, result)
                if (!launched) {
                    Log.w(TAG, "file chooser unavailable, dismiss prompt")
                    return GeckoResult.fromValue(filePrompt.dismiss())
                }
                return result
            }
        }
    }

    private fun createProgressDelegate(): GeckoSession.ProgressDelegate {
        return object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                loadingSessions.add(session)
                if (session == currentSession) {
                    onPageStarted?.invoke(url)
                    notifyLoadingChanged()
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                loadingSessions.remove(session)
                if (!success) {
                    val url = urlMap[session] ?: "unknown"
                    Log.e(TAG, "Page load failed: url=$url")
                }
                if (session == currentSession) {
                    onPageStopped?.invoke(success)
                    notifyLoadingChanged()
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (session == currentSession) {
                    onProgressChanged?.invoke(progress)
                }
            }
        }
    }

    fun pushSession(session: GeckoSession) {
        sessionStack.add(session)
        forwardStack.clear()
        onNavigationStateChanged?.invoke()
        onTitleChanged?.invoke(currentTitle)
        notifyLoadingChanged()
    }

    fun goBack(): GeckoSession? {
        val session = currentSession ?: return null
        if (canGoBackMap[session] == true) {
            session.goBack()
            return session
        }
        if (!canGoBack) return null
        val removed = sessionStack.removeAt(sessionStack.lastIndex)
        forwardStack.add(removed)
        onNavigationStateChanged?.invoke()
        onTitleChanged?.invoke(currentTitle)
        notifyLoadingChanged()
        return sessionStack.lastOrNull()
    }

    fun goForward(): GeckoSession? {
        val session = currentSession ?: return null
        if (canGoForwardMap[session] == true) {
            session.goForward()
            return session
        }
        if (!canGoForward) return null
        val restored = forwardStack.removeAt(forwardStack.lastIndex)
        sessionStack.add(restored)
        onNavigationStateChanged?.invoke()
        onTitleChanged?.invoke(currentTitle)
        notifyLoadingChanged()
        return restored
    }

    fun loadUrl(url: String) {
        val session = currentSession ?: createSession().also { pushSession(it) }
        pendingSessions.add(session)
        session.loadUri(url)
        setPendingTarget(session, url)
        onTitleChanged?.invoke(currentTitle)
    }

    fun markPending(session: GeckoSession) {
        pendingSessions.add(session)
    }

    private fun isDownloadUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
        val extensions = setOf(
            "apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "exe", "msi",
            "dmg", "iso", "bin", "ipa", "jar", "crx", "epub", "apk.1"
        )
        return extensions.any { path.endsWith(".$it") }
    }

    private fun withoutFragment(uri: String): String {
        val index = uri.indexOf('#')
        return if (index >= 0) uri.substring(0, index) else uri
    }

    private fun shouldRecordUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        return true
    }

    private fun schemeOf(url: String): String? {
        val match = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""").find(url) ?: return null
        return match.value.dropLast(1).lowercase()
    }

    private fun handleEvoUri(evoUri: EvoUri) {
        when {
            evoUri.isInterface -> handleEvoInterface(evoUri)
            else -> Log.w(TAG, "unknown evo uri: ${evoUri.raw}")
        }
    }

    private fun handleEvoInterface(evoUri: EvoUri) {
        when (evoUri.action) {
            EvoUri.ACTION_NAVIGATE -> {
                val params = evoUri.params ?: ""
                val value = try {
                    URLDecoder.decode(params, "UTF-8")
                } catch (e: Exception) {
                    params
                }
                if (value.isNotEmpty()) {
                    onNavigateRequested?.invoke(value)
                }
            }
            else -> Log.w(TAG, "unknown evo interface action: ${evoUri.action}")
        }
    }

    private fun translateToResource(evoUri: EvoUri): String? {
        return when {
            evoUri.isIndex -> builtInPageUrl
            evoUri.isError -> "$builtInPageResourcePrefix/error.html"
            evoUri.isPage -> {
                val name = evoUri.action ?: return null
                "$builtInPageResourcePrefix/$name.html"
            }
            else -> null
        }
    }

    private fun translateToEvo(url: String): String {
        val marker = "/built-in_page/"
        val index = url.indexOf(marker)
        if (index >= 0) {
            val file = url.substring(index + marker.length).substringBefore('?').substringBefore('#')
            if (file == "index.html") return builtInPageIndexUri
            if (file == "error.html") return EvoUri.build(EvoUri.AUTHORITY_ERROR)
            if (file.endsWith(".html")) {
                return EvoUri.build(EvoUri.AUTHORITY_PAGE, file.removeSuffix(".html"))
            }
        }
        return url
    }

    private fun errorCodeName(code: Int): String {
        return try {
            WebRequestError::class.java.fields
                .filter {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.type == Int::class.javaPrimitiveType &&
                        it.name.startsWith("ERROR_")
                }
                .firstOrNull { it.getInt(null) == code }
                ?.name
                ?: code.toString()
        } catch (e: Exception) {
            code.toString()
        }
    }

    private fun buildErrorHtml(name: String, code: Int): String? {
        return try {
            var html = readAssetText("built-in_page/error.html")
            html = inlineLocalAssets(html)
            val safeName = name.replace("\\", "\\\\").replace("\"", "\\\"")
            val injection = "<script>window.EVO_ERROR = {name:\"$safeName\",code:$code};</script>"
            if (html.contains("</head>", ignoreCase = true)) {
                html.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$injection</head>")
            } else {
                injection + html
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun inlineLocalAssets(html: String): String {
        val linkRegex = Regex(
            "<link\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        )
        val scriptRegex = Regex(
            "<script\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>\\s*</script>",
            RegexOption.IGNORE_CASE
        )
        var result = linkRegex.replace(html) { match ->
            val content = readAssetIfLocal(match.groupValues[1])
            if (content != null) "<style>\n$content\n</style>" else match.value
        }
        result = scriptRegex.replace(result) { match ->
            val content = readAssetIfLocal(match.groupValues[1])
            if (content != null) "<script>\n$content\n</script>" else match.value
        }
        return result
    }

    private fun readAssetIfLocal(ref: String): String? {
        return try {
            if (ref.startsWith("http://", true) ||
                ref.startsWith("https://", true) ||
                ref.startsWith("data:", true) ||
                ref.startsWith("//")
            ) return null
            val clean = ref.substringBefore('?').substringBefore('#')
                .removePrefix("./")
                .removePrefix("/")
            readAssetText("built-in_page/$clean")
        } catch (e: Exception) {
            null
        }
    }

    private fun readAssetText(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
    private fun isBuiltInPageUrl(url: String): Boolean =
        Utils.isBuiltInPage(url, context.filesDir.absolutePath)

    private fun samePageWithoutFragment(uriA: String, uriB: String): Boolean =
        withoutFragment(uriA) == withoutFragment(uriB)

    private fun fetchFavicon(session: GeckoSession, pageUrl: String) {
        if (!pageUrl.startsWith("http://", ignoreCase = true) && !pageUrl.startsWith("https://", ignoreCase = true)) return
        if (faviconUrlMap[session] == pageUrl) return
        faviconUrlMap[session] = pageUrl
        thread {
            val bitmap = loadCachedFavicon(pageUrl)
                ?: findFavicon(pageUrl)?.also { saveFavicon(pageUrl, it) }
            mainHandler.post {
                if (bitmap != null) {
                    faviconMap[session] = bitmap
                } else {
                    faviconUrlMap.remove(session)
                }
            }
        }
    }

    private fun hostOf(url: String): String? = try { URL(url).host } catch (e: Exception) { null }

    private fun loadCachedFavicon(pageUrl: String): Bitmap? {
        val host = hostOf(pageUrl) ?: return null
        val file = File(faviconCacheDir, "$host.png")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun saveFavicon(pageUrl: String, bitmap: Bitmap) {
        val host = hostOf(pageUrl) ?: return
        try {
            faviconCacheDir.mkdirs()
            FileOutputStream(File(faviconCacheDir, "$host.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) {
        }
    }

    private fun findFavicon(pageUrl: String): Bitmap? {
        val linkIcon = findLinkIconUrl(pageUrl)
        linkIcon?.let { downloadBitmap(it) }?.let { return it }
        return fallbackIconUrl(pageUrl)?.let { downloadBitmap(it) }
    }

    private fun findLinkIconUrl(pageUrl: String): String? {
        return try {
            val conn = URL(pageUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            val html = readHtmlHead(conn)
            conn.disconnect()
            val tag = Regex("""<link\b[^>]*\brel\s*=\s*["'](?:shortcut\s+)?icon["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html)?.value
            val href = tag?.let {
                Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
            }
            if (href != null) URL(URL(pageUrl), href).toString() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun fallbackIconUrl(pageUrl: String): String? {
        return try {
            val u = URL(pageUrl)
            "${u.protocol}://${u.host}/favicon.ico"
        } catch (e: Exception) {
            null
        }
    }

    private fun readHtmlHead(conn: HttpURLConnection): String {
        return try {
            conn.inputStream.use { stream ->
                val reader = stream.bufferedReader(Charsets.UTF_8)
                val buf = CharArray(131072)
                val count = reader.read(buf)
                if (count > 0) String(buf, 0, count) else ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            val bitmap = if (conn.responseCode == 200) {
                BitmapFactory.decodeStream(conn.inputStream)
            } else null
            conn.disconnect()
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun reload() {
        val session = currentSession ?: return
        pendingSessions.add(session)
        session.reload()
    }

    fun stopLoading() {
        currentSession?.stop()
    }

    fun getSessionAt(index: Int): GeckoSession? = sessionStack.getOrNull(index)

    fun getAllSessions(): List<GeckoSession> = sessionStack.toList()

    fun close() {
        for (session in sessionStack) {
            pendingTargetUrls.remove(session)
        }
        sessionStack.clear()
        forwardStack.clear()
        pendingSessions.clear()
        errorSessions.clear()
        canGoBackMap.clear()
        canGoForwardMap.clear()
        loadingSessions.clear()
        titleMap.clear()
        urlMap.clear()
        faviconMap.clear()
        faviconUrlMap.clear()
    }
}
