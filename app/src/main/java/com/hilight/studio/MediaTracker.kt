package com.hilight.studio

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/** Represents the currently detected media track and its extracted LED color palette. */
data class MediaTrackInfo(
    val title: String?,
    val artist: String?,
    val packageName: String?,
    val isPlaying: Boolean,
    val artwork: Bitmap?,
    val colors: List<Int>, // 8 LED colors
    val rawColors: List<Int> = colors, // 8 LED colors before optimization
    val primaryColor: Int,
    val secondaryColor: Int,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val lastPositionUpdateTime: Long = 0L,
) {
    /** Estimates current playback position in milliseconds. */
    fun currentPositionMs(now: Long = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)): Long {
        var pos = positionMs
        if (isPlaying && lastPositionUpdateTime > 0L && playbackSpeed > 0f && now > 0L) {
            val delta = now - lastPositionUpdateTime
            if (delta > 0L) {
                pos += (delta * playbackSpeed).toLong()
            }
        }
        return if (durationMs > 0L) pos.coerceIn(0L, durationMs) else maxOf(0L, pos)
    }

    /** Progress ratio between 0.0 (song start) and 1.0 (song end). */
    val progress: Float
        get() = if (durationMs > 0L) {
            (currentPositionMs().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

/** Internal representation of an active media source (app) being tracked. */
internal data class TrackedMediaSource(
    val packageName: String,
    var title: String? = null,
    var artist: String? = null,
    var artwork: Bitmap? = null,
    var colors: List<Int> = emptyList(),
    var rawColors: List<Int> = emptyList(),
    var primaryColor: Int = MediaTracker.DEFAULT_COLOR,
    var secondaryColor: Int = MediaTracker.DEFAULT_COLOR,
    var isPlaying: Boolean = false,
    var lastActiveTime: Long = 0L,
    var positionMs: Long = 0L,
    var durationMs: Long = 0L,
    var playbackSpeed: Float = 1f,
    var lastPositionUpdateTime: Long = 0L,
    var controller: MediaController? = null,
    var callback: MediaController.Callback? = null,
)

/**
 * Tracks currently playing media via Android's MediaSessionManager and active notifications,
 * extracting vibrant 8-LED color palettes from album artwork.
 *
 * Supports multiple concurrent media sources, always prioritizing whichever media is actively
 * playing and smoothly transitioning between players.
 */
class MediaTracker(
    private val context: Context,
    private val onMediaStateChanged: (MediaTrackInfo?) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _currentMedia = MutableStateFlow<MediaTrackInfo?>(null)
    val currentMedia: StateFlow<MediaTrackInfo?> = _currentMedia.asStateFlow()

    private var sessionManager: MediaSessionManager? = null
    private var registeredSessionListener = false
    internal val mediaSources = mutableMapOf<String, TrackedMediaSource>()
    private var sampleModeActive = false

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            main.post { syncControllers(controllers) }
        }

    fun startListening() {
        if (registeredSessionListener) return
        try {
            val mgr = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            sessionManager = mgr
            val comp = ComponentName(app, NotificationTrigger::class.java)
            mgr?.addOnActiveSessionsChangedListener(sessionsChangedListener, comp)
            registeredSessionListener = true
            syncControllers(mgr?.getActiveSessions(comp))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener permission not active for MediaSessionManager: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start MediaSession listener", t)
        }
    }

    fun stopListening() {
        if (!registeredSessionListener) return
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Error removing active sessions listener", t)
        }
        registeredSessionListener = false
        clearAllControllers()
    }

    fun refreshSessions() {
        try {
            val comp = ComponentName(app, NotificationTrigger::class.java)
            val controllers = sessionManager?.getActiveSessions(comp)
            syncControllers(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot refresh active sessions without notification listener: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh sessions", t)
        }
    }

    private fun syncControllers(controllers: List<MediaController>?) {
        if (controllers == null) return
        val currentPkgs = controllers.map { it.packageName }.toSet()

        // Unregister controllers that no longer exist in the system
        val deadControllers = mediaSources.filter { (pkg, src) ->
            src.controller != null && pkg !in currentPkgs
        }
        for ((pkg, src) in deadControllers) {
            src.callback?.let { cb -> runCatching { src.controller?.unregisterCallback(cb) } }
            src.controller = null
            src.callback = null
            // If it also has no notification info, drop it
            if (src.title == null && src.artwork == null) {
                mediaSources.remove(pkg)
            } else {
                src.isPlaying = false
            }
        }

        // Add or update active controllers
        for (controller in controllers) {
            val pkg = controller.packageName
            val source = mediaSources.getOrPut(pkg) { TrackedMediaSource(pkg) }

            if (source.controller != controller) {
                source.callback?.let { cb -> runCatching { source.controller?.unregisterCallback(cb) } }
                source.controller = controller
                val cb = createControllerCallback(controller)
                source.callback = cb
                runCatching { controller.registerCallback(cb, main) }
            }

            updateFromController(source, controller)
        }

        reconcileActiveMedia()
    }

    private fun createControllerCallback(controller: MediaController) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            main.post {
                val source = mediaSources[controller.packageName]
                if (source != null) {
                    updateFromController(source, controller)
                    reconcileActiveMedia()
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            main.post {
                val source = mediaSources[controller.packageName]
                if (source != null) {
                    updateFromController(source, controller)
                    reconcileActiveMedia()
                }
            }
        }

        override fun onSessionDestroyed() {
            main.post {
                val source = mediaSources[controller.packageName]
                if (source != null) {
                    source.callback?.let { cb -> runCatching { controller.unregisterCallback(cb) } }
                    source.controller = null
                    source.callback = null
                    mediaSources.remove(controller.packageName)
                    reconcileActiveMedia()
                }
            }
        }
    }

    private fun updateFromController(source: TrackedMediaSource, controller: MediaController) {
        val pbState = runCatching { controller.playbackState }.getOrNull()
        val state = pbState?.state
        val isPlaying = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING

        val meta = runCatching { controller.metadata }.getOrNull()
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.description?.title?.toString()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.description?.subtitle?.toString()
        val artwork = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.description?.iconBitmap
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val position = pbState?.position ?: 0L
        val playbackSpeed = pbState?.playbackSpeed ?: 1f
        val lastPositionUpdateTime = pbState?.lastPositionUpdateTime ?: 0L

        if (title != null) source.title = title
        if (artist != null) source.artist = artist
        if (duration > 0L) {
            source.durationMs = duration
        } else if (meta != null && meta.keySet().contains(MediaMetadata.METADATA_KEY_DURATION)) {
            source.durationMs = 0L
        }
        source.positionMs = maxOf(0L, position)
        source.playbackSpeed = if (playbackSpeed > 0f) playbackSpeed else 1f
        source.lastPositionUpdateTime = if (lastPositionUpdateTime > 0L) lastPositionUpdateTime else if (isPlaying) SystemClock.elapsedRealtime() else 0L

        if (artwork != null) {
            source.artwork = artwork
            source.rawColors = extractRawLedColors(artwork)
            source.colors = source.rawColors.map { ensureLedVisible(it) }
            source.primaryColor = source.colors.firstOrNull() ?: DEFAULT_COLOR
            source.secondaryColor = source.colors.getOrNull(4) ?: source.colors.getOrNull(1) ?: DEFAULT_COLOR
        } else if (source.colors.isEmpty()) {
            source.rawColors = fallbackLedColors((title ?: source.packageName).hashCode())
            source.colors = source.rawColors
            source.primaryColor = source.colors.firstOrNull() ?: DEFAULT_COLOR
            source.secondaryColor = source.colors.getOrNull(4) ?: source.colors.getOrNull(1) ?: DEFAULT_COLOR
        }

        val wasPlaying = source.isPlaying
        source.isPlaying = isPlaying
        if (isPlaying || (!wasPlaying && isPlaying) || source.lastActiveTime == 0L) {
            source.lastActiveTime = System.currentTimeMillis()
        }
    }

    private fun clearAllControllers() {
        for ((_, src) in mediaSources) {
            src.callback?.let { cb -> runCatching { src.controller?.unregisterCallback(cb) } }
            src.controller = null
            src.callback = null
        }
        mediaSources.clear()
        if (!sampleModeActive) {
            updateTrackInfo(null)
        }
    }

    /** Called from NotificationTrigger when a media notification is posted or updated. */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sampleModeActive) return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true ||
            notif.category == Notification.CATEGORY_TRANSPORT

        if (!isMedia) return

        val pkg = sbn.packageName
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Check action buttons: a Pause action means media is currently playing.
        val hasPauseAction = notif.actions?.any { action ->
            val text = action.title?.toString()?.lowercase() ?: ""
            text.contains("pause") || text.contains("一時停止")
        } == true

        val hasPlayAction = notif.actions?.any { action ->
            val text = action.title?.toString()?.lowercase() ?: ""
            text.contains("play") || text.contains("再生")
        } == true

        var notifIsPlaying = when {
            hasPauseAction -> true
            hasPlayAction -> false
            else -> (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
        }

        var artwork: Bitmap? = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Bitmap::class.java)

        if (artwork == null) {
            val largeIcon = notif.getLargeIcon()
            if (largeIcon != null) {
                runCatching {
                    val drawable = largeIcon.loadDrawable(context)
                    if (drawable is BitmapDrawable) {
                        artwork = drawable.bitmap
                    } else if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                        val bm = Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtMost(256),
                            drawable.intrinsicHeight.coerceAtMost(256),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bm)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        artwork = bm
                    }
                }
            }
        }

        val source = mediaSources.getOrPut(pkg) { TrackedMediaSource(pkg) }
        if (title != null) source.title = title
        if (artist != null) source.artist = artist
        if (artwork != null) {
            source.artwork = artwork
            source.rawColors = extractRawLedColors(artwork)
            source.colors = source.rawColors.map { ensureLedVisible(it) }
            source.primaryColor = source.colors.firstOrNull() ?: DEFAULT_COLOR
            source.secondaryColor = source.colors.getOrNull(4) ?: source.colors.getOrNull(1) ?: DEFAULT_COLOR
        } else if (source.colors.isEmpty()) {
            val notifColor = if (notif.color != 0) notif.color else null
            if (notifColor != null) {
                source.rawColors = rawPaletteFromSingleColor(notifColor)
                source.colors = paletteFromSingleColor(notifColor)
            } else {
                source.rawColors = fallbackLedColors((title ?: pkg).hashCode())
                source.colors = source.rawColors
            }
            source.primaryColor = source.colors.firstOrNull() ?: DEFAULT_COLOR
            source.secondaryColor = source.colors.getOrNull(4) ?: source.colors.getOrNull(1) ?: DEFAULT_COLOR
        }

        // If controller exists, its playbackState is authoritative
        val controllerState = runCatching { source.controller?.playbackState?.state }.getOrNull()
        if (controllerState != null) {
            notifIsPlaying = controllerState == PlaybackState.STATE_PLAYING ||
                controllerState == PlaybackState.STATE_BUFFERING ||
                controllerState == PlaybackState.STATE_FAST_FORWARDING ||
                controllerState == PlaybackState.STATE_REWINDING
        }

        source.isPlaying = notifIsPlaying
        if (notifIsPlaying || source.lastActiveTime == 0L) {
            source.lastActiveTime = System.currentTimeMillis()
        }

        refreshSessions()
        reconcileActiveMedia()
    }

    /** Called from NotificationTrigger when a media notification is removed. */
    fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sampleModeActive) return
        val pkg = sbn.packageName
        val source = mediaSources[pkg]
        if (source != null) {
            val controllerState = runCatching { source.controller?.playbackState?.state }.getOrNull()
            if (controllerState != PlaybackState.STATE_PLAYING &&
                controllerState != PlaybackState.STATE_BUFFERING) {
                source.callback?.let { cb -> runCatching { source.controller?.unregisterCallback(cb) } }
                mediaSources.remove(pkg)
            } else {
                source.isPlaying = false
            }
        }
        reconcileActiveMedia()
    }

    /**
     * Reconciles all active media sources and selects the most relevant one:
     * 1. Prioritizes ANY source that is currently playing.
     * 2. If multiple sources are playing, chooses the most recently active.
     * 3. If no sources are playing, retains the most recently active source.
     */
    fun reconcileActiveMedia() {
        if (sampleModeActive) return

        val chosenSource = selectActiveSource(mediaSources.values)
        if (chosenSource == null) {
            updateTrackInfo(null)
            return
        }

        val fallback = fallbackLedColors((chosenSource.title ?: chosenSource.packageName).hashCode())
        val info = MediaTrackInfo(
            title = chosenSource.title,
            artist = chosenSource.artist,
            packageName = chosenSource.packageName,
            isPlaying = chosenSource.isPlaying,
            artwork = chosenSource.artwork,
            colors = chosenSource.colors.ifEmpty { fallback },
            rawColors = chosenSource.rawColors.ifEmpty { chosenSource.colors.ifEmpty { fallback } },
            primaryColor = chosenSource.primaryColor,
            secondaryColor = chosenSource.secondaryColor,
            positionMs = chosenSource.positionMs,
            durationMs = chosenSource.durationMs,
            playbackSpeed = chosenSource.playbackSpeed,
            lastPositionUpdateTime = chosenSource.lastPositionUpdateTime,
        )
        updateTrackInfo(info)
    }

    /** Sets or toggles simulated sample media for testing and preview. */
    fun toggleSampleMedia() {
        if (sampleModeActive) {
            sampleModeActive = false
            reconcileActiveMedia()
        } else {
            sampleModeActive = true
            val sampleArtwork = createSampleArtwork()
            val rawColors = extractRawLedColors(sampleArtwork)
            val colors = rawColors.map { ensureLedVisible(it) }
            val sampleInfo = MediaTrackInfo(
                title = "Starfall Symphony",
                artist = "Pixel Wave",
                packageName = "com.sample.music",
                isPlaying = true,
                artwork = sampleArtwork,
                colors = colors,
                rawColors = rawColors,
                primaryColor = colors[0],
                secondaryColor = colors[4],
                positionMs = 45_000L,
                durationMs = 180_000L,
                playbackSpeed = 1f,
                lastPositionUpdateTime = SystemClock.elapsedRealtime(),
            )
            updateTrackInfo(sampleInfo)
        }
    }

    fun isSampleMode(): Boolean = sampleModeActive

    private fun updateTrackInfo(info: MediaTrackInfo?) {
        if (_currentMedia.value == info) return
        _currentMedia.value = info
        onMediaStateChanged(info)
    }

    companion object {
        private const val TAG = "MediaTracker"
        const val DEFAULT_COLOR = 0xFF00E5FF.toInt()

        /**
         * Selects the active media source from a collection of tracked sources:
         * - Any source that is playing has absolute priority over paused sources.
         * - Among playing sources (or paused sources), the most recently active is chosen.
         */
        internal fun selectActiveSource(sources: Collection<TrackedMediaSource>): TrackedMediaSource? {
            if (sources.isEmpty()) return null
            val playingSources = sources.filter { it.isPlaying }
            return if (playingSources.isNotEmpty()) {
                playingSources.maxByOrNull { it.lastActiveTime }
            } else {
                sources.maxByOrNull { it.lastActiveTime }
            }
        }

        /**
         * Extracts 8 vivid, saturated LED colors from a Bitmap album cover.
         * Downsamples the image to 48x48 for performance and analyzes HSV distributions.
         */
        fun extractLedColors(bitmap: Bitmap): List<Int> {
            return extractRawLedColors(bitmap).map { ensureLedVisible(it) }
        }

        /**
         * Extracts 8 raw LED colors from a Bitmap album cover before LED visibility/brightness optimization.
         */
        fun extractRawLedColors(bitmap: Bitmap): List<Int> {
            val size = 48
            val scaled = if (bitmap.width != size || bitmap.height != size) {
                runCatching { Bitmap.createScaledBitmap(bitmap, size, size, true) }.getOrDefault(bitmap)
            } else bitmap

            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            return extractRawLedColorsFromPixels(pixels)
        }

        /** Internal representation of a color bin candidate with its pixel frequency. */
        private data class ColorCandidate(val color: Int, val count: Int)

        /**
         * Extracts the true dominant colors from a pixel buffer before LED visibility/brightness optimization.
         */
        fun extractRawLedColorsFromPixels(pixels: IntArray): List<Int> {
            if (pixels.isEmpty()) return fallbackLedColors(42)

            // 15-bit color quantization: 5 bits per RGB channel (32x32x32 = 32,768 bins)
            val histogram = IntArray(32768)
            val sumR = IntArray(32768)
            val sumG = IntArray(32768)
            val sumB = IntArray(32768)

            var validPixels = 0
            for (pixel in pixels) {
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha < 128) continue

                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                histogram[bin]++
                sumR[bin] += r
                sumG[bin] += g
                sumB[bin] += b
                validPixels++
            }

            if (validPixels == 0) return fallbackLedColors(42)

            // Collect non-empty color bins
            val candidates = ArrayList<ColorCandidate>()
            for (bin in 0 until 32768) {
                val count = histogram[bin]
                if (count > 0) {
                    val r = sumR[bin] / count
                    val g = sumG[bin] / count
                    val b = sumB[bin] / count
                    val c = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    candidates.add(ColorCandidate(c, count))
                }
            }

            // Order candidates by frequency (most dominant first)
            candidates.sortByDescending { it.count }

            // Extract distinct dominant colors (diversity filtering)
            val distinctColors = ArrayList<Int>()
            val minDistanceSq = 1800 // ~42 distance in weighted RGB space

            for (cand in candidates) {
                val c = cand.color
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                var isTooClose = false
                for (accepted in distinctColors) {
                    val ar = (accepted ushr 16) and 0xFF
                    val ag = (accepted ushr 8) and 0xFF
                    val ab = accepted and 0xFF
                    val dr = r - ar
                    val dg = g - ag
                    val db = b - ab
                    val distSq = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                    if (distSq < minDistanceSq) {
                        isTooClose = true
                        break
                    }
                }

                if (!isTooClose) {
                    distinctColors.add(c)
                    if (distinctColors.size >= 8) break
                }
            }

            if (distinctColors.isEmpty()) {
                distinctColors.add(candidates.first().color)
            }

            // Map the true dominant colors across the 8 LEDs
            val n = distinctColors.size
            return (0 until LED_COUNT).map { i ->
                when {
                    n == 1 -> distinctColors[0]
                    n == 2 -> mixColors(distinctColors[0], distinctColors[1], i.toFloat() / (LED_COUNT - 1))
                    else -> {
                        val t = i.toFloat() / LED_COUNT * n
                        val idxA = t.toInt() % n
                        val idxB = (idxA + 1) % n
                        val frac = t - t.toInt()
                        mixColors(distinctColors[idxA], distinctColors[idxB], frac)
                    }
                }
            }
        }

        /**
         * Extracts the true dominant colors from a pixel buffer using 15-bit color quantization
         * and perceptual diversity filtering, optimized for LED array display.
         */
        fun extractLedColorsFromPixels(pixels: IntArray): List<Int> {
            return extractRawLedColorsFromPixels(pixels).map { ensureLedVisible(it) }
        }

        fun mixColors(c1: Int, c2: Int, t: Float): Int {
            val factor = t.coerceIn(0f, 1f)
            val r1 = (c1 ushr 16) and 0xFF
            val g1 = (c1 ushr 8) and 0xFF
            val b1 = c1 and 0xFF
            val r2 = (c2 ushr 16) and 0xFF
            val g2 = (c2 ushr 8) and 0xFF
            val b2 = c2 and 0xFF
            val r = (r1 + (r2 - r1) * factor).toInt().coerceIn(0, 255)
            val g = (g1 + (g2 - g1) * factor).toInt().coerceIn(0, 255)
            val b = (b1 + (b2 - b1) * factor).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun ensureLedVisible(color: Int): Int {
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            val maxChannel = maxOf(r, g, b)
            // Pure black → fallback to white rather than a hue-less void.
            if (maxChannel == 0) return (0xFF shl 24) or (255 shl 16)
            // Colors below 30% HSV value are intentionally dark (e.g. near-black) — keep as-is.
            if (maxChannel < 77) return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            // Work in HSV so we can control brightness and saturation independently.
            val hsv = FloatArray(3)
            colorToHsv(color, hsv)
            // Near-white / achromatic: original saturation is already very low — pin to full
            // brightness but leave saturation alone so the color stays white, not hue-shifted.
            if (hsv[1] < 0.15f) return hsvToColor(hsv[0], hsv[1], 1f)
            // Chromatic color: pin V=1 and clamp S to ≥0.85 to prevent washed-out pastels.
            return hsvToColor(hsv[0], hsv[1].coerceAtLeast(0.85f), 1f)
        }

        fun colorToHsv(color: Int, outHsv: FloatArray) {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val h = when {
                delta == 0f -> 0f
                max == r -> (60f * ((g - b) / delta) + 360f) % 360f
                max == g -> (60f * ((b - r) / delta) + 120f) % 360f
                else -> (60f * ((r - g) / delta) + 240f) % 360f
            }
            val s = if (max == 0f) 0f else delta / max
            outHsv[0] = h
            outHsv[1] = s
            outHsv[2] = max
        }

        fun hsvToColor(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val x = c * (1 - abs((h / 60f) % 2 - 1))
            val m = v - c
            val (r, g, b) = when (((h / 60f).toInt()) % 6) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return (0xFF shl 24) or
                (((r + m) * 255f).toInt().coerceIn(0, 255) shl 16) or
                (((g + m) * 255f).toInt().coerceIn(0, 255) shl 8) or
                ((b + m) * 255f).toInt().coerceIn(0, 255)
        }

        fun rawPaletteFromSingleColor(seedColor: Int): List<Int> {
            val hsv = FloatArray(3)
            colorToHsv(seedColor, hsv)
            val baseHue = hsv[0]
            val sat = hsv[1]
            val value = hsv[2]
            return (0 until LED_COUNT).map { i ->
                val hue = (baseHue + (i - (LED_COUNT - 1) / 2f) * 4f + 360f) % 360f
                hsvToColor(hue, sat, value)
            }
        }

        fun paletteFromSingleColor(seedColor: Int): List<Int> {
            return rawPaletteFromSingleColor(seedColor).map { ensureLedVisible(it) }
        }

        fun fallbackLedColors(seed: Int): List<Int> {
            val baseHue = abs(seed % 360).toFloat()
            return (0 until LED_COUNT).map { i ->
                val hue = (baseHue + i * (360f / LED_COUNT)) % 360f
                hsvToColor(hue, 0.9f, 1f)
            }
        }

        private fun createSampleArtwork(): Bitmap {
            val bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)
            val paint = android.graphics.Paint()

            // Deep slate navy background (#162032)
            paint.color = 0xFF162032.toInt()
            canvas.drawRect(0f, 0f, 96f, 96f, paint)

            // Warm coral (#E05A47)
            paint.color = 0xFFE05A47.toInt()
            canvas.drawCircle(48f, 48f, 28f, paint)

            // Muted gold accent (#F2C054)
            paint.color = 0xFFF2C054.toInt()
            canvas.drawCircle(48f, 48f, 12f, paint)

            return bm
        }
    }
}
