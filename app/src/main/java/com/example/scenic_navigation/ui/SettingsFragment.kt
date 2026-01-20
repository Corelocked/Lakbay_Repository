package com.example.scenic_navigation.ui

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.scenic_navigation.R
import com.example.scenic_navigation.config.Config
import android.text.InputFilter
import android.text.Spanned

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Use the same shared prefs name as the app `RouteViewModel` expects
        preferenceManager.sharedPreferencesName = "scenic_prefs"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val epsKey = Config.PREF_CLUSTER_EPS_KEY
        val minKey = Config.PREF_CLUSTER_MIN_PTS_KEY

        val epsPref = findPreference<EditTextPreference>(epsKey)
        epsPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val raw = pref.text
            val formatted = raw?.toDoubleOrNull()?.let { String.format("%.0f m", it) }
            formatted ?: "${Config.DEFAULT_CLUSTER_EPS_METERS.toInt()} m"
        }

        // Bind dialog EditText hint and clamp values automatically on change
        epsPref?.setOnBindEditTextListener { editText ->
            editText.hint = "meters (m)"
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            val epsFilter = object : InputFilter {
                override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
                    val newText = StringBuilder(dest).apply {
                        replace(dstart, dend, source.subSequence(start, end).toString())
                    }.toString()
                    if (newText.isEmpty()) return null
                    // Only digits and at most one dot
                    if (!newText.matches(Regex("^[0-9]*\\.?[0-9]*$"))) return ""
                    if (newText.count { it == '.' } > 1) return ""
                    if (newText.startsWith('.')) return ""
                    return null
                }
            }
            editText.filters = arrayOf(epsFilter)
        }

        epsPref?.setOnPreferenceChangeListener { pref, newValue ->
            val s = (newValue as? String)?.trim()
            val v = s?.toDoubleOrNull()
            val coerced = when {
                v == null -> Config.DEFAULT_CLUSTER_EPS_METERS
                v <= 0.0 -> Config.DEFAULT_CLUSTER_EPS_METERS
                else -> v
            }
            // Save coerced value as string
            preferenceManager.sharedPreferences?.edit()?.putString(epsKey, coerced.toString())?.apply()
            // Update the displayed text (EditTextPreference stores text)
            (pref as? EditTextPreference)?.text = String.format("%.0f", coerced)
            if (v == null || v <= 0.0) {
                val root = requireView()
                Snackbar.make(root, "EPS adjusted to ${coerced.toInt()} m", Snackbar.LENGTH_SHORT).show()
            }
            // Return false because we've handled saving
            false
        }

        val minPref = findPreference<EditTextPreference>(minKey)
        minPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val raw = pref.text
            val formatted = raw?.toIntOrNull()?.let { "$it pts" }
            formatted ?: "${Config.DEFAULT_CLUSTER_MIN_PTS} pts"
        }

        minPref?.setOnBindEditTextListener { editText ->
            editText.hint = "points (pts)"
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val minFilter = object : InputFilter {
                override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
                    val newText = StringBuilder(dest).apply {
                        replace(dstart, dend, source.subSequence(start, end).toString())
                    }.toString()
                    if (newText.isEmpty()) return null
                    if (!newText.matches(Regex("^[0-9]+$"))) return ""
                    if (newText.length == 1 && newText == "0") return ""
                    return null
                }
            }
            editText.filters = arrayOf(minFilter)
        }

        minPref?.setOnPreferenceChangeListener { pref, newValue ->
            val s = (newValue as? String)?.trim()
            val v = s?.toIntOrNull()
            val coerced = when {
                v == null -> Config.DEFAULT_CLUSTER_MIN_PTS
                v < 1 -> 1
                else -> v
            }
            preferenceManager.sharedPreferences?.edit()?.putString(minKey, coerced.toString())?.apply()
            (pref as? EditTextPreference)?.text = coerced.toString()
            if (v == null || v < 1) {
                val root = requireView()
                Snackbar.make(root, "Min points adjusted to $coerced pts", Snackbar.LENGTH_SHORT).show()
            }
            false
        }

        // Reset to defaults preference
        val resetPref = findPreference<Preference>("pref_reset_defaults")
        resetPref?.setOnPreferenceClickListener {
            preferenceManager.sharedPreferences?.edit()?.putString(epsKey, Config.DEFAULT_CLUSTER_EPS_METERS.toString())?.apply()
            preferenceManager.sharedPreferences?.edit()?.putString(minKey, Config.DEFAULT_CLUSTER_MIN_PTS.toString())?.apply()
            // Notify directly via SettingsBus so RouteViewModel refreshes immediately
            com.example.scenic_navigation.events.SettingsBus.notifySettingsChanged()
            epsPref?.text = Config.DEFAULT_CLUSTER_EPS_METERS.toInt().toString()
            minPref?.text = Config.DEFAULT_CLUSTER_MIN_PTS.toString()
            val root = requireView()
            Snackbar.make(root, "Preferences reset to defaults", Snackbar.LENGTH_SHORT).show()
            true
        }
    }

    // Intercept EditTextPreference dialogs to provide OK-button disabling while typing
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is EditTextPreference) {
            showEditTextPreferenceDialog(preference)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun showEditTextPreferenceDialog(pref: EditTextPreference) {
        val context = requireContext()
        val editText = android.widget.EditText(context)
        // configure based on key
        when (pref.key) {
            Config.PREF_CLUSTER_EPS_KEY -> {
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                editText.hint = "meters (m)"
                editText.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                    val newText = StringBuilder(dest).apply { replace(dstart, dend, source.subSequence(start, end).toString()) }.toString()
                    if (newText.isEmpty()) return@InputFilter null
                    if (!newText.matches(Regex("^[0-9]*\\.?[0-9]*$"))) return@InputFilter ""
                    if (newText.count { it == '.' } > 1) return@InputFilter ""
                    if (newText.startsWith('.')) return@InputFilter ""
                    null
                })
            }
            Config.PREF_CLUSTER_MIN_PTS_KEY -> {
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                editText.hint = "points (pts)"
                editText.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                    val newText = StringBuilder(dest).apply { replace(dstart, dend, source.subSequence(start, end).toString()) }.toString()
                    if (newText.isEmpty()) return@InputFilter null
                    if (!newText.matches(Regex("^[0-9]+$"))) return@InputFilter ""
                    if (newText.length == 1 && newText == "0") return@InputFilter ""
                    null
                })
            }
        }
        editText.setText(pref.text ?: "")

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(pref.dialogTitle ?: pref.title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            fun validate(text: String): Boolean {
                return when (pref.key) {
                    Config.PREF_CLUSTER_EPS_KEY -> text.toDoubleOrNull()?.let { it > 0.0 } ?: false
                    Config.PREF_CLUSTER_MIN_PTS_KEY -> text.toIntOrNull()?.let { it >= 1 } ?: false
                    else -> true
                }
            }

            ok.isEnabled = validate(editText.text.toString())
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    ok.isEnabled = validate(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            ok.setOnClickListener {
                // Coerce/save via existing preference change handlers
                pref.text = editText.text.toString()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
