package com.zhousl.aether.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineTurnSpacingSettingTest {
    @Test
    fun keepsOfferedSpacingsAndFallsBackToStandard() {
        TimelineTurnSpacingOptionsDp.forEach { option ->
            assertEquals(option, normalizeTimelineTurnSpacingDp(option))
        }
        assertEquals(DefaultTimelineTurnSpacingDp, normalizeTimelineTurnSpacingDp(null))
        assertEquals(DefaultTimelineTurnSpacingDp, normalizeTimelineTurnSpacingDp(7))
        assertEquals(DefaultTimelineTurnSpacingDp, normalizeTimelineTurnSpacingDp(-4))
    }

    @Test
    fun settingsSavedBeforeTheOptionExistedUseStandardSpacing() {
        val settings = parseAppSettings("""{"systemPrompt":"saved before the option existed"}""")

        assertEquals("saved before the option existed", settings.systemPrompt)
        assertEquals(DefaultTimelineTurnSpacingDp, settings.timelineTurnSpacingDp)
    }

    @Test
    fun spacingSurvivesSettingsSerialization() {
        val restored = parseAppSettings(serializeAppSettings(AppSettings(timelineTurnSpacingDp = 16)))

        assertEquals(16, restored.timelineTurnSpacingDp)
    }
}
