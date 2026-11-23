package com.tft.fasttranslator

import android.content.Context
import android.graphics.RectF

object Prefs {
    private const val PREF_NAME = "tft_translator_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_LEFT = "crop_left"
    private const val KEY_TOP = "crop_top"
    private const val KEY_RIGHT = "crop_right"
    private const val KEY_BOTTOM = "crop_bottom"

    fun getApiKey(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""
    }

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API, key.trim())
            .apply()
    }

    // Default normalized crop region: center area where 3 Augments appear in landscape
    fun getCropRect(context: Context): RectF {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val left = sp.getFloat(KEY_LEFT, 0.12f)
        val top = sp.getFloat(KEY_TOP, 0.18f)
        val right = sp.getFloat(KEY_RIGHT, 0.88f)
        val bottom = sp.getFloat(KEY_BOTTOM, 0.84f)
        return RectF(left, top, right, bottom)
    }

    fun saveCropRect(context: Context, rect: RectF) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LEFT, rect.left.coerceIn(0f, 0.95f))
            .putFloat(KEY_TOP, rect.top.coerceIn(0f, 0.95f))
            .putFloat(KEY_RIGHT, rect.right.coerceIn(0.05f, 1f))
            .putFloat(KEY_BOTTOM, rect.bottom.coerceIn(0.05f, 1f))
            .apply()
    }
}
