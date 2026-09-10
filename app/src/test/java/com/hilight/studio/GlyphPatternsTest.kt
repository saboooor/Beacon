package com.hilight.studio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphPatternsTest {

    private val javaRenderer = com.hilight.core.Renderer()

    private fun isFrameVisible(frame: IntArray): Boolean {
        for (color in frame) {
            val rgb = ((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)
            if (rgb > 12) return true
        }
        return false
    }

    @Test
    fun allNewPatternsProduceValidFramesInJavaRenderer() {
        val patterns = listOf("meter", "strobe", "heartbeat", "bounce", "radar", "converge", "glitch")
        val color = 0xFF00E676L
        val speedMs = 1000L

        for (mode in patterns) {
            val cfg = JSONObject().apply {
                put("mode", mode)
                put("color", color)
                put("speedMs", speedMs)
                put("brightness", 1.0)
            }

            var hasVisibleFrame = false
            for (t in 0..1000 step 50) {
                val frame = javaRenderer.frame(cfg, t.toLong(), 8)
                assertEquals("Frame size must be 8 for mode $mode", 8, frame.size)
                for (pixel in frame) {
                    val alpha = (pixel ushr 24) and 0xFF
                    val red = (pixel ushr 16) and 0xFF
                    val green = (pixel ushr 8) and 0xFF
                    val blue = pixel and 0xFF
                    assertTrue("Alpha must be 0 or 255", alpha == 0 || alpha == 0xFF)
                    assertTrue("Red channel in bounds", red in 0..255)
                    assertTrue("Green channel in bounds", green in 0..255)
                    assertTrue("Blue channel in bounds", blue in 0..255)
                }
                if (isFrameVisible(frame)) {
                    hasVisibleFrame = true
                }
            }
            assertTrue("Pattern $mode should produce at least one visible frame during cycle", hasVisibleFrame)
        }
    }

    @Test
    fun allNewPatternsProduceValidFramesInKotlinPreview() {
        val glyphPatterns = listOf(
            Pattern.METER,
            Pattern.STROBE,
            Pattern.HEARTBEAT,
            Pattern.BOUNCE,
            Pattern.RADAR,
            Pattern.CONVERGE,
            Pattern.GLITCH,
        )

        for (pattern in glyphPatterns) {
            val ambient = Ambient(
                pattern = pattern,
                color = 0xFF00E5FF.toInt(),
                speedMs = 1000,
                brightness = 1f,
            )

            var hasVisibleFrame = false
            for (t in 0..1000 step 50) {
                val frame = Renderer.frame(pattern, t.toLong(), ambient)
                assertEquals(8, frame.size)
                if (isFrameVisible(frame)) {
                    hasVisibleFrame = true
                }
            }
            assertTrue("Preview for $pattern should produce at least one visible frame", hasVisibleFrame)
        }
    }

    @Test
    fun patternEnumResolutionAndRoundTrip() {
        val glyphPatterns = listOf(
            Pattern.METER to "meter",
            Pattern.STROBE to "strobe",
            Pattern.HEARTBEAT to "heartbeat",
            Pattern.BOUNCE to "bounce",
            Pattern.RADAR to "radar",
            Pattern.CONVERGE to "converge",
            Pattern.GLITCH to "glitch",
        )

        for ((pat, key) in glyphPatterns) {
            assertEquals(key, pat.key)
            assertEquals(pat, Pattern.of(key))
            assertTrue(pat.usesSpeed)
            assertTrue(pat.cycleMeaningRes != null)
        }
    }

    @Test
    fun randomPatternProducesAccuratePreviewAndNotRainbow() {
        val rainbowAmbient = Ambient(pattern = Pattern.RAINBOW, speedMs = 1000, rainbowSpread = true)
        val randomAmbient = Ambient(
            pattern = Pattern.RANDOM,
            randomIntervalMs = 500,
            randomPerLed = true,
            randomSmooth = true,
        )

        val rainbowFrame = Renderer.frame(Pattern.RAINBOW, 0L, rainbowAmbient)
        val randomFrame = Renderer.frame(Pattern.RANDOM, 0L, randomAmbient)

        // Ensure random frame is visible and not identical to a rainbow wheel
        assertTrue(isFrameVisible(randomFrame))
        var matchesRainbow = true
        for (i in 0 until 8) {
            if (rainbowFrame[i] != randomFrame[i]) {
                matchesRainbow = false
                break
            }
        }
        assertFalse("Random frame should not match rainbow frame", matchesRainbow)

        // Verify randomPerLed = false makes all LEDs uniform
        val uniformRandom = randomAmbient.copy(randomPerLed = false)
        val uniformFrame = Renderer.frame(Pattern.RANDOM, 0L, uniformRandom)
        val firstColor = uniformFrame[0]
        for (i in 1 until 8) {
            assertEquals("All LEDs should share same color when perLed is false", firstColor, uniformFrame[i])
        }

        // Verify randomSmooth fades over the interval
        val frameStart = Renderer.frame(Pattern.RANDOM, 0L, randomAmbient)
        val frameMid = Renderer.frame(Pattern.RANDOM, 250L, randomAmbient)
        val frameEnd = Renderer.frame(Pattern.RANDOM, 500L, randomAmbient)

        var midDiffersFromStart = false
        var midDiffersFromEnd = false
        for (i in 0 until 8) {
            if (frameMid[i] != frameStart[i]) midDiffersFromStart = true
            if (frameMid[i] != frameEnd[i]) midDiffersFromEnd = true
        }
        assertTrue("Midpoint of transition should differ from start", midDiffersFromStart)
        assertTrue("Midpoint of transition should differ from next step", midDiffersFromEnd)
    }
}
