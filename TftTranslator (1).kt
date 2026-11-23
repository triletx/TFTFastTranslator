package com.tft.fasttranslator

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TftTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    // Instant 0ms cache for repeated tooltips/augments in the same session
    private val cache = LruCache<String, String>(200)

    private const val SYSTEM_PROMPT = """Bạn là trợ lý dịch thuật tốc độ cao chuyên game Đấu Trường Chân Lý (TFT / 金铲铲之战) từ tiếng Trung sang tiếng Việt.
QUY TẮC BẮT BUỘC:
1. Dùng chuẩn thuật ngữ TFT Việt Nam:
   - 攻击力 -> SMCK | 法术强度 -> SMPT | 攻速 -> Tốc đánh | 护甲 -> Giáp | 魔抗 -> Kháng phép
   - 最大生命值 -> Máu tối đa | 全能吸血 -> Hút máu toàn phần | 护盾 -> Lá chắn | 暴击 -> Chí mạng
   - 破甲/护甲击碎 -> Cào giáp | 魔抗击碎 -> Xuyên kháng phép | 重伤 -> Vết thương sâu | 灼烧 -> Thiêu đốt
   - 强化符文 -> Lõi Công Nghệ | 羁绊 -> Tộc/Hệ | 装备 -> Trang bị | 金币 -> Vàng | 经验值 -> Kinh nghiệm
   - 弈子/英雄 -> Tướng | 刷新 -> Roll | 备战席 -> Hàng chờ | 生命值 -> Máu
2. Cực kỳ ngắn gọn, súc tích, chỉ giữ thông số và hiệu ứng chính để người chơi đọc trong 1 giây. Bỏ lời văn thừa.
3. Nếu văn bản gồm 3 Lõi Công Nghệ (tách theo cụm Trái/Giữa/Phải), trình bày rõ thành 3 mục:
   [1] Tên lõi: Hiệu ứng ngắn gọn
   [2] Tên lõi: Hiệu ứng ngắn gọn
   [3] Tên lõi: Hiệu ứng ngắn gọn
4. Không giải thích dài dòng, không chào hỏi, chỉ trả về bản dịch tiếng Việt."""

    suspend fun translate(chineseText: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val cleaned = chineseText.trim()
        if (cleaned.isEmpty()) return@withContext "Không thấy chữ tiếng Trung trong vùng quét."
        if (apiKey.isBlank()) return@withContext "Chưa nhập Gemini API Key trong app."

        cache.get(cleaned)?.let { return@withContext it }

        val models = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash")
        var lastError = "Lỗi kết nối API."

        for (model in models) {
            try {
                val result = callGemini(model, cleaned, apiKey)
                if (result != null) {
                    cache.put(cleaned, result)
                    return@withContext result
                }
            } catch (e: Exception) {
                lastError = "Lỗi ($model): ${e.message}"
            }
        }
        return@withContext lastError
    }

    private fun callGemini(model: String, text: String, apiKey: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 350)
                // Disable thinking for sub-second speed on 2.5 models
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
        }

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: return null
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${respBody.take(160)}")
            }
            val json = JSONObject(respBody)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            val translated = sb.toString().trim()
            return translated.ifEmpty { null }
        }
    }
}
