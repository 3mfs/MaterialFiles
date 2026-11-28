

package com.moxapps.files.settings

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moxapps.files.R
import com.moxapps.files.theme.custom.CustomThemeHelper
import com.moxapps.files.theme.custom.ThemeColor
import com.moxapps.files.theme.night.NightMode
import com.moxapps.files.theme.night.NightModeHelper
import com.moxapps.files.ui.PreferenceFragmentCompat

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }

        setupBuildPlatePreference()
    }

    private fun setupBuildPlatePreference() {
        val pref = preferenceScreen.findPreference<ListPreference>(
            getString(R.string.pref_key_model_viewer_build_plate)
        ) ?: return
        pref.summaryProvider = Preference.SummaryProvider<ListPreference> { lp ->
            val current = lp.value ?: "256x256"
            val idx = lp.findIndexOfValue(current)
            if (idx >= 0 && current != "custom") {
                lp.entries[idx]
            } else {
                val (w, d) = parseBuildPlateDimensions(current)
                getString(R.string.settings_model_viewer_build_plate_custom_summary_format, w, d)
            }
        }
        pref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == "custom") {
                showCustomBuildPlateDialog(pref)
                false
            } else {
                true
            }
        }
    }

    private fun showCustomBuildPlateDialog(pref: ListPreference) {
        val context = requireContext()
        val (curW, curD) = parseBuildPlateDimensions(pref.value)
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val widthInput = EditText(context).apply {
            hint = getString(R.string.settings_model_viewer_build_plate_width_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(curW.toInt().toString())
        }
        val depthInput = EditText(context).apply {
            hint = getString(R.string.settings_model_viewer_build_plate_depth_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(curD.toInt().toString())
        }
        container.addView(widthInput)
        container.addView(depthInput)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_model_viewer_build_plate_custom_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val w = widthInput.text.toString().toIntOrNull()?.coerceIn(50, 2000) ?: curW.toInt()
                val d = depthInput.text.toString().toIntOrNull()?.coerceIn(50, 2000) ?: curD.toInt()
                pref.value = "${w}x${d}"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseBuildPlateDimensions(raw: String?): Pair<Float, Float> {
        val parts = raw?.lowercase()?.split("x")
        val w = parts?.getOrNull(0)?.toFloatOrNull()?.takeIf { it > 0f } ?: 256f
        val d = parts?.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: w
        return w to d
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }
}
