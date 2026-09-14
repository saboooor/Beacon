package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MediaTrackerTest {

    @Test
    fun `hsvToColor and colorToHsv roundtrip preserves primary hues`() {
        val testColors = listOf(
            0xFFFF0000.toInt(), // Red
            0xFF00FF00.toInt(), // Green
            0xFF0000FF.toInt(), // Blue
            0xFFFFFF00.toInt(), // Yellow
            0xFF00FFFF.toInt(), // Cyan
            0xFFFF00FF.toInt(), // Magenta
        )

        val hsv = FloatArray(3)
        for (color in testColors) {
            MediaTracker.colorToHsv(color, hsv)
            val reconstructed = MediaTracker.hsvToColor(hsv[0], hsv[1], hsv[2])

            val r1 = (color shr 16) and 0xFF
            val g1 = (color shr 8) and 0xFF
            val b1 = color and 0xFF

            val r2 = (reconstructed shr 16) and 0xFF
            val g2 = (reconstructed shr 8) and 0xFF
            val b2 = reconstructed and 0xFF

            assertTrue("Red channel matches within 2", abs(r1 - r2) <= 2)
            assertTrue("Green channel matches within 2", abs(g1 - g2) <= 2)
            assertTrue("Blue channel matches within 2", abs(b1 - b2) <= 2)
        }
    }

    @Test
    fun `extractLedColorsFromPixels extracts authentic dominant colors matching artwork`() {
        // Synthesize an album art with dominant Navy (#162032), Coral (#E05A47), and Gold (#F2C054)
        val navy = 0xFF162032.toInt()
        val coral = 0xFFE05A47.toInt()
        val gold = 0xFFF2C054.toInt()

        val pixels = IntArray(100) { i ->
            when {
                i < 60 -> navy  // 60% Navy
                i < 90 -> coral // 30% Coral
                else -> gold    // 10% Gold
            }
        }

        val colors = MediaTracker.extractLedColorsFromPixels(pixels)

        assertEquals("Must produce exactly 8 LED colors", 8, colors.size)

        // Navy (#162032, max channel 50 < 77) is below the boost threshold — kept as-is.
        val hasNavyTone = colors.any { c ->
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            b > r && b > g && b <= 77 // Blue dominant, intentionally dark (not boosted)
        }
        // Coral (#E05A47, max 224 ≥ 77) → HSV boosted to S=0.85, V=1.0 → vivid warm red.
        val hasCoralTone = colors.any { c ->
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            r > 200 && g < 120 // Saturated warm red after saturation boost
        }

        assertTrue("Palette must contain the dominant navy tone", hasNavyTone)
        assertTrue("Palette must contain the dominant coral tone", hasCoralTone)
    }

    @Test
    fun `extractRawLedColorsFromPixels returns colors before optimization`() {
        // Pastel pink artwork: R=255, G=180, B=192 (pastel, low saturation ~0.29)
        val pastelPink = 0xFFFFB4C0.toInt()
        val pixels = IntArray(50) { pastelPink }

        val raw = MediaTracker.extractRawLedColorsFromPixels(pixels)
        val optimized = MediaTracker.extractLedColorsFromPixels(pixels)

        assertEquals(8, raw.size)
        assertEquals(8, optimized.size)

        // Raw colors preserve the unboosted pastel values
        val rawColor = raw[0]
        val rawG = (rawColor ushr 8) and 0xFF
        assertTrue("Raw color preserves higher green channel of pastel", rawG > 150)

        // Optimized colors boost the saturation so it is vivid on LEDs
        val optColor = optimized[0]
        val optG = (optColor ushr 8) and 0xFF
        assertTrue("Optimized color has lower green for higher saturation", optG < rawG)
    }

    @Test
    fun `extractLedColorsFromPixels on monochromatic artwork produces graceful glow`() {
        // Synthesize black & white album art
        val pixels = IntArray(100) { i ->
            val v = (i * 2).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val colors = MediaTracker.extractLedColorsFromPixels(pixels)

        assertEquals(8, colors.size)
        for (c in colors) {
            val alpha = (c ushr 24) and 0xFF
            assertEquals(0xFF, alpha)
            assertNotEquals(0, c and 0xFFFFFF)
        }
    }

    @Test
    fun `paletteFromSingleColor spreads smoothly across 8 LEDs`() {
        val seed = 0xFF2979FF.toInt() // Blue
        val palette = MediaTracker.paletteFromSingleColor(seed)

        assertEquals(8, palette.size)
        val hues = palette.map {
            val hsv = FloatArray(3)
            MediaTracker.colorToHsv(it, hsv)
            hsv[0]
        }

        // Hues should vary slightly to give visual depth across the array
        assertTrue("First and last hue should differ", abs(hues.first() - hues.last()) > 10f)
    }

    @Test
    fun `fallbackLedColors produces 8 distinct hues across the spectrum`() {
        val colors = MediaTracker.fallbackLedColors(42)
        assertEquals(8, colors.size)

        val uniqueColors = colors.distinct()
        assertEquals("Fallback must produce 8 distinct colors", 8, uniqueColors.size)
    }

    @Test
    fun `MediaTrackInfo holds complete track metadata and colors`() {
        val colors = List(8) { 0xFF00E5FF.toInt() }
        val info = MediaTrackInfo(
            title = "Midnight City",
            artist = "M83",
            packageName = "com.spotify.music",
            isPlaying = true,
            artwork = null,
            colors = colors,
            primaryColor = colors[0],
            secondaryColor = colors[4],
        )

        assertEquals("Midnight City", info.title)
        assertEquals("M83", info.artist)
        assertEquals("com.spotify.music", info.packageName)
        assertTrue(info.isPlaying)
        assertEquals(8, info.colors.size)
        assertEquals(colors[0], info.primaryColor)
        assertEquals(colors[4], info.secondaryColor)
    }

    @Test
    fun `selectActiveSource prioritizes playing media over paused media`() {
        val spotify = TrackedMediaSource(
            packageName = "com.spotify.music",
            title = "Song A",
            isPlaying = false, // Paused
            lastActiveTime = 1000L,
        )
        val youtube = TrackedMediaSource(
            packageName = "com.google.android.apps.youtube.music",
            title = "Song B",
            isPlaying = true, // Playing
            lastActiveTime = 500L,
        )

        // Even though Spotify had more recent activity (1000 vs 500), YouTube is playing!
        val selected = MediaTracker.selectActiveSource(listOf(spotify, youtube))
        assertNotNull(selected)
        assertEquals("com.google.android.apps.youtube.music", selected?.packageName)
        assertTrue(selected?.isPlaying == true)

        // If list order is reversed, YouTube is still selected
        val selectedReversed = MediaTracker.selectActiveSource(listOf(youtube, spotify))
        assertEquals("com.google.android.apps.youtube.music", selectedReversed?.packageName)
    }

    @Test
    fun `selectActiveSource switches when paused media starts playing`() {
        val app1 = TrackedMediaSource(
            packageName = "com.app.one",
            title = "Podcast",
            isPlaying = true,
            lastActiveTime = 1000L,
        )
        val app2 = TrackedMediaSource(
            packageName = "com.app.two",
            title = "Track",
            isPlaying = false,
            lastActiveTime = 500L,
        )

        // app1 is playing
        assertEquals("com.app.one", MediaTracker.selectActiveSource(listOf(app1, app2))?.packageName)

        // app1 pauses, app2 starts playing
        app1.isPlaying = false
        app2.isPlaying = true
        app2.lastActiveTime = 2000L

        // app2 is now selected immediately
        val selected = MediaTracker.selectActiveSource(listOf(app1, app2))
        assertEquals("com.app.two", selected?.packageName)
        assertTrue(selected?.isPlaying == true)
    }

    @Test
    fun `selectActiveSource picks most recent when both are playing`() {
        val app1 = TrackedMediaSource(
            packageName = "com.app.one",
            isPlaying = true,
            lastActiveTime = 1000L,
        )
        val app2 = TrackedMediaSource(
            packageName = "com.app.two",
            isPlaying = true,
            lastActiveTime = 2500L, // More recently active
        )

        val selected = MediaTracker.selectActiveSource(listOf(app1, app2))
        assertEquals("com.app.two", selected?.packageName)
    }

    @Test
    fun `selectActiveSource retains most recent when all are paused`() {
        val app1 = TrackedMediaSource(
            packageName = "com.app.one",
            isPlaying = false,
            lastActiveTime = 1000L,
        )
        val app2 = TrackedMediaSource(
            packageName = "com.app.two",
            isPlaying = false,
            lastActiveTime = 2000L, // More recently active
        )

        val selected = MediaTracker.selectActiveSource(listOf(app1, app2))
        assertEquals("com.app.two", selected?.packageName)
    }

    @Test
    fun `selectActiveSource returns null for empty collection`() {
        assertNull(MediaTracker.selectActiveSource(emptyList()))
    }

    @Test
    fun `computeMediaProgressLitCount drains from 8 to 0 as track progresses`() {
        // At start (0.0): all 8 LEDs lit
        assertEquals(8, Store.computeMediaProgressLitCount(0.0f))
        // Slightly into the song (< 1/8): still full 8 LEDs
        assertEquals(8, Store.computeMediaProgressLitCount(0.05f))
        assertEquals(8, Store.computeMediaProgressLitCount(0.124f))

        // 1/8 of the way through: 7 LEDs lit
        assertEquals(7, Store.computeMediaProgressLitCount(0.125f))
        assertEquals(7, Store.computeMediaProgressLitCount(0.20f))

        // 2/8 (1/4) of the way through: 6 LEDs lit
        assertEquals(6, Store.computeMediaProgressLitCount(0.25f))

        // Halfway (4/8): 4 LEDs lit
        assertEquals(4, Store.computeMediaProgressLitCount(0.50f))

        // 3/4 (6/8): 2 LEDs lit
        assertEquals(2, Store.computeMediaProgressLitCount(0.75f))

        // 7/8 through: 1 LED lit
        assertEquals(1, Store.computeMediaProgressLitCount(0.875f))
        // Near the end: still 1 LED lit
        assertEquals(1, Store.computeMediaProgressLitCount(0.95f))
        assertEquals(1, Store.computeMediaProgressLitCount(0.99f))

        // Song end (1.0 or greater): 0 LEDs lit
        assertEquals(0, Store.computeMediaProgressLitCount(1.0f))
        assertEquals(0, Store.computeMediaProgressLitCount(1.5f))

        // Negative progress: clamped to 8 LEDs
        assertEquals(8, Store.computeMediaProgressLitCount(-0.1f))
    }

    @Test
    fun `computeMediaProgressPerLed turns off trailing LEDs based on progress`() {
        val testColors = List(8) { 0xFF102030.toInt() + it }

        // Start of song: all 8 LEDs lit with their respective colors
        val startPerLed = Store.computeMediaProgressPerLed(0.0f, testColors)
        assertEquals(8, startPerLed.size)
        assertEquals(testColors, startPerLed)

        // Halfway through: first 4 lit, last 4 off (0x00000000)
        val midPerLed = Store.computeMediaProgressPerLed(0.50f, testColors)
        assertEquals(8, midPerLed.size)
        for (i in 0 until 4) {
            assertEquals("LED $i should be lit", testColors[i], midPerLed[i])
        }
        for (i in 4 until 8) {
            assertEquals("LED $i should be off", 0, midPerLed[i])
        }

        // End of song: all LEDs off (0)
        val endPerLed = Store.computeMediaProgressPerLed(1.0f, testColors)
        assertEquals(8, endPerLed.size)
        assertTrue("All LEDs must be dark at end of track", endPerLed.all { it == 0 })
    }

    @Test
    fun `MediaTrackInfo progress accurately reflects playback ratio`() {
        val colors = List(8) { 0xFF00E5FF.toInt() }
        val info = MediaTrackInfo(
            title = "Test Song",
            artist = "Artist",
            packageName = "com.test",
            isPlaying = false,
            artwork = null,
            colors = colors,
            primaryColor = colors[0],
            secondaryColor = colors[4],
            positionMs = 60_000L,
            durationMs = 240_000L,
        )

        assertEquals(0.25f, info.progress, 0.001f)
        assertEquals(60_000L, info.currentPositionMs())
    }
}
