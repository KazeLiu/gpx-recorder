package com.iboism.gpxrecorder.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {
    @Test
    fun colorThemeFromValueFallsBackToBlueWhenStoredValueIsUnknown() {
        assertEquals(ThemePreference.ColorTheme.Blue, ThemePreference.ColorTheme.fromValue("missing"))
    }

    @Test
    fun colorThemeFromValueDefaultsToBlueWhenStoredValueIsMissing() {
        assertEquals(ThemePreference.ColorTheme.Blue, ThemePreference.ColorTheme.fromValue(null))
    }

    @Test
    fun colorThemeFromValueRecognizesBlue() {
        assertEquals(ThemePreference.ColorTheme.Blue, ThemePreference.ColorTheme.fromValue("blue"))
    }

}
