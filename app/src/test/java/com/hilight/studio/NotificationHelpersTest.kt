package com.hilight.studio

import org.junit.Assert.*
import org.junit.Test

class NotificationHelpersTest {

    @Test fun onlyExplicitIncomingCallMarkerQualifies() {
        assertTrue(isIncomingCallType(1))
        for (type in listOf(0, 2, 3, -1, 99)) assertFalse(isIncomingCallType(type))
    }

    @Test fun silentChannelsIncludeLowImportanceAndNoSoundOrVibration() {
        assertTrue(isSilentNotification(2, true, true))
        assertTrue(isSilentNotification(3, false, false))
        assertFalse(isSilentNotification(3, true, false))
        assertFalse(isSilentNotification(3, false, true))
        assertFalse(isSilentNotification(null, false, false))
        assertFalse(isSilentNotification(-1000, false, false))
    }

    @Test fun fullGradientLookReachesRendererAndLegacyRandomTimingIsPreserved() {
        val look = Ambient(pattern = Pattern.GRADIENT, color = 0xFFFF0000.toInt(), secondColor = 0xFF0000FF.toInt())
        val json = Bridge.lookAlertJson(123, look, 4000, AlertSource.NOTIFICATION)
        assertEquals("gradient", json.getString("pattern"))
        assertFalse(json.has("mode"))
        assertEquals(2, json.getJSONArray("colors").length())
        assertEquals(0xFF0000FFL, json.getJSONArray("colors").getLong(1))
        assertEquals(4000, json.getInt("durationMs"))
        assertEquals(500, AppRule("pkg", "App", pattern = Pattern.RANDOM).effectiveLook().randomIntervalMs)
        val legacyGradient = AppRule("pkg", "App", pattern = Pattern.GRADIENT, color = 0xFFFF0000.toInt())
        assertEquals(legacyGradient.color, legacyGradient.effectiveLook().secondColor)
        assertEquals(0xFF00FF00.toInt(), legacyGradient.effectiveLook(0xFF00FF00.toInt()).secondColor)
    }
}
