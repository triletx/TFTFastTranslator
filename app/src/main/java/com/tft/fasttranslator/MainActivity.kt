package com.tft.fasttranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var btnGrantOverlay: Button
    private lateinit var tvCacheCount: TextView

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, FloatingTranslateService::class.java).apply {
                putExtra(FloatingTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingTranslateService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Bạn cần cấp quyền chia sẻ màn hình để dịch.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        tvCacheCount = findViewById(R.id.tvCacheCount)

        val btnSaveKey: Button = findViewById(R.id.btnSaveKey)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)
        val btnClearCache: Button = findViewById(R.id.btnClearCache)
        val switchInPlace: SwitchMaterial = findViewById(R.id.switchInPlace)
        val seekFontSize: SeekBar = findViewById(R.id.seekFontSize)
        val tvFontSizeLabel: TextView = findViewById(R.id.tvFontSizeLabel)

        etApiKey.setText(Prefs.getApiKey(this))

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            Prefs.saveApiKey(this, key)
            Toast.makeText(this, "Đã lưu Gemini API Key!", Toast.LENGTH_SHORT).show()
        }

        switchInPlace.isChecked = Prefs.isInPlaceMode(this)
        switchInPlace.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setInPlaceMode(this, isChecked)
        }

        val currentFont = Prefs.getFontSizeSp(this).toInt().coerceIn(10, 18)
        seekFontSize.progress = currentFont - 10
        tvFontSizeLabel.text = "Cỡ chữ bản dịch: ${currentFont}sp"
        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val sp = progress + 10
                tvFontSizeLabel.text = "Cỡ chữ bản dịch: ${sp}sp"
                Prefs.setFontSizeSp(this@MainActivity, sp.toFloat())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnClearCache.setOnClickListener {
            Prefs.clearCache(this)
            refreshStatus()
            Toast.makeText(this, "Đã xóa bộ nhớ đệm!", Toast.LENGTH_SHORT).show()
        }

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStart.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Vui lòng dán và lưu Gemini API Key trước!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveApiKey(this, key)

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Hãy cấp quyền hiển thị trên ứng dụng khác trước!", Toast.LENGTH_SHORT).show()
                btnGrantOverlay.performClick()
                return@setOnClickListener
            }

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingTranslateService::class.java))
            Toast.makeText(this, "Đã tắt nút nổi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = Settings.canDrawOverlays(this)
        if (granted) {
            btnGrantOverlay.text = "✓ Đã cấp quyền hiển thị nổi"
            btnGrantOverlay.setTextColor(Color.parseColor("#0AC8B9"))
        } else {
            btnGrantOverlay.text = "Cấp quyền hiển thị trên ứng dụng khác"
            btnGrantOverlay.setTextColor(Color.parseColor("#F0E6D2"))
        }
        tvCacheCount.text = "Bộ nhớ đệm (Cache): ${Prefs.getCacheCount(this)} mục"
    }
}
