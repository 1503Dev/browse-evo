package dev1503.browseevo.ui.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import dev1503.browseevo.EvoDataStore
import dev1503.browseevo.R

class DownloadSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val dataStore = EvoDataStore(requireActivity())
        preferenceManager.preferenceDataStore = dataStore
        setPreferencesFromResource(R.xml.preferences_download, rootKey)
    }
}
