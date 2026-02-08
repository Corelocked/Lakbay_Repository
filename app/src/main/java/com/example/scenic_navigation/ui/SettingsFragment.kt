package com.example.scenic_navigation.ui

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.scenic_navigation.R
import com.example.scenic_navigation.config.Config

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Use the same shared prefs name as the app `RouteViewModel` expects
        preferenceManager.sharedPreferencesName = "scenic_prefs"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Language preference
        val languagePref = findPreference<androidx.preference.ListPreference>("language")
        languagePref?.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.ListPreference> { pref ->
            val value = pref.value
            val index = pref.findIndexOfValue(value)
            if (index >= 0) pref.entries[index] else "English"
        }
        // Set the current value from preferences
        val currentLanguage = com.example.scenic_navigation.utils.LocaleHelper.getLanguage(requireContext())
        languagePref?.value = currentLanguage
        languagePref?.setOnPreferenceChangeListener { _, newValue ->
            val language = newValue as String
            com.example.scenic_navigation.utils.LocaleHelper.setLanguage(requireContext(), language)
            // Restart the activity completely to apply the new locale
            requireActivity().finish()
            requireActivity().startActivity(requireActivity().intent)
            true
        }

        // Personalization switch: show friendly summary and notify app on change
        val personalizationPref = findPreference<androidx.preference.SwitchPreference>("personalization_enabled")
        personalizationPref?.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.SwitchPreference> { pref ->
            if (pref.isChecked) getString(R.string.personalization_summary) else getString(R.string.personalization_summary)
        }
        personalizationPref?.setOnPreferenceChangeListener { pref, newValue ->
            val enabled = newValue as? Boolean ?: true
            // Persist to the same shared prefs so SettingsStore and ViewModels see it
            preferenceManager.sharedPreferences?.edit()?.putBoolean("personalization_enabled", enabled)?.apply()
            // Notify other components (ViewModels) that settings changed
            com.example.scenic_navigation.events.SettingsBus.notifySettingsChanged()
            true
        }

        // Map style preference
        val mapStylePref = findPreference<androidx.preference.ListPreference>("map_style")
        mapStylePref?.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.ListPreference> { pref ->
            val value = pref.value
            val index = pref.findIndexOfValue(value)
            if (index >= 0) pref.entries[index] else "Streets"
        }

        // Reset to defaults preference - only resets the 4 specified settings
        val resetPref = findPreference<Preference>("pref_reset_defaults")
        resetPref?.setOnPreferenceClickListener {

            // Reset personalization to true
            preferenceManager.sharedPreferences?.edit()?.putBoolean("personalization_enabled", true)?.apply()
            personalizationPref?.isChecked = true

            // Reset map style to "Streets"
            preferenceManager.sharedPreferences?.edit()?.putString("map_style", "Streets")?.apply()
            mapStylePref?.value = "Streets"

            // Reset language to English
            preferenceManager.sharedPreferences?.edit()?.putString("language", "en")?.apply()
            languagePref?.value = "en"
            com.example.scenic_navigation.utils.LocaleHelper.setLanguage(requireContext(), "en")

            // Notify directly via SettingsBus so ViewModels refresh immediately
            com.example.scenic_navigation.events.SettingsBus.notifySettingsChanged()

            val root = requireView()
            Snackbar.make(root, "Settings reset to defaults", Snackbar.LENGTH_SHORT).show()

            // Restart the activity to apply language change
            requireActivity().finish()
            requireActivity().startActivity(requireActivity().intent)

            true
        }
    }
}
