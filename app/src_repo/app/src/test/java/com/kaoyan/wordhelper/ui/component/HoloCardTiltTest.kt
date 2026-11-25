package com.kaoyan.wordhelper.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class HoloCardTiltTest {
    @Test fun deadZoneIsOpenAtBothEdges() {
        assertEquals(0f, settleTilt(0f, 0.0349f), 0f)
        assertEquals(0f, settleTilt(0f, -0.0349f), 0f)
        assertEquals(0.0049f, settleTilt(0f, 0.035f), 1e-7f)
        assertEquals(-0.0049f, settleTilt(0f, -0.035f), 1e-7f)
    }

    @Test fun largeInputIsClampedBeforeDamping() {
        assertEquals(0.14f, settleTilt(0f, 20f), 0f)
        assertEquals(-0.14f, settleTilt(0f, -20f), 0f)
    }

    @Test fun repeatedSamplesConvergeAndSnapExactlyToTarget() {
        var tilt = 0f
        repeat(100) { tilt = settleTilt(tilt, 0.5f) }
        assertEquals(0.5f, tilt, 0f)
        assertEquals(0.43f, settleTilt(tilt, 0f), 1e-7f)
        repeat(100) { tilt = settleTilt(tilt, 0.02f) }
        assertEquals(0f, tilt, 0f)
    }
}
