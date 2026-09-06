package dev1503.browseevo.ui.viewmodel.browsermain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import android.widget.Toast
import dev1503.browseevo.data.BookmarkManager
import dev1503.browseevo.download.DownloadController
import dev1503.browseevo.download.DownloadNotifier
import dev1503.browseevo.download.DownloadRecord
import dev1503.browseevo.ui.widgets.MenuPagerAdapter
import dev1503.browseevo.ui.widgets.OverlayBuilder
import kotlin.math.abs
import dev1503.browseevo.MainActivity
import dev1503.browseevo.R
import dev1503.browseevo.Utils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class WatchSquareBrowserMainViewModel(activity: MainActivity) : CommonBrowserMainViewModel(activity) {
    override val layoutResId: Int = R.layout.view_model_browser_main_watch

    override val requestFocusOnUrlEditShown: Boolean = false

    override val useSnackbarAnchor: Boolean = false

    override val closeUrlEditOverlayOnBack: Boolean = true

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDownTime = 0L
    private var isPotentialSwipe = false

    override fun onViewReady() {
        textWebSiteTitle.setOnTouchListener { v, event -> handleTitleTouch(v, event) }
    }

    override fun showMenu() {
        val content = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_menu, null)
        val viewPager = content.findViewById<ViewPager>(R.id.viewPager)
        var overlay: View? = null
        val dismiss: () -> Unit = { overlay?.let { dismissOverlay(it) } }

        fun setupPages() {
            val adapter = MenuPagerAdapter(
                firstPageLayout = R.layout.layout_bottom_sheet_menu_watch_1,
                secondPageLayout = R.layout.layout_bottom_sheet_menu_2
            )
            viewPager.adapter = adapter
            viewPager.post {
                adapter.page1View?.findViewById<MaterialButton>(R.id.btnTools)?.setOnClickListener {
                    viewPager.setCurrentItem(1, true)
                }
                adapter.page1View?.let {
                    Utils.bindMenuPageButtons(
                        it,
                        onAddBookmarkClick = { showAddBookmark(currentPageTitle(), currentPageUrl()) },
                        onShareClick = { Utils.shareUrl(activity, currentPageUrl(), currentPageTitle()) },
                        onDarkModeToggleClick = {
                            Utils.cycleDarkMode()
                            refreshThemeColors()
                            dismiss()
                        }
                    )
                }
                adapter.page2View?.findViewById<MaterialButton>(R.id.btnViewSource)?.setOnClickListener {
                    dismiss()
                    viewCurrentPageSource()
                }
                adapter.page2View?.findViewById<MaterialButton>(R.id.btnPcMode)?.let { button ->
                    button.isChecked = Utils.isPcMode()
                    button.setOnClickListener {
                        Utils.setPcMode(!Utils.isPcMode())
                        button.isChecked = Utils.isPcMode()
                        webViewWrapper.updateAllSessionsUserAgent()
                        webViewWrapper.reload()
                        dismiss()
                    }
                }
            }
        }
        setupPages()
        overlay = OverlayBuilder(activity)
            .title("Menu")
            .view(content)
            .show(dismiss)
        showOverlay(overlay)
    }

    override fun showAddBookmark(title: String, address: String) {
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.view_add_bookmark, null) as ViewGroup
        val editTitle = content.findViewById<TextInputEditText>(R.id.editBookmarkTitle)
        val editAddress = content.findViewById<TextInputEditText>(R.id.editBookmarkAddress)
        val editDir = content.findViewById<TextInputEditText>(R.id.editBookmarkDir)
        editTitle.setText(title)
        editAddress.setText(address)
        editDir.setText("/")

        var overlay: View? = null
        val dismiss: () -> Unit = { overlay?.let { dismissOverlay(it) } }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, 0)
        }
        buttonRow.addView(
            MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "取消"
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttonRow.addView(
            MaterialButton(activity).apply {
                text = "确定"
                setOnClickListener {
                    val saved = saveBookmarkWithDuplicateCheck(
                        editTitle.text?.toString()?.trim().orEmpty(),
                        editAddress.text?.toString()?.trim().orEmpty(),
                        BookmarkManager.sanitizeDir(editDir.text?.toString()?.trim().orEmpty())
                    )
                    if (saved) dismiss()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(buttonRow)

        overlay = OverlayBuilder(activity)
            .title("添加书签")
            .view(content)
            .show(dismiss)
        showOverlay(overlay)
    }

    override fun showDownloadConfirm(url: String, filename: String?, contentLength: Long, body: java.io.InputStream?) {
        if (maybeAutoDownload(url, contentLength, body)) return
        requestDownloadPermissions()
        DownloadController.init(activity)

        val content = LayoutInflater.from(activity).inflate(R.layout.view_add_download, null) as ViewGroup
        val editFilename = content.findViewById<TextInputEditText>(R.id.editDownloadFilename)
        val sizeText = content.findViewById<TextView>(R.id.textDownloadSize)
        editFilename.setText(filename ?: DownloadController.suggestFilename(url))
        if (contentLength > 0) {
            sizeText.text = "文件大小: ${DownloadNotifier.formatBytes(contentLength)}"
        }
        val client = OkHttpClient()
        // 已有原始响应流时不发 HEAD 请求：那是对下载地址的额外请求，
        // 可能消耗掉一次性链接。
        if (body == null) {
            client.newCall(Request.Builder().url(url).head().build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val length = it.header("Content-Length")?.toLongOrNull() ?: -1L
                            Handler(Looper.getMainLooper()).post {
                                if (length > 0) sizeText.text = "文件大小: ${DownloadNotifier.formatBytes(length)}"
                            }
                        }
                    }
                })
        }

        var consumedBody = false
        var overlay: View? = null
        val dismiss: () -> Unit = {
            // 未交给下载器的响应流直接关闭，释放连接
            if (!consumedBody) {
                runCatching { body?.close() }
            }
            overlay?.let { dismissOverlay(it) }
        }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, 0)
        }
        fun startDownload() {
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
            Snackbar.make(_view, "已开始下载", Snackbar.LENGTH_LONG)
                .setAction("查看") {
                    Utils.openDownloadManagerActivity(activity)
                }
                .show()
            dismiss()
        }
        buttonRow.addView(
            MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "复制"
                setOnClickListener {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                    Toast.makeText(activity, "已复制链接", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttonRow.addView(
            MaterialButton(activity).apply {
                text = "取消"
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttonRow.addView(
            MaterialButton(activity).apply {
                text = "确定"
                setOnClickListener { startDownload() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(buttonRow)

        overlay = OverlayBuilder(activity)
            .title("是否下载文件")
            .view(content)
            .show(dismiss)
        showOverlay(overlay)
    }

    override fun showTabsSheet() {
        val content = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_tabs, null)
        val recycler = content.findViewById<RecyclerView>(R.id.recyclerTabs)
        recycler.layoutManager = LinearLayoutManager(activity)
        var overlay: View? = null
        fun dismiss() {
            overlay?.let { dismissOverlay(it) }
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
        content.findViewById<MaterialButton>(R.id.btnNewTab).setOnClickListener {
            dismiss()
            webViewWrapper.createTab()
            webViewWrapper.loadBuiltInPage()
        }
        overlay = OverlayBuilder(activity)
            .title("Tabs")
            .fillContent(true)
            .view(content)
            .show { dismiss() }
        showOverlay(overlay)
    }

    private fun handleTitleTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.rawX
                swipeDownY = event.rawY
                swipeDownTime = event.downTime
                isPotentialSwipe = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPotentialSwipe && abs(event.rawX - swipeDownX) > touchSlopPx(v)) {
                    isPotentialSwipe = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isPotentialSwipe) {
                    onTitleReleased(v, event)
                }
            }
        }
        return false
    }

    private fun onTitleReleased(v: View, event: MotionEvent) {
        val dx = event.rawX - swipeDownX
        val dy = event.rawY - swipeDownY
        val durationMs = (event.eventTime - swipeDownTime).coerceAtLeast(1)
        val minDistancePx = dpToPx(v, MIN_SWIPE_DISTANCE_DP)
        val maxDriftPx = dpToPx(v, MAX_VERTICAL_DRIFT_DP)
        val minVelocityPxPerS = dpToPx(v, MIN_SWIPE_VELOCITY_DP_PER_S)
        val velocityPxPerS = abs(dx) / durationMs * 1000f
        val isHorizontalEnough = abs(dx) >= minDistancePx && abs(dy) <= maxDriftPx
        val isFastEnough = velocityPxPerS >= minVelocityPxPerS
        val isShortEnough = durationMs <= MAX_SWIPE_DURATION_MS
        if (isHorizontalEnough && isFastEnough && isShortEnough) {
            if (dx < 0) {
                webViewWrapper.goBack()
            } else {
                webViewWrapper.goForward()
            }
        }
    }

    private fun touchSlopPx(v: View): Int =
        ViewConfiguration.get(v.context).scaledTouchSlop

    private fun dpToPx(v: View, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, v.resources.displayMetrics)

    companion object {
        private const val MIN_SWIPE_DISTANCE_DP = 64f
        private const val MAX_VERTICAL_DRIFT_DP = 48f
        private const val MIN_SWIPE_VELOCITY_DP_PER_S = 500f
        private const val MAX_SWIPE_DURATION_MS = 600L
    }
}
