package dev1503.browseevo.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev1503.browseevo.EvoDataStore
import dev1503.browseevo.R

class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_settings, rootKey)
        findPreference<Preference>("appearance")?.onPreferenceClickListener = this
        findPreference<Preference>("download")?.onPreferenceClickListener = this
        findPreference<Preference>("about")?.onPreferenceClickListener = this
        val dataStore = EvoDataStore(requireActivity())
        preferenceManager.preferenceDataStore = dataStore
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference.key == "appearance") {
            enter(AppearanceFragment())
            return true
        }
        if (preference.key == "download") {
            enter(DownloadSettingsFragment())
            return true
        }
        if (preference.key == "about") {
            enter(AboutFragment())
            return true
        }
        return false
    }

    @SuppressLint("CommitTransaction")
    fun enter(fragment: Fragment) {
        val fragmentManager: FragmentManager = activity?.supportFragmentManager!!
        val transaction: FragmentTransaction = fragmentManager.beginTransaction()
        transaction.replace(R.id.container, fragment)
        transaction.addToBackStack(null)
        transaction.commit()
    }
}