package com.bettercontent.dimensiondrink.runtime.run

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontSleepDangerAdmissionTest {
    @Test
    fun onlyAnAdmittedActiveFontWindowCanRequestSleepInterruption() {
        assertTrue(FontSleepDangerAdmission.shouldInterrupt(activeSession = true, stillInWindow = true))
        assertFalse(FontSleepDangerAdmission.shouldInterrupt(activeSession = false, stillInWindow = true))
        assertFalse(FontSleepDangerAdmission.shouldInterrupt(activeSession = true, stillInWindow = false))
        assertFalse(FontSleepDangerAdmission.shouldInterrupt(activeSession = false, stillInWindow = false))
    }
}
