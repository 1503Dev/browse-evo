package dev1503.browseevo.ui.viewmodel.browsermain

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import dev1503.browseevo.EvoWebViewTab
import dev1503.browseevo.MainActivity
import dev1503.browseevo.R
import dev1503.browseevo.Utils
import dev1503.browseevo.data.BookmarkManager
import dev1503.browseevo.download.DownloadController
import dev1503.browseevo.download.DownloadNotifier
import dev1503.browseevo.download.DownloadRecord
import dev1503.browseevo.ui.widgets.BottomSheetDialogBuilder
import dev1503.browseevo.ui.widgets.EvoPopupMenu
import dev1503.browseevo.ui.widgets.MenuBottomSheet
import dev1503.materialpopups.widgets.menuitem.MenuItem
import dev1503.materialpopups.widgets.popup.MenuPopup
import dev1503.materialpopups.widgets.popup.Popup
import org.mozilla.geckoview.GeckoSession
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

abstract class CommonBrowserMainViewModel(activity: MainActivity): BrowserMainViewModel(activity) {
    protected abstract val layoutResId: Int

    protected lateinit var btnTabs: MaterialButton
    protected lateinit var urlEditOverlay: LinearLayout
    protected lateinit var btnGo: MaterialButton
    protected lateinit var layoutTopBar: LinearLayout
    protected lateinit var layoutBottomBar: LinearLayout
    protected lateinit var urlEditBar: LinearLayout
    protected lateinit var textTabCount: TextView

    private var userEditedUrl = false
    private var programmaticSet = false

    private val overlayStack = ArrayDeque<View>()

    protected open val requestFocusOnUrlEditShown: Boolean = true

    protected open val useSnackbarAnchor: Boolean = true

    protected open val closeUrlEditOverlayOnBack: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        _view = FrameLayout.inflate(activity, layoutResId, null)
        val webViewContainerInner = _view.findViewById<LinearLayout>(R.id.webViewContainerInner)
        webViewContainerInner.addView(webViewWrapper.view)

        btnTabs = _view.findViewById(R.id.btnTabs)
        urlEditOverlay = _view.findViewById(R.id.urlEditOverlayRoot)
        btnGo = _view.findViewById(R.id.btnGo)
        layoutTopBar = _view.findViewById(R.id.layoutTopBar)
        layoutBottomBar = _view.findViewById(R.id.layoutBottomBar)
        urlEditBar = _view.findViewById(R.id.layoutUrlEditOverlay)
        textTabCount = _view.findViewById(R.id.textTabCount)

        super.onCreate(savedInstanceState)

        webViewWrapper.onDownloadRequested = { url, filename, length, body ->
            showDownloadConfirm(url, filename, length, body)
        }

        btnTabs.setOnClickListener { showTabsSheet() }
        btnMenu.setOnClickListener { showMenu() }
        btnHome.setOnClickListener {
            hideUrlEditOverlay()
            webViewWrapper.loadBuiltInPage()
        }

        webViewWrapper.onExternalSchemeRequested = { uri -> promptExternalScheme(uri) }
        webViewWrapper.onNavigateRequested = { value -> navigate(value) }
        webViewWrapper.onTabCreated = { bounceTabsButton() }

        urlEditOverlay.visibility = View.GONE
        applyDefaultColor()

        textWebSiteTitle.setOnClickListener {
            val text = editTextUrl.text?.toString()?.trim().orEmpty()
            if (!(userEditedUrl && text.isNotEmpty())) {
                programmaticSet = true
                val currentUrl = webViewWrapper.activeTab?.currentUrl.orEmpty()
                editTextUrl.setText(urlTextForEditOverlay(currentUrl))
                programmaticSet = false
                userEditedUrl = false
            }
            showUrlEditOverlay()
        }
        textWebSiteTitle.setOnLongClickListener {
            showTitleBarMenu()
            true
        }
        urlEditOverlay.setOnClickListener { hideUrlEditOverlay() }
        btnGo.setOnClickListener { onGoClicked() }

        editTextUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!programmaticSet) {
                    userEditedUrl = true
                }
            }
        })
        editTextUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                onGoClicked()
                true
            } else {
                false
            }
        }
        updateTabCountBadge()
        onViewReady()
    }

    protected open fun onViewReady() {
    }

    protected fun showOverlay(view: View) {
        val root = _view as? ViewGroup ?: return
        overlayStack.addLast(view)
        root.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    protected fun dismissOverlay(view: View): Boolean {
        if (!overlayStack.contains(view)) return false
        overlayStack.remove(view)
        (_view as? ViewGroup)?.removeView(view)
        return true
    }

    protected fun dismissTopOverlay(): Boolean {
        val view = overlayStack.lastOrNull() ?: return false
        return dismissOverlay(view)
    }

    protected fun dismissAllOverlays() {
        while (overlayStack.isNotEmpty()) {
            dismissTopOverlay()
        }
    }

    private var lastBackExitAttempt = 0L

    final override fun onResume() {
        dismissAllOverlays()
        super.onResume()
    }

    override fun handleBackPressed(): Boolean {
        if (dismissTopOverlay()) return true
        if (closeUrlEditOverlayOnBack &&
            ::urlEditOverlay.isInitialized &&
            urlEditOverlay.visibility == View.VISIBLE
        ) {
            hideUrlEditOverlay()
            return true
        }
        if (super.handleBackPressed()) return true
        val now = System.currentTimeMillis()
        return if (now - lastBackExitAttempt < 2000L) {
            false
        } else {
            lastBackExitAttempt = now
            Toast.makeText(activity, "再按一次退出", Toast.LENGTH_SHORT).show()
            true
        }
    }

    protected fun updateTabCountBadge() {
        if (!::textTabCount.isInitialized) return
        val count = webViewWrapper.getTabCount()
        textTabCount.text = count.toString()
        textTabCount.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (count >= 10) 9f else 11f
        )
    }

    private fun urlTextForEditOverlay(url: String): String {
        if (isBuiltInPage(url)) return ""
        if (isBingSearchUrl(url)) {
            try {
                val query = Uri.parse(url).getQueryParameter("q")
                if (!query.isNullOrEmpty()) return query
            } catch (e: Exception) {
            }
        }
        return url
    }

    private fun isBingSearchUrl(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        val parsed = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return false
        }
        val host = parsed.host?.lowercase() ?: return false
        val isBingHost = host == "bing.com" || host.endsWith(".bing.com")
        val path = parsed.path ?: ""
        return isBingHost && path.contains("search", ignoreCase = true) && parsed.getQueryParameter("q") != null
    }

    private fun onGoClicked() {
        val input = editTextUrl.text?.toString()?.trim().orEmpty()
        val current = webViewWrapper.activeTab?.currentUrl.orEmpty()
        if (input.isNotEmpty() && input != current) {
            navigate(input)
            userEditedUrl = false
        }
        hideUrlEditOverlay()
    }

    private fun navigate(input: String) {
        val scheme = schemeOf(input)
        when (scheme) {
            null -> {
                if (looksLikeHost(input)) {
                    webViewWrapper.goToUrl("http://$input")
                } else {
                    searchWithBing(input)
                }
            }
            "http", "https", "file" -> webViewWrapper.goToUrl(input)
            else -> promptExternalScheme(input)
        }
    }

    private fun promptExternalScheme(input: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(input))
        if (intent.resolveActivity(activity.packageManager) != null) {
            val builder = Snackbar.make(_view, "是否交给外部应用打开？", Snackbar.LENGTH_LONG)
            if (useSnackbarAnchor) builder.setAnchorView(layoutBottomBar)
            builder.setAction("允许") {
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    webViewWrapper.goToUrl(input)
                }
            }
            builder.show()
        } else {
            val builder = Snackbar.make(_view, "没有应用可以处理该地址", Snackbar.LENGTH_SHORT)
            if (useSnackbarAnchor) builder.setAnchorView(layoutBottomBar)
            builder.show()
        }
    }

    final override fun onPageStarted() {
        super.onPageStarted()
        userEditedUrl = false
    }

    private fun showUrlEditOverlay(showKeyboard: Boolean = true) {
        urlEditOverlay.alpha = 0f
        urlEditOverlay.visibility = View.VISIBLE
        urlEditOverlay.animate()
            .alpha(1f)
            .setDuration(200L)
            .start()
        if (requestFocusOnUrlEditShown && showKeyboard) editTextUrl.requestFocus()
        editTextUrl.post {
            if (!requestFocusOnUrlEditShown || !showKeyboard) return@post
            editTextUrl.selectAll()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editTextUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideUrlEditOverlay() {
        if (urlEditOverlay.visibility != View.VISIBLE) return
        editTextUrl.clearFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editTextUrl.windowToken, 0)
        urlEditOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction { urlEditOverlay.visibility = View.GONE }
            .start()
    }

    private fun showTitleBarMenu() {
        val popup = EvoPopupMenu(textWebSiteTitle)
        popup.menu.add("复制链接", R.drawable.content_copy_24px)
        popup.menu.add("复制标题")
        popup.menu.addDivider()
        popup.menu.add("粘贴", R.drawable.content_paste_24px)
        popup.menu.add("粘贴并前往", R.drawable.content_paste_go_24px)
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "复制链接" -> copyCurrentLink()
                "复制标题" -> copyCurrentTitle()
                "粘贴" -> pasteFromClipboard()
                "粘贴并前往" -> pasteFromClipboardAndGo()
            }
            true
        }
        popup.show()
    }

    private fun copyCurrentLink() {
        val url = webViewWrapper.activeTab?.currentUrl.orEmpty()
        if (url.isEmpty() || isBuiltInPage(url)) {
            Toast.makeText(activity, "没有可复制的链接", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard("url", url)
    }

    private fun copyCurrentTitle() {
        val title = webViewWrapper.activeTab?.currentTitle.orEmpty().trim()
        if (title.isEmpty()) {
            Toast.makeText(activity, "没有可复制的标题", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard("title", title)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun clipboardText(): String? {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(activity)?.toString()
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pasteFromClipboard() {
        val text = clipboardText()
        if (text == null) {
            Toast.makeText(activity, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        programmaticSet = true
        editTextUrl.setText(text)
        programmaticSet = false
        userEditedUrl = false
        showUrlEditOverlay(showKeyboard = false)
    }

    private fun pasteFromClipboardAndGo() {
        val text = clipboardText() ?: run {
            Toast.makeText(activity, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        navigate(text)
    }

    final override fun onLoadingStateChanged(loading: Boolean) {
        super.onLoadingStateChanged(loading)
        if (!loading) {
            extractAndApplyWebColor()
        }
    }

    final override fun onActiveTabChanged(index: Int) {
        super.onActiveTabChanged(index)
        updateTabCountBadge()
        extractAndApplyWebColor()
    }

    private fun extractAndApplyWebColor() {
        val url = webViewWrapper.activeTab?.currentUrl.orEmpty()
        if (url.isEmpty() || url.equals("about:blank", ignoreCase = true)) {
            applyDefaultColor()
            return
        }
        val result = webViewWrapper.capturePixels()
        if (result == null) {
            applyDefaultColor()
            return
        }
        result.accept(
            { bitmap ->
                if (bitmap != null) {
                    val color = dominantTopColor(bitmap)
                    bitmap.recycle()
                    applyWebColor(color)
                } else {
                    applyDefaultColor()
                }
            },
            { applyDefaultColor() }
        )
    }

    private fun dominantTopColor(bitmap: Bitmap): Int {
        val stripHeight = maxOf(1, bitmap.height / 8)
        val pixels = IntArray(bitmap.width * stripHeight)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, stripHeight)
        val counts = HashMap<Int, Int>()
        var maxCount = 0
        var maxColor = 0
        var total = 0
        for (i in pixels.indices step 4) {
            val p = pixels[i]
            if ((p shr 24) and 0xFF < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val key = (r shr 4) shl 8 or ((g shr 4) shl 4) or (b shr 4)
            val c = (counts[key] ?: 0) + 1
            counts[key] = c
            total++
            if (c > maxCount) {
                maxCount = c
                maxColor = key
            }
        }
        if (total == 0) return Color.WHITE
        val r = (maxColor shr 8 and 0xF) shl 4 or 8
        val g = (maxColor shr 4 and 0xF) shl 4 or 8
        val b = (maxColor and 0xF) shl 4 or 8
        return Color.rgb(r, g, b)
    }

    private fun isLightColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.5
    }

    private fun applyWebColor(color: Int) {
        if (!::layoutTopBar.isInitialized) return
        val textColor = if (isLightColor(color)) Color.BLACK else Color.WHITE
        applyBarColors(color, textColor)
    }

    fun refreshThemeColors() {
        applyDefaultColor()
    }

    private fun applyDefaultColor() {
        if (!::layoutTopBar.isInitialized) return
        val background = resolveColor(android.R.attr.colorBackground)
        val textColor = if (isLightColor(background)) Color.BLACK else Color.WHITE
        applyBarColors(background, textColor)
    }

    private fun applyBarColors(color: Int, textColor: Int) {
        layoutTopBar.setBackgroundColor(color)
        layoutBottomBar.setBackgroundColor(color)
        urlEditBar.setBackgroundColor(color)
        textWebSiteTitle.setTextColor(textColor)
        listOf(
            R.id.btnWebsiteInfo, R.id.btnReload, R.id.btnGoBack, R.id.btnGoForward,
            R.id.btnHome, R.id.btnTabs, R.id.btnMenu, R.id.btnGo, R.id.btnSearchEngine
        ).forEach { id ->
            _view.findViewById<MaterialButton>(id)?.let { tintButton(it, textColor) }
        }
        if (::textTabCount.isInitialized) textTabCount.setTextColor(textColor)
        editTextUrl.setTextColor(textColor)
    }

    private fun resolveColor(attr: Int): Int {
        val typedValue = TypedValue()
        if (_view.context.theme.resolveAttribute(attr, typedValue, true)) {
            val type = typedValue.type
            if (type >= TypedValue.TYPE_FIRST_COLOR_INT && type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data or 0xFF000000.toInt()
            }
        }
        val isNight = (_view.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isNight) 0xFF141218.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun tintButton(button: MaterialButton, color: Int) {
        val disabled = Color.argb(96, Color.red(color), Color.green(color), Color.blue(color))
        val stateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(color, disabled)
        )
        button.setTextColor(stateList)
        button.iconTint = stateList
    }

    private fun bounceTabsButton() {
        if (!::btnTabs.isInitialized) return
        val container = btnTabs.parent as? View ?: return
        val distance = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics
        )
        container.animate().cancel()
        container.translationY = 0f
        container.animate()
            .translationYBy(-distance)
            .setDuration(140L)
            .withEndAction {
                container.animate()
                    .translationY(0f)
                    .setDuration(220L)
                    .start()
            }
            .start()
    }

    protected fun requestDownloadPermissions() {
        val storagePermissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val missing = storagePermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), 1001)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${activity.packageName}")
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notificationPermission = "android.permission.POST_NOTIFICATIONS"
            if (ContextCompat.checkSelfPermission(activity, notificationPermission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(notificationPermission), 1002
                )
            }
        }
    }

    /**
     * 开启"自动开始下载"时跳过确认对话框直接处理下载请求；
     * 返回 false 表示未启用，调用方继续走询问流程。
     * [body] 为触发下载时的原始响应流，直接落盘以复用该连接。
     */
    protected fun maybeAutoDownload(url: String, contentLength: Long, body: java.io.InputStream?): Boolean {
        if (Utils.neoSettings?.getBoolean(Utils.KEY_AUTO_DOWNLOAD, false) != true) return false
        requestDownloadPermissions()
        DownloadController.init(activity)
        DownloadController.autoDownload(activity, url, null, contentLength, body)
        val message = "已自动开始下载"
        val snackbar = Snackbar.make(_view, message, Snackbar.LENGTH_LONG)
        if (useSnackbarAnchor) snackbar.setAnchorView(layoutBottomBar)
        snackbar.setAction("查看") {
            Utils.openDownloadManagerActivity(activity)
        }
        snackbar.show()
        return true
    }

    override fun handleContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
        val src = resolveMediaUri(element)
        val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE &&
            src != null && (
                src.startsWith("http://", true) ||
                    src.startsWith("https://", true) ||
                    src.startsWith("data:", true)
                )
        if (!isImage) {
            super.handleContextMenu(element)
            return
        }
        val popup = MenuPopup(activity)
        popup.addMenuItem(
            MenuItem("在新标签页中打开图片") {
                webViewWrapper.createTab().loadUrl(src)
                webViewWrapper.switchToTab(webViewWrapper.getTabCount() - 1)
            }.setIcon(R.drawable.open_in_new_24px)
        )
        popup.addMenuItem(
            MenuItem("下载图片") {
                if (src.startsWith("data:", true)) {
                    downloadDataImage(src)
                } else {
                    webViewWrapper.downloadUrl(src)
                }
            }.setIcon(R.drawable.download_24px)
        )
        if (element.linkUri != null) {
            popup.addDivider()
            addLinkMenuItems(popup, element.linkUri.toString())
        }
        popup.build()
            .setAnimation(Popup.ANIM_FADE)
            .showAt(webViewWrapper.lastPointerX, webViewWrapper.lastPointerY)
    }

    private fun resolveMediaUri(element: GeckoSession.ContentDelegate.ContextElement): String? {
        val src = element.srcUri ?: return null
        if (src.contains("://") || src.startsWith("data:", true) || src.startsWith("blob:", true)) return src
        val base = element.baseUri ?: return src
        return try {
            java.net.URL(java.net.URL(base), src).toString()
        } catch (e: Exception) {
            src
        }
    }

    private fun downloadDataImage(dataUri: String) {
        val bytes = decodeDataImage(dataUri) ?: run {
            Toast.makeText(activity, "无法解析该图片", Toast.LENGTH_SHORT).show()
            return
        }
        val ext = dataUri.substringBefore(';').substringAfter(':', "")
            .substringAfter('/', "").lowercase()
            .takeIf { Regex("[a-z0-9]+").matches(it) } ?: "png"
        showDownloadConfirm(
            dataUri,
            "image_${System.currentTimeMillis()}.$ext",
            bytes.size.toLong(),
            java.io.ByteArrayInputStream(bytes)
        )
    }

    private fun decodeDataImage(dataUri: String): ByteArray? {
        if (!dataUri.startsWith("data:", true)) return null
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        val meta = dataUri.substring(5, comma)
        val payload = dataUri.substring(comma + 1)
        return try {
            if (meta.contains("base64", true)) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    protected open fun showDownloadConfirm(url: String, filename: String?, contentLength: Long, body: java.io.InputStream?) {
        if (maybeAutoDownload(url, contentLength, body)) return
        requestDownloadPermissions()
        DownloadController.init(activity)

        val contentView = LayoutInflater.from(activity).inflate(R.layout.view_add_download, null)
        val editFilename = contentView.findViewById<TextInputEditText>(R.id.editDownloadFilename)
        val sizeText = contentView.findViewById<TextView>(R.id.textDownloadSize)
        editFilename.setText(filename ?: DownloadController.suggestFilename(url))
        if (contentLength > 0) {
            sizeText.text = "文件大小: ${DownloadNotifier.formatBytes(contentLength)}"
        }

        val client = OkHttpClient()
        // 已有原始响应流时不发 HEAD 请求：那是对下载地址的额外请求，
        // 可能消耗掉一次性链接。
        if (body == null) {
            client.newBuilder().build().newCall(
                Request.Builder().url(url).head().build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val length = it.header("Content-Length")?.toLongOrNull() ?: -1L
                        Handler(Looper.getMainLooper()).post {
                            if (length > 0) {
                                sizeText.text = "文件大小: ${DownloadNotifier.formatBytes(length)}"
                            }
                        }
                    }
                }
            })
        }

        var consumedBody = false
        var dialog: AlertDialog? = null
        val dismiss = { dialog?.dismiss() }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("是否下载文件")
            .setView(contentView)
            .setNeutralButton("复制链接", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                Toast.makeText(activity, "已复制链接", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editFilename.text?.toString()?.trim()
                    .takeUnless { it.isNullOrEmpty() } ?: DownloadController.suggestFilename(url)
                val file = DownloadController.uniqueDestination(DownloadController.publicDownloadDir(), name)
                val record = DownloadRecord(
                    filename = file.name,
                    url = url,
                    path = file.absolutePath,
                    totalBytes = contentLength,
                    savedBytes = 0L,
                    paused = false,
                    timestamp = 0L
                )
                consumedBody = true
                DownloadController.start(activity, record, body)
                val snackbar = Snackbar.make(_view, "已开始下载", Snackbar.LENGTH_LONG)
                if (useSnackbarAnchor) snackbar.setAnchorView(layoutBottomBar)
                snackbar.setAction("查看") {
                    Utils.openDownloadManagerActivity(activity)
                }
                snackbar.show()
                dismiss()
            }
        }
        dialog.setOnDismissListener {
            // 未交给下载器的响应流直接关闭，释放连接
            if (!consumedBody) {
                runCatching { body?.close() }
            }
        }
        dialog.show()
    }

    protected open fun showMenu() {
        MenuBottomSheet(
            activity,
            onAddBookmarkClick = { showAddBookmark(currentPageTitle(), currentPageUrl()) },
            onShareClick = { Utils.shareUrl(activity, currentPageUrl(), currentPageTitle()) },
            onViewSourceClick = { viewCurrentPageSource() },
            onDarkModeToggled = { refreshThemeColors() },
            onPcModeToggled = {
                webViewWrapper.updateAllSessionsUserAgent()
                webViewWrapper.reload()
            }
        ).show()
    }

    protected fun viewCurrentPageSource() {
        val url = currentPageUrl()
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            webViewWrapper.goToUrl("view-source:$url")
        }
    }

    protected fun currentPageTitle(): String =
        webViewWrapper.activeTab?.currentTitle.orEmpty()

    protected fun currentPageUrl(): String =
        webViewWrapper.activeTab?.currentUrl.orEmpty()

    protected fun saveBookmarkWithDuplicateCheck(title: String, address: String, dir: String): Boolean {
        val manager = BookmarkManager(activity)
        val existing = manager.loadAll()
        if (existing.any { it.dir == dir && it.title == title }) {
            Toast.makeText(activity, "标题已存在", Toast.LENGTH_SHORT).show()
            return false
        }
        if (existing.any { it.address == address }) {
            Toast.makeText(activity, "地址已存在", Toast.LENGTH_SHORT).show()
            return false
        }
        return if (manager.add(BookmarkManager.Bookmark(title, address, dir))) {
            Toast.makeText(activity, "添加成功", Toast.LENGTH_SHORT).show()
            true
        } else {
            false
        }
    }

    protected open fun showAddBookmark(title: String, address: String) {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.view_add_bookmark, null)
        val editTitle = contentView.findViewById<TextInputEditText>(R.id.editBookmarkTitle)
        val editAddress = contentView.findViewById<TextInputEditText>(R.id.editBookmarkAddress)
        val editDir = contentView.findViewById<TextInputEditText>(R.id.editBookmarkDir)
        editTitle.setText(title)
        editAddress.setText(address)
        editDir.setText("/")
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("添加书签")
            .setView(contentView)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val saved = saveBookmarkWithDuplicateCheck(
                    editTitle.text?.toString()?.trim().orEmpty(),
                    editAddress.text?.toString()?.trim().orEmpty(),
                    BookmarkManager.sanitizeDir(editDir.text?.toString()?.trim().orEmpty())
                )
                if (saved) dialog.dismiss()
            }
        }
        dialog.show()
    }

    protected open fun showTabsSheet() {
        val sheetView = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_tabs, null)
        val recycler = sheetView.findViewById<RecyclerView>(R.id.recyclerTabs)
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.isNestedScrollingEnabled = true
        val metrics = activity.resources.displayMetrics
        recycler.layoutParams = recycler.layoutParams.apply {
            height = (metrics.heightPixels * 0.5).toInt()
        }
        var dialog: BottomSheetDialog? = null
        fun dismiss() {
            dialog?.dismiss()
        }
        recycler.adapter = TabsAdapter(
            tabs = webViewWrapper.formalTabs,
            activeIndexProvider = { webViewWrapper.getActiveTabIndex() },
            onItemClick = { index ->
                webViewWrapper.switchToTab(index)
                dismiss()
            },
            onCloseItemClick = { index ->
                webViewWrapper.closeTab(index)
                updateTabCountBadge()
                recycler.adapter?.notifyDataSetChanged()
            }
        )
        sheetView.findViewById<MaterialButton>(R.id.btnNewTab).setOnClickListener {
            dismiss()
            webViewWrapper.createTab()
            webViewWrapper.loadBuiltInPage()
        }
        dialog = BottomSheetDialogBuilder(activity)
            .title("Tabs")
            .view(sheetView)
            .show()
    }

    protected class TabsAdapter(
        private val tabs: List<EvoWebViewTab>,
        private val activeIndexProvider: () -> Int,
        private val onItemClick: (Int) -> Unit,
        private val onCloseItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<TabsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgTabIcon)
            val textTitle: TextView = view.findViewById(R.id.textTabTitle)
            val textUrl: TextView = view.findViewById(R.id.textTabUrl)
            val btnClose: MaterialButton = view.findViewById(R.id.btnCloseTab)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tab = tabs[position]
            val isActive = position == activeIndexProvider()
            holder.textTitle.text = tab.currentTitle.ifEmpty { "Untitled" }
            holder.textUrl.text = tab.currentUrl.ifEmpty { "about:blank" }
            holder.textTitle.typeface = Typeface.create(
                holder.textTitle.typeface,
                if (isActive) Typeface.BOLD else Typeface.NORMAL
            )
            val textColor = if (isActive) {
                val typedValue = TypedValue()
                holder.textTitle.context.theme.resolveAttribute(
                    androidx.appcompat.R.attr.colorPrimary, typedValue, true
                )
                typedValue.data
            } else {
                holder.textTitle.currentTextColor
            }
            holder.textTitle.setTextColor(textColor)
            val favicon = tab.currentFavicon
            if (favicon != null) {
                holder.imgIcon.setImageBitmap(favicon)
            } else {
                holder.imgIcon.setImageResource(R.drawable.globe_24px)
            }
            holder.itemView.setOnClickListener { onItemClick(position) }
            holder.btnClose.setOnClickListener { onCloseItemClick(position) }
        }

        override fun getItemCount(): Int = tabs.size
    }
}
