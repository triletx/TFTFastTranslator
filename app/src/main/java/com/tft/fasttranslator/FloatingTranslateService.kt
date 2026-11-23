package com.tft.fasttranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

class FloatingTranslateService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "tft_fast_translator_channel"
        private const val NOTIF_ID = 1001
        private const val CAPTURE_SCALE = 0.7f
    }

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var bubbleView: TextView? = null
    private var resultContainer: ScrollView? = null
    private var resultTextView: TextView? = null
    private var regionSelectorView: RegionSelectorView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private var isProcessing = false

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
            if (bubbleView == null) {
                addFloatingBubble()
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        releaseCapture()
        mediaProjection?.stop()
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)
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

    private fun startOneShotCapture() {
        releaseCapture()
        val (w, h, d) = getScreenDimensions()
        val capW = (w * CAPTURE_SCALE).toInt().coerceAtLeast(1)
        val capH = (h * CAPTURE_SCALE).toInt().coerceAtLeast(1)

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TFTTranslatorCapture",
            capW, capH, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun releaseCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun addFloatingBubble() {
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val bubble = TextView(this).apply {
            text = "Dịch"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_bubble)
            elevation = 8f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTime = 0L
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            openRegionSelector()
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val duration = System.currentTimeMillis() - downTime
                    if (!longPressTriggered && dx < 18 && dy < 18 && duration < 500) {
                        triggerFastTranslate()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, params)
    }

    private fun openRegionSelector() {
        if (regionSelectorView != null) return
        hideResultCard()
        val selector = RegionSelectorView(this, Prefs.getCropRect(this)) { newNormRect ->
            Prefs.saveCropRect(this, newNormRect)
            removeRegionSelector()
            Toast.makeText(this, "Đã lưu vùng quét mới!", Toast.LENGTH_SHORT).show()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
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

        hideResultCard()
        bubbleView?.visibility = View.INVISIBLE

        startOneShotCapture()

        mainHandler.postDelayed({
            val bitmap = captureFrame()
            releaseCapture()
            bubbleView?.visibility = View.VISIBLE

            if (bitmap == null) {
                isProcessing = false
                showResultCard("Không chụp được màn hình. Hãy thử bấm lại.")
                return@postDelayed
            }

            showResultCard("Đang dịch...")

            val cropNorm = Prefs.getCropRect(this)
            val left = (cropNorm.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (cropNorm.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val cropW = ((cropNorm.right - cropNorm.left) * bitmap.width).toInt().coerceIn(1, bitmap.width - left)
            val cropH = ((cropNorm.bottom - cropNorm.top) * bitmap.height).toInt().coerceIn(1, bitmap.height - top)

            val cropped = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
            bitmap.recycle()

            val image = InputImage.fromBitmap(cropped, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cropped.recycle()
                    val sortedBlocks = visionText.textBlocks.sortedWith(
                        compareBy<com.google.mlkit.vision.text.Text.TextBlock> {
                            val box = it.boundingBox
                            if (box != null && cropW > cropH) {
                                (box.centerX() * 3) / cropW
                            } else {
                                0
                            }
                        }.thenBy { it.boundingBox?.top ?: 0 }
                    )

                    val extracted = sortedBlocks.joinToString("\n") { it.text }.trim()
                    if (extracted.isEmpty()) {
                        isProcessing = false
                        showResultCard("Không nhận diện được chữ tiếng Trung trong vùng quét.\n(Nhấn giữ nút Dịch để chọn lại vùng quét)")
                        return@addOnSuccessListener
                    }

                    val apiKey = Prefs.getApiKey(this)
                    serviceScope.launch {
                        val translated = TftTranslator.translate(extracted, apiKey)
                        showResultCard(translated)
                        isProcessing = false
                    }
                }
                .addOnFailureListener { e ->
                    cropped.recycle()
                    isProcessing = false
                    showResultCard("Lỗi quét OCR: ${e.message}")
                }
        }, 120)
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

    private fun showResultCard(text: String) {
        if (resultContainer == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.parseColor("#F5F5F5"))
                textSize = 14f
                setPadding(32, 24, 32, 24)
                setLineSpacing(6f, 1.0f)
                setOnClickListener { hideResultCard() }
            }

            val scroll = ScrollView(this).apply {
                setBackgroundResource(R.drawable.bg_result_card)
                addView(tv)
                setOnClickListener { hideResultCard() }
            }

            val (w, h, _) = getScreenDimensions()
            val maxW = (w * 0.72f).toInt()
            val maxH = (h * 0.65f).toInt()

            val params = WindowManager.LayoutParams(
                maxW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 40
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

    private fun hideResultCard() {
        resultContainer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        resultContainer = null
        resultTextView = null
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
            .setContentTitle("TFT Dịch Nhanh đang chạy")
            .setContentText("Chạm nút nổi trên màn hình để dịch tức thì")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        hideResultCard()
        removeRegionSelector()
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
        releaseCapture()
        mediaProjection?.stop()
        recognizer.close()
    }
}
