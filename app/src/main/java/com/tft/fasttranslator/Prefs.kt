package com.tft.fasttranslator

import android.content.Context
import android.graphics.RectF

object Prefs {
    private const val PREF_NAME = "tft_fast_translator_prefs"
    private const val CACHE_PREF_NAME = "tft_translation_cache"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_IN_PLACE = "in_place_mode"
    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_CROP_L = "crop_l"
    private const val KEY_CROP_T = "crop_t"
    private const val KEY_CROP_R = "crop_r"
    private const val KEY_CROP_B = "crop_b"

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key.trim()).apply()
    }

    fun getApiKey(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""
    }

    fun setInPlaceMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IN_PLACE, enabled).apply()
    }

    fun isInPlaceMode(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IN_PLACE, true)
    }

    fun setFontSizeSp(context: Context, sizeSp: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FONT_SIZE, sizeSp.coerceIn(10f, 18f)).apply()
    }

    fun getFontSizeSp(context: Context): Float {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FONT_SIZE, 13f)
    }

    fun saveCropRect(context: Context, rect: RectF) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_CROP_L, rect.left)
            .putFloat(KEY_CROP_T, rect.top)
            .putFloat(KEY_CROP_R, rect.right)
            .putFloat(KEY_CROP_B, rect.bottom)
            .apply()
    }

    fun getCropRect(context: Context): RectF {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val l = sp.getFloat(KEY_CROP_L, 0f)
        val t = sp.getFloat(KEY_CROP_T, 0f)
        val r = sp.getFloat(KEY_CROP_R, 1f)
        val b = sp.getFloat(KEY_CROP_B, 1f)
        return RectF(l, t, r, b)
    }

    private fun normalizeKey(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }

    fun getCached(context: Context, source: String): String? {
        val key = normalizeKey(source)
        if (key.isEmpty()) return null
        return context.getSharedPreferences(CACHE_PREF_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    fun putCached(context: Context, source: String, translation: String) {
        val key = normalizeKey(source)
        if (key.isEmpty() || translation.isBlank()) return
        val sp = context.getSharedPreferences(CACHE_PREF_NAME, Context.MODE_PRIVATE)
        if (sp.all.size > 600) {
            sp.edit().clear().apply()
        }
        sp.edit().putString(key, translation.trim()).apply()
    }

    fun getCacheCount(context: Context): Int {
        return context.getSharedPreferences(CACHE_PREF_NAME, Context.MODE_PRIVATE).all.size
    }

    fun clearCache(context: Context) {
        context.getSharedPreferences(CACHE_PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
