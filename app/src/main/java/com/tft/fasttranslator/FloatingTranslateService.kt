package com.tft.fasttranslator

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

class FloatingTranslateService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "tft_fast_translator_channel"
        private const val NOTIF_ID = 1001
        private const val CAPTURE_SCALE = 0.75f
        private val CJK_REGEX = Regex("[\\u4E00-\\u9FFF]")
    }

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var bubbleContainer: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleSpinner: ProgressBar? = null

    private var dismissTargetView: TextView? = null
    private var isInDismissZone = false

    private var inPlaceOverlayView: InPlaceOverlayView? = null
    private var resultContainer: ScrollView? = null
    private var resultTextView: TextView? = null
    private var regionSelectorView: RegionSelectorView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private var isProcessing = false

    private val dimBubbleRunnable = Runnable {
        bubbleContainer?.animate()?.alpha(0.35f)?.setDuration(300)?.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            setupMediaProjection(resultCode, resultData)
            if (bubbleContainer == null) {
                addFloatingBubble()
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideAllOverlays()
        mainHandler.postDelayed({ snapBubbleToEdge(animate = false) }, 120)
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pauseCaptureSurface()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        mediaProjection?.stop()

        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        val (w, h, d) = getScreenDimensions()
        val capW = (w * CAPTURE_SCALE).toInt().coerceAtLeast(1)
        val capH = (h * CAPTURE_SCALE).toInt().coerceAtLeast(1)

        // Create once with null surface (zero idle battery drain, Android 14+ safe)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TFTTranslatorCapture",
            capW, capH, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            mainHandler
        )
    }

    private fun getScreenDimensions(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun resumeCaptureSurface() {
        val (w, h, d) = getScreenDimensions()
        val capW = (w * CAPTURE_SCALE).toInt().coerceAtLeast(1)
        val capH = (h * CAPTURE_SCALE).toInt().coerceAtLeast(1)

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(capW, capH, d)
        virtualDisplay?.surface = imageReader?.surface
    }

    private fun pauseCaptureSurface() {
        try { virtualDisplay?.surface = null } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun addFloatingBubble() {
        val sizePx = dp(52)
        val container = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_bubble)
            elevation = dp(6).toFloat()
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_translate_gold)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
        }
        container.addView(icon, FrameLayout.LayoutParams(sizePx, sizePx))

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0AC8B9"))
            visibility = View.GONE
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
        }
        container.addView(spinner, FrameLayout.LayoutParams(sizePx, sizePx))

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(6)
            y = dp(180)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTime = 0L
        var longPressTriggered = false
        var dragging = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            openRegionSelector()
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(dimBubbleRunnable)
                    container.animate().cancel()
                    container.alpha = 1.0f
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    longPressTriggered = false
                    dragging = false
                    mainHandler.postDelayed(longPressRunnable, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!dragging && (abs(dx) > 14 || abs(dy) > 14)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        showDismissTarget()
                    }
                    if (dragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
                        updateDismissZoneState(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val shouldDismiss = dragging && isInDismissZone
                    hideDismissTarget()
                    if (shouldDismiss) {
                        Toast.makeText(this, "Đã tắt TFT Dịch Nhanh", Toast.LENGTH_SHORT).show()
                        stopSelf()
                    } else if (!longPressTriggered && !dragging && (System.currentTimeMillis() - downTime) < 500) {
                        triggerFastTranslate()
                    } else {
                        snapBubbleToEdge(animate = true)
                        scheduleDimBubble()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleContainer = container
        bubbleParams = params
        bubbleIcon = icon
        bubbleSpinner = spinner
        windowManager.addView(container, params)
        scheduleDimBubble()
    }

    private fun scheduleDimBubble() {
        mainHandler.removeCallbacks(dimBubbleRunnable)
        if (!isProcessing) {
            mainHandler.postDelayed(dimBubbleRunnable, 2500)
        }
    }

    private fun snapBubbleToEdge(animate: Boolean) {
        val container = bubbleContainer ?: return
        val params = bubbleParams ?: return
        val (w, h, _) = getScreenDimensions()
        val sizePx = params.width
        val edgeMargin = dp(4)

        val targetX = if (params.x + sizePx / 2 < w / 2) edgeMargin else (w - sizePx - edgeMargin)
        val clampedY = params.y.coerceIn(dp(24), (h - sizePx - dp(24)).coerceAtLeast(dp(24)))

        if (!animate) {
            params.x = targetX
            params.y = clampedY
            try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
            return
        }

        val startX = params.x
        params.y = clampedY
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try { windowManager.updateViewLayout(container, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        isInDismissZone = false
        val sizePx = dp(58)
        val tv = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F0E6D2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dismiss_circle)
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32)
        }
        dismissTargetView = tv
        windowManager.addView(tv, params)
        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    private fun updateDismissZoneState(rawX: Float, rawY: Float) {
        val target = dismissTargetView ?: return
        val (w, h, _) = getScreenDimensions()
        val targetCenterX = w / 2f
        val targetCenterY = h - dp(32) - dp(29).toFloat()
        val dist = hypot((rawX - targetCenterX).toDouble(), (rawY - targetCenterY).toDouble())
        val inside = dist < dp(72)

        if (inside != isInDismissZone) {
            isInDismissZone = inside
            if (inside) {
                target.setBackgroundResource(R.drawable.bg_dismiss_circle_active)
                target.animate().scaleX(1.22f).scaleY(1.22f).setDuration(120).start()
                bubbleContainer?.animate()?.scaleX(0.82f)?.scaleY(0.82f)?.setDuration(120)?.start()
            } else {
                target.setBackgroundResource(R.drawable.bg_dismiss_circle)
                target.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                bubbleContainer?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            }
        }
    }

    private fun hideDismissTarget() {
        isInDismissZone = false
        bubbleContainer?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
        dismissTargetView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dismissTargetView = null
    }

    private fun setBubbleLoading(loading: Boolean) {
        bubbleSpinner?.visibility = if (loading) View.VISIBLE else View.GONE
        bubbleIcon?.alpha = if (loading) 0.45f else 1.0f
        if (loading) {
            mainHandler.removeCallbacks(dimBubbleRunnable)
            bubbleContainer?.alpha = 1.0f
        } else {
            scheduleDimBubble()
        }
    }

    private fun openRegionSelector() {
        if (regionSelectorView != null) return
        hideAllOverlays()
        val selector = RegionSelectorView(this, Prefs.getCropRect(this)) { newNormRect ->
            Prefs.saveCropRect(this, newNormRect)
            removeRegionSelector()
            val isFull = newNormRect.left <= 0.01f && newNormRect.top <= 0.01f &&
                newNormRect.right >= 0.99f && newNormRect.bottom >= 0.99f
            val msg = if (isFull) "Đã chuyển về quét Full màn hình" else "Đã lưu vùng quét tùy chỉnh"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            scheduleDimBubble()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        regionSelectorView = selector
        windowManager.addView(selector, params)
    }

    private fun removeRegionSelector() {
        regionSelectorView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        regionSelectorView = null
    }

    private fun triggerFastTranslate() {
        if (isProcessing) return
        isProcessing = true
        hideAllOverlays()
        bubbleContainer?.visibility = View.INVISIBLE

        try {
            resumeCaptureSurface()
        } catch (e: Throwable) {
            bubbleContainer?.visibility = View.VISIBLE
            isProcessing = false
            showSummaryCard("Lỗi chụp màn hình: ${e.message}")
            return
        }

        captureWithRetry(attempt = 0)
    }

    private fun captureWithRetry(attempt: Int) {
        val delayMs = if (attempt == 0) 110L else 90L
        mainHandler.postDelayed({
            val bitmap = captureFrame()
            if (bitmap == null && attempt < 2) {
                captureWithRetry(attempt + 1)
                return@postDelayed
            }

            pauseCaptureSurface()
            bubbleContainer?.visibility = View.VISIBLE

            if (bitmap == null) {
                isProcessing = false
                setBubbleLoading(false)
                showSummaryCard("Không chụp được khung hình. Hãy chạm nút Dịch lần nữa.")
                return@postDelayed
            }

            setBubbleLoading(true)
            processCapturedBitmap(bitmap)
        }, delayMs)
    }

    private fun processCapturedBitmap(bitmap: Bitmap) {
        val (screenW, screenH, _) = getScreenDimensions()
        val scaleX = bitmap.width.toFloat() / screenW.coerceAtLeast(1)
        val scaleY = bitmap.height.toFloat() / screenH.coerceAtLeast(1)

        val cropNorm = Prefs.getCropRect(this)
        val cropLeft = (cropNorm.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val cropTop = (cropNorm.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val cropW = ((cropNorm.right - cropNorm.left) * bitmap.width).toInt().coerceIn(1, bitmap.width - cropLeft)
        val cropH = ((cropNorm.bottom - cropNorm.top) * bitmap.height).toInt().coerceIn(1, bitmap.height - cropTop)

        val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
        bitmap.recycle()

        val image = InputImage.fromBitmap(cropped, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                cropped.recycle()

                // Filter only blocks containing Chinese characters
                val chineseBlocks = visionText.textBlocks.filter { block ->
                    CJK_REGEX.containsMatchIn(block.text)
                }

                if (chineseBlocks.isEmpty()) {
                    finishProcessing()
                    showSummaryCard("Không thấy chữ tiếng Trung trên màn hình.")
                    return@addOnSuccessListener
                }

                val apiKey = Prefs.getApiKey(this)

                if (Prefs.isInPlaceMode(this)) {
                    val texts = chineseBlocks.map { it.text.replace("\n", " ").trim() }
                    val screenRects = chineseBlocks.map { block ->
                        val box = block.boundingBox
                        if (box != null) {
                            RectF(
                                (cropLeft + box.left) / scaleX,
                                (cropTop + box.top) / scaleY,
                                (cropLeft + box.right) / scaleX,
                                (cropTop + box.bottom) / scaleY
                            )
                        } else {
                            RectF(dp(20).toFloat(), dp(40).toFloat(), (screenW - dp(20)).toFloat(), dp(90).toFloat())
                        }
                    }

                    serviceScope.launch {
                        val res = TftTranslator.translateBlocks(this@FloatingTranslateService, texts, apiKey)
                        finishProcessing()
                        res.fold(
                            onSuccess = { translatedList ->
                                val overlayBlocks = screenRects.zip(translatedList) { r, t -> OverlayBlock(r, t) }
                                showInPlaceOverlay(screenW, screenH, overlayBlocks)
                            },
                            onFailure = { err ->
                                showSummaryCard(err.message ?: "Lỗi dịch")
                            }
                        )
                    }
                } else {
                    val sortedBlocks = chineseBlocks.sortedWith(
                        compareBy<com.google.mlkit.vision.text.Text.TextBlock> {
                            val box = it.boundingBox
                            if (box != null && cropW > cropH) (box.centerX() * 3) / cropW else 0
                        }.thenBy { it.boundingBox?.top ?: 0 }
                    )
                    val extracted = sortedBlocks.joinToString("\n") { it.text }.trim()
                    serviceScope.launch {
                        val res = TftTranslator.translateSummary(this@FloatingTranslateService, extracted, apiKey)
                        finishProcessing()
                        res.fold(
                            onSuccess = { showSummaryCard(it) },
                            onFailure = { showSummaryCard(it.message ?: "Lỗi dịch") }
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                cropped.recycle()
                finishProcessing()
                showSummaryCard("Lỗi quét OCR: ${e.message}")
            }
    }

    private fun finishProcessing() {
        isProcessing = false
        setBubbleLoading(false)
    }

    private fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            val trimmed = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
            fullBitmap.recycle()
            trimmed
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun showInPlaceOverlay(screenW: Int, screenH: Int, blocks: List<OverlayBlock>) {
        hideAllOverlays()
        val fontSize = Prefs.getFontSizeSp(this)
        val overlay = InPlaceOverlayView(this, screenW, screenH, blocks, fontSize) {
            hideInPlaceOverlay()
        }
        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        inPlaceOverlayView = overlay
        windowManager.addView(overlay, params)
    }

    private fun hideInPlaceOverlay() {
        inPlaceOverlayView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        inPlaceOverlayView = null
    }

    private fun showSummaryCard(text: String) {
        hideInPlaceOverlay()
        if (resultContainer == null) {
            val fontSize = Prefs.getFontSizeSp(this)
            val tv = TextView(this).apply {
                setTextColor(Color.parseColor("#F0E6D2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setLineSpacing(dp(3).toFloat(), 1.0f)
                setOnClickListener { hideSummaryCard() }
            }
            val scroll = ScrollView(this).apply {
                setBackgroundResource(R.drawable.bg_result_card)
                addView(tv)
                setOnClickListener { hideSummaryCard() }
            }
            val (w, h, _) = getScreenDimensions()
            val maxW = (w * 0.78f).toInt()
            val maxH = (h * 0.68f).toInt()

            val params = WindowManager.LayoutParams(
                maxW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(18)
            }

            scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.height > maxH) {
                    params.height = maxH
                    try { windowManager.updateViewLayout(scroll, params) } catch (_: Exception) {}
                }
            }

            resultTextView = tv
            resultContainer = scroll
            windowManager.addView(scroll, params)
        }
        resultTextView?.text = text
        resultContainer?.visibility = View.VISIBLE
    }

    private fun hideSummaryCard() {
        resultContainer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        resultContainer = null
        resultTextView = null
    }

    private fun hideAllOverlays() {
        hideInPlaceOverlay()
        hideSummaryCard()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TFT Dịch Nhanh",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TFT Dịch Nhanh Hextech")
            .setContentText("Chạm nút nổi để dịch • Kéo xuống X để tắt")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        hideAllOverlays()
        hideDismissTarget()
        removeRegionSelector()
        bubbleContainer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleContainer = null
        pauseCaptureSurface()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        mediaProjection?.stop()
        recognizer.close()
    }
}
