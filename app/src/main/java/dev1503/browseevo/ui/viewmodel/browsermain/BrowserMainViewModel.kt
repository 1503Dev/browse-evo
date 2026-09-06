package dev1503.browseevo.ui.viewmodel.browsermain

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev1503.browseevo.MainActivity
import dev1503.browseevo.PendingNavigation
import dev1503.browseevo.R
import dev1503.browseevo.Utils
import dev1503.browseevo.ui.viewmodel.ViewModel
import dev1503.browseevo.ui.widgets.EvoWebViewWrapper
import dev1503.materialpopups.widgets.menuitem.MenuItem
import dev1503.materialpopups.widgets.popup.MenuPopup
import dev1503.materialpopups.widgets.popup.Popup
import org.mozilla.geckoview.GeckoSession
import java.net.URLEncoder

open class BrowserMainViewModel(override val activity: MainActivity): ViewModel(activity) {
    val webViewWrapper: EvoWebViewWrapper = EvoWebViewWrapper(activity)
    lateinit var btnReload: MaterialButton
    var btnWebsiteInfo: MaterialButton? = null
    lateinit var textWebSiteTitle: TextView
    lateinit var btnGoBack: MaterialButton
    lateinit var btnGoForward: MaterialButton
    lateinit var btnHome: MaterialButton
    lateinit var btnMenu: MaterialButton
    lateinit var progressBar: LinearProgressIndicator
    lateinit var editTextUrl: AppCompatEditText

    private var progressAnimator: ValueAnimator? = null
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btnReload = _view.findViewById(R.id.btnReload)
        btnWebsiteInfo = _view.findViewById(R.id.btnWebsiteInfo)
        textWebSiteTitle = _view.findViewById(R.id.textWebsiteTitle)
        btnGoBack = _view.findViewById(R.id.btnGoBack)
        btnGoForward = _view.findViewById(R.id.btnGoForward)
        btnHome = _view.findViewById(R.id.btnHome)
        btnMenu = _view.findViewById(R.id.btnMenu)
        progressBar = _view.findViewById(R.id.progressBar)
        editTextUrl = _view.findViewById(R.id.editTextUrl)

        btnReload.setOnClickListener {
            if (webViewWrapper.isLoading) webViewWrapper.stopLoading() else webViewWrapper.reload()
        }
        btnGoBack.setOnClickListener { webViewWrapper.goBack() }
        btnGoForward.setOnClickListener { webViewWrapper.goForward() }
        webViewWrapper.onTitleChanged = { title -> textWebSiteTitle.text = title ?: "" }
        webViewWrapper.onNavigationStateChanged = { updateNavigationButtons() }
        webViewWrapper.onTabChanged = { index, tab ->
            textWebSiteTitle.text = tab?.currentTitle ?: ""
            onActiveTabChanged(index)
        }
        webViewWrapper.onPageStarted = { onPageStarted() }
        webViewWrapper.onProgressChanged = { progress -> animateProgress(progress) }
        webViewWrapper.onLoadingChanged = { loading -> onLoadingStateChanged(loading) }
        webViewWrapper.onContextMenu = { _, _, element ->
            handleContextMenu(element)
        }

        webViewWrapper.loadBuiltInPage()
    }

    override fun onResume() {
        webViewWrapper.onHostResumed()
        val url = PendingNavigation.url ?: return
        PendingNavigation.url = null
        webViewWrapper.goToUrl(url)
    }

    override fun onHostStopped() {
        webViewWrapper.onHostStopped()
    }

    override fun handleBackPressed(): Boolean {
        if (webViewWrapper.goBack()) return true
        if (webViewWrapper.getTabCount() > 1) {
            webViewWrapper.closeActiveTab()
            return true
        }
        return false
    }

    protected open fun onPageStarted() {
        progressAnimator?.cancel()
        progressBar.progress = 0
        showProgressBar()
    }

    protected open fun onLoadingStateChanged(loading: Boolean) {
        btnReload.setIconResource(
            if (loading) R.drawable.close_24px else R.drawable.refresh_24px
        )
        if (loading) showProgressBar() else hideProgressBar()
    }

    protected open fun onActiveTabChanged(index: Int) {
        updateNavigationButtons()
    }

    private fun showProgressBar() {
        fadeRunnable?.let { fadeHandler.removeCallbacks(it) }
        progressAnimator?.cancel()
        progressBar.alpha = 1f
        progressBar.visibility = View.VISIBLE
    }

    private fun hideProgressBar() {
        if (progressBar.visibility != View.VISIBLE || progressBar.alpha == 0f) return
        fadeRunnable?.let { fadeHandler.removeCallbacks(it) }
        animateProgress(100)
        fadeRunnable = Runnable {
            progressBar.animate()
                .alpha(0f)
                .setDuration(300L)
                .withEndAction {
                    progressBar.visibility = View.GONE
                    progressBar.progress = 0
                }
                .start()
        }
        fadeHandler.postDelayed(fadeRunnable!!, 200L)
    }

    private fun animateProgress(target: Int) {
        val to = target.coerceIn(0, 100)
        if (progressBar.visibility != View.VISIBLE) {
            progressBar.alpha = 1f
            progressBar.visibility = View.VISIBLE
        }
        val from = progressBar.progress
        if (from == to) return
        progressAnimator?.cancel()
        val duration = (Math.abs(to - from) * 20L).coerceIn(120L, 600L)
        progressAnimator = ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { progressBar.progress = it.animatedValue as Int }
            start()
        }
    }

    private fun updateNavigationButtons() {
        btnGoBack.isEnabled = webViewWrapper.canGoBack()
        btnGoForward.isEnabled = webViewWrapper.canGoForward()
    }

    protected fun isBuiltInPage(url: String): Boolean =
        Utils.isBuiltInPage(url, activity.filesDir.absolutePath)

    protected fun looksLikeHost(input: String): Boolean = Utils.looksLikeHost(input)

    protected fun schemeOf(input: String): String? = Utils.schemeOf(input)

    protected fun searchWithBing(input: String) {
        val query = URLEncoder.encode(input, "UTF-8")
        webViewWrapper.goToUrl("https://www.bing.com/search?q=$query")
    }

    protected open fun handleContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
        if (element.linkUri == null) return
        val popup = MenuPopup(activity)
        addLinkMenuItems(popup, element.linkUri.toString())
        popup.build()
            .setAnimation(Popup.ANIM_FADE)
            .showAt(webViewWrapper.lastPointerX, webViewWrapper.lastPointerY)
    }

    protected fun addLinkMenuItems(popup: MenuPopup, linkUrl: String) {
        popup.addMenuItem(
            MenuItem("在新标签页中打开链接") {
                webViewWrapper.createTab().loadUrl(linkUrl)
                webViewWrapper.switchToTab(webViewWrapper.getTabCount() - 1)
            }.setIcon(R.drawable.open_in_new_24px)
        )
        popup.addMenuItem(
            MenuItem("复制链接地址") {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", linkUrl))
                Toast.makeText(activity, "已复制链接", Toast.LENGTH_SHORT).show()
            }
        )
    }
}