package dev1503.browseevo

import android.app.Activity
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore
import com.google.android.material.snackbar.Snackbar
import dev1503.browseevo.ui.viewmodel.DeviceTypeSelectionViewModel.Companion.PREF_KEY_DEVICE_TYPE

class EvoDataStore(val context: Activity): PreferenceDataStore() {
    override fun putString(key: String?, value: String?) {
        if (key == "device_type") {
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit {
                    putString(PREF_KEY_DEVICE_TYPE, value)
                }
            requestRestart()
            return
        }
        if (key == Utils.KEY_DARK_MODE) {
            return
        }
        Utils.neoSettings?.putString(key!!, value)
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == "device_type") {
            return android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_KEY_DEVICE_TYPE, defValue)
        }
        if (key == Utils.KEY_DARK_MODE) {
            return Utils.neoSettings?.getInt(key, 0).toString()
        }
        return Utils.neoSettings?.getString(key!!, defValue)
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return Utils.neoSettings?.getInt(key!!, defValue) ?: defValue
    }

    override fun putInt(key: String?, value: Int) {
        Utils.neoSettings?.putInt(key!!, value)
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return Utils.neoSettings?.getBoolean(key!!, defValue) ?: defValue
    }

    override fun putBoolean(key: String?, value: Boolean) {
        Utils.neoSettings?.putBoolean(key!!, value)
    }

    fun requestRestart() {
        val snackbar = Snackbar.make(context.window.decorView, "重启以应用更改", Snackbar.LENGTH_SHORT)
        snackbar.show()
    }
}