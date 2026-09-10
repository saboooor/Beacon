package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingBatteryIndicatorTest {

    @Test
    fun `batteryGradientColor starts at red and ends at green`() {
        assertEquals(Store.BATTERY_COLOR_RED, Store.batteryGradientColor(0, LED_COUNT))
        assertEquals(Store.BATTERY_COLOR_GREEN, Store.batteryGradientColor(LED_COUNT - 1, LED_COUNT))
    }

    @Test
    fun `batteryGradientColor intermediate leds transition smoothly`() {
        val red = Store.batteryGradientColor(0, 8)
        val orange = Store.batteryGradientColor(2, 8)
        val yellowGreen = Store.batteryGradientColor(4, 8)
        val green = Store.batteryGradientColor(7, 8)

        assertEquals(Store.BATTERY_COLOR_RED, red)
        assertEquals(Store.BATTERY_COLOR_GREEN, green)
        // Red channel decreases from red towards green, green channel increases
        assertTrue(((red shr 16) and 0xFF) >= ((green shr 16) and 0xFF))
        assertTrue(((green shr 8) and 0xFF) > ((red shr 8) and 0xFF))
        assertTrue(((yellowGreen shr 8) and 0xFF) > ((red shr 8) and 0xFF))
    }

    @Test
    fun `computeChargingPerLed produces 8 leds with proportional gradient lit counts`() {
        // 0% -> at least 1 LED lit
        val zero = Store.computeChargingPerLed(0)
        assertEquals(LED_COUNT, zero.size)
        assertEquals(1, zero.count { it != 0 })
        assertEquals(Store.BATTERY_COLOR_RED, zero[0])

        // 12% -> 1 LED lit
        val twelve = Store.computeChargingPerLed(12)
        assertEquals(1, twelve.count { it != 0 })
        assertEquals(Store.BATTERY_COLOR_RED, twelve[0])

        // 13% -> 2 LEDs lit (red -> red-orange)
        val thirteen = Store.computeChargingPerLed(13)
        assertEquals(2, thirteen.count { it != 0 })
        assertEquals(Store.batteryGradientColor(0, 8), thirteen[0])
        assertEquals(Store.batteryGradientColor(1, 8), thirteen[1])
        assertEquals(0, thirteen[2])

        // 50% -> 4 LEDs lit (indices 0..3)
        val fifty = Store.computeChargingPerLed(50)
        assertEquals(4, fifty.count { it != 0 })
        assertEquals(Store.batteryGradientColor(0, 8), fifty[0])
        assertEquals(Store.batteryGradientColor(3, 8), fifty[3])
        assertEquals(0, fifty[4])

        // 75% -> 6 LEDs lit (indices 0..5)
        val seventyFive = Store.computeChargingPerLed(75)
        assertEquals(6, seventyFive.count { it != 0 })
        assertEquals(Store.batteryGradientColor(5, 8), seventyFive[5])
        assertEquals(0, seventyFive[6])

        // 100% -> all 8 LEDs lit with the complete Red to Green gradient ring
        val full = Store.computeChargingPerLed(100)
        assertEquals(8, full.count { it != 0 })
        for (i in 0 until 8) {
            assertEquals(Store.batteryGradientColor(i, 8), full[i])
        }
    }

    @Test
    fun `computeChargingPerLed with custom colors uses custom colors`() {
        val customColors = List(LED_COUNT) { 0xFF0000FF.toInt() + it }
        val fifty = Store.computeChargingPerLed(50, customColors)
        assertEquals(4, fifty.count { it != 0 })
        assertEquals(customColors[0], fifty[0])
        assertEquals(customColors[3], fifty[3])
        assertEquals(0, fifty[4])
    }
}
