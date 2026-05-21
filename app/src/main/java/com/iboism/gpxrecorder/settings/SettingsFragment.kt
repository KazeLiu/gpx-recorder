package com.iboism.gpxrecorder.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private lateinit var binding: FragmentSettingsBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.backButton.setOnClickListener { parentFragmentManager.popBackStack() }
        setupLanguageOptions()
        setupThemeOptions()
        setupMapProviderOptions()
        setupFutureSettings()
    }

    private fun setupLanguageOptions() {
        updateLanguageValue()
        binding.languageRow.setOnClickListener {
            val currentIndex = languageOptions.indexOfFirst {
                it.languageTag == LocalePreference.getLanguageTag(requireContext())
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.language)
                .setSingleChoiceItems(languageOptions.map { getString(it.labelRes) }.toTypedArray(), currentIndex) { dialog, which ->
                    LocalePreference.setLanguageTag(requireContext(), languageOptions[which].languageTag)
                    updateLanguageValue()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun updateLanguageValue() {
        val selectedTag = LocalePreference.getLanguageTag(requireContext())
        val selectedOption = languageOptions.firstOrNull { it.languageTag == selectedTag } ?: languageOptions.first()
        binding.languageValue.text = getString(selectedOption.labelRes)
    }

    private fun setupThemeOptions() {
        binding.darkModeSwitch.isChecked = ThemePreference.isDarkModeEnabled(requireContext())
        binding.darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            ThemePreference.setDarkModeEnabled(requireContext(), isChecked)
        }
        binding.darkModeRow.setOnClickListener {
            binding.darkModeSwitch.isChecked = !binding.darkModeSwitch.isChecked
        }
    }

    private fun setupMapProviderOptions() {
        updateMapProviderValue()
        binding.mapProviderRow.setOnClickListener {
            val currentProvider = MapProviderPreference.getProvider(requireContext())
            val currentIndex = mapProviderOptions.indexOfFirst { it.provider == currentProvider }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.map_provider)
                .setSingleChoiceItems(mapProviderOptions.map { getString(it.labelRes) }.toTypedArray(), currentIndex) { dialog, which ->
                    MapProviderPreference.setProvider(requireContext(), mapProviderOptions[which].provider)
                    updateMapProviderValue()
                    dialog.dismiss()
                    requireActivity().recreate()
                }
                .show()
        }
    }

    private fun updateMapProviderValue() {
        val selectedProvider = MapProviderPreference.getProvider(requireContext())
        val selectedOption = mapProviderOptions.firstOrNull { it.provider == selectedProvider } ?: mapProviderOptions.first()
        binding.mapProviderValue.text = getString(selectedOption.labelRes)
    }

    private fun setupFutureSettings() {
        binding.extraSettingsContainer.visibility = View.GONE
    }

    data class LanguageOption(val languageTag: String, val labelRes: Int)
    data class MapProviderOption(val provider: MapProvider, val labelRes: Int)

    companion object {
        private val languageOptions = listOf(
            LanguageOption("en", R.string.language_english),
            LanguageOption("zh-CN", R.string.language_simplified_chinese)
        )
        private val mapProviderOptions = listOf(
            MapProviderOption(MapProvider.Google, R.string.map_provider_google),
            MapProviderOption(MapProvider.Amap, R.string.map_provider_amap)
        )

        fun newInstance() = SettingsFragment()
    }
}
