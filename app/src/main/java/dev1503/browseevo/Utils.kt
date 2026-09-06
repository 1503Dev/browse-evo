package dev1503.browseevo

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import dev1503.browseevo.data.NeoSettings
import dev1503.browseevo.ui.widgets.EvoWebViewWrapper
import dev1503.materialpopups.MaterialPopups
import dev1503.materialpopups.Utils
import org.mozilla.geckoview.GeckoRuntimeSettings

import java.net.URLDecoder

object Utils {
    var neoSettings: NeoSettings? = null

    const val KEY_DARK_MODE = "appearance/dark_mode"
    const val KEY_PC_MODE = "browse/pc_mode"
    const val KEY_AUTO_DOWNLOAD = "download/auto_download"

    const val DARK_MODE_OFF = 0
    const val DARK_MODE_ON = 1
    const val DARK_MODE_RESERVED = 2

    fun openHistoryActivity(context: Context, tab: String) {
        context.startActivity(
            Intent(context, HistoryActivity::class.java).putExtra(HistoryActivity.EXTRA_SELECTED_TAB, tab)
        )
    }

    fun openDownloadManagerActivity(context: Context) {
        context.startActivity(Intent(context, DownloadManagerActivity::class.java))
    }

    fun openSettingsActivity(context: Context) {
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }

    fun shareUrl(context: Context, url: String, title: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrEmpty()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
        }
        context.startActivity(Intent.createChooser(intent, "分享网页"))
    }

    fun getDarkModeSetting(): Int =
        when (neoSettings?.getInt(KEY_DARK_MODE, DARK_MODE_OFF)) {
            DARK_MODE_ON -> DARK_MODE_ON
            DARK_MODE_RESERVED -> DARK_MODE_RESERVED
            else -> DARK_MODE_OFF
        }

    fun setDarkModeSetting(value: Int) {
        neoSettings?.putInt(KEY_DARK_MODE, value)
    }

    fun getPreferredColorScheme(): Int =
        when (getDarkModeSetting()) {
            DARK_MODE_ON -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            DARK_MODE_OFF -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            else -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        }
    fun applySavedNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (getDarkModeSetting()) {
                DARK_MODE_ON -> {
                    MaterialPopups.textColor = MaterialPopups.TEXT_COLOR_DARK
                    MaterialPopups.backgroundColor = MaterialPopups.BACKGROUND_COLOR_DARK
                    MaterialPopups.backgroundOverlayAlpha = MaterialPopups.BACKGROUND_OVERLAY_ALPHA_DARK
                    MaterialPopups.backgroundOverlayColor = Utils.applyAlpha(MaterialPopups.textColor, MaterialPopups.backgroundOverlayAlpha)
                    AppCompatDelegate.MODE_NIGHT_YES
                }
                DARK_MODE_RESERVED -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> {
                    MaterialPopups.textColor = MaterialPopups.TEXT_COLOR_LIGHT
                    MaterialPopups.backgroundColor = MaterialPopups.BACKGROUND_COLOR_LIGHT
                    MaterialPopups.backgroundOverlayAlpha = MaterialPopups.BACKGROUND_OVERLAY_ALPHA_LIGHT
                    MaterialPopups.backgroundOverlayColor = Utils.applyAlpha(MaterialPopups.textColor, MaterialPopups.backgroundOverlayAlpha)
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            }
        )
    }
    fun applyNightModeOverride(base: Context): Context {
        val mask = when (getDarkModeSetting()) {
            DARK_MODE_ON -> Configuration.UI_MODE_NIGHT_YES
            DARK_MODE_OFF -> Configuration.UI_MODE_NIGHT_NO
            else -> return base
        }
        return try {
            val config = Configuration(base.resources.configuration)
            config.uiMode = mask or (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
            base.createConfigurationContext(config)
        } catch (_: Exception) {
            base
        }
    }

    fun isPcMode(): Boolean = neoSettings?.getBoolean(KEY_PC_MODE, false) ?: false

    fun setPcMode(value: Boolean) {
        neoSettings?.putBoolean(KEY_PC_MODE, value)
        EvoWebViewWrapper.rebuildUserAgent()
    }

    fun cycleDarkMode() {
        setDarkModeSetting(
            when (getDarkModeSetting()) {
                DARK_MODE_OFF -> DARK_MODE_ON
                DARK_MODE_ON -> DARK_MODE_RESERVED
                else -> DARK_MODE_OFF
            }
        )
        EvoWebViewWrapper.resetRuntime()
        applySavedNightMode()
    }

    private fun refreshDarkModeButton(button: MaterialButton) {
        val setting = getDarkModeSetting()
        button.isChecked = setting != DARK_MODE_OFF
        button.text = if (setting == DARK_MODE_RESERVED) "跟随系统" else "夜间模式"
    }

    fun bindMenuPageButtons(
        page1View: View,
        onAddBookmarkClick: () -> Unit,
        onShareClick: (() -> Unit)? = null,
        onDarkModeToggleClick: (() -> Unit)? = null,
    ) {
        page1View.findViewById<MaterialButton>(R.id.btnDarkMode)?.let { button ->
            refreshDarkModeButton(button)
            button.setOnClickListener {
                val handler = onDarkModeToggleClick
                if (handler != null) handler() else cycleDarkMode()
            }
        }
        page1View.findViewById<MaterialButton>(R.id.btnBookmarks)?.setOnClickListener {
            openHistoryActivity(page1View.context, HistoryActivity.TAB_BOOKMARK)
        }
        page1View.findViewById<MaterialButton>(R.id.btnHistory)?.setOnClickListener {
            openHistoryActivity(page1View.context, HistoryActivity.TAB_HISTORY)
        }
        page1View.findViewById<MaterialButton>(R.id.btnDownloads)?.setOnClickListener {
            openDownloadManagerActivity(page1View.context)
        }
        page1View.findViewById<MaterialButton>(R.id.btnAddBookmark)?.setOnClickListener {
            onAddBookmarkClick()
        }
        page1View.findViewById<MaterialButton>(R.id.btnShare)?.setOnClickListener {
            onShareClick?.invoke()
        }
        page1View.findViewById<MaterialButton>(R.id.btnSettings)?.setOnClickListener {
            openSettingsActivity(page1View.context)
        }
    }

    fun isBuiltInPage(url: String, filesDirPath: String): Boolean {
        if (url.startsWith("evo://index", ignoreCase = true)) return true
        if (url.startsWith("evo://page", ignoreCase = true)) return true
        if (url.startsWith("resource://android/assets/built-in_page")) return true
        if (url.startsWith("file:///android_asset/built-in_page")) return true
        if (url.startsWith("file://$filesDirPath/built-in_page")) return true
        return false
    }

    fun looksLikeHost(input: String): Boolean {
        if (input.contains("://") || input.any { it.isWhitespace() }) return false
        return Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""").matches(input) ||
            Regex("""^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+(:\d+)?(/.*)?$""").matches(input) ||
            Regex("""^localhost(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE).matches(input)
    }

    fun schemeOf(input: String): String? {
        val match = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""").find(input) ?: return null
        val candidate = match.groupValues[1]
        if (candidate.contains('.') || candidate.equals("localhost", ignoreCase = true)) return null
        return candidate.lowercase()
    }

    /**
     * 解码 URL 编码的字符串（如文件名）。只在包含 '%' 时尝试解码；
     * '+' 视为字面字符而非空格（URL 路径与 Content-Disposition 中的 '+' 都是字面量）；
     * 解码失败时返回原字符串。
     */
    fun decodeUrlEncoded(value: String): String {
        if (!value.contains('%')) return value
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            value
        }
    }
}
