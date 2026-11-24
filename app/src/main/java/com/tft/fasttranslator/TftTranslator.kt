package com.tft.fasttranslator

import android.content.Context
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val MODELS = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    private const val SYSTEM_PROMPT_BATCH = """Bạn là chuyên gia dịch game Đấu Trường Chân Lý (TFT / Kim Xẻng - 金铲铲之战) Trung sang Việt.
Nhiệm vụ: Dịch từng giá trị trong JSON object sang tiếng Việt CỰC NGẮN GỌN để hiển thị đè trực tiếp lên màn hình game.
Quy tắc:
- Dùng đúng thuật ngữ ĐTCL Việt Nam: Lõi Công Nghệ, STVL, SMPT, Hút máu toàn phần, Lá chắn, Tốc đánh, Giáp, Kháng phép, Lượt roll, Tộc/Hệ, Ấn, Hàng chờ, Chuyển đổi.
- Giữ nguyên mọi con số và %. Không giải thích thêm.
- BẮT BUỘC trả về đúng 1 JSON object chứa chính xác các key số như đầu vào."""

    private const val SYSTEM_PROMPT_SUMMARY = """Bạn là chuyên gia dịch nhanh game Đấu Trường Chân Lý (TFT / Kim Xẻng - 金铲铲之战) Trung sang Việt.
Dịch cực ngắn gọn, đi thẳng vào tên và hiệu ứng chính. Nếu có 3 Lõi Công Nghệ, chia rõ 3 mục [1], [2], [3].
Dùng đúng thuật ngữ ĐTCL Việt Nam (STVL, SMPT, Hút máu toàn phần, Lá chắn, Tốc đánh, Lượt roll, Ấn...). Giữ nguyên các con số."""

    suspend fun translateBlocks(
        context: Context,
        blocks: List<String>,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext Result.success(emptyList())

        val results = MutableList(blocks.size) { "" }
        val uncached = mutableListOf<Pair<Int, String>>()

        blocks.forEachIndexed { idx, raw ->
            val cached = Prefs.getCached(context, raw)
            if (cached != null) {
                results[idx] = cached
            } else {
                uncached.add(idx to raw)
            }
        }

        if (uncached.isEmpty()) {
            return@withContext Result.success(results)
        }

        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Chưa nhập Gemini API Key!"))
        }

        val inputJson = JSONObject()
        uncached.forEach { (idx, text) ->
            inputJson.put(idx.toString(), text)
        }

        val apiResult = callGemini(
            apiKey = apiKey,
            systemPrompt = SYSTEM_PROMPT_BATCH,
            userContent = inputJson.toString(),
            expectJson = true
        )

        apiResult.fold(
            onSuccess = { rawOutput ->
                try {
                    val cleaned = rawOutput.trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()
                    val start = cleaned.indexOf('{')
                    val end = cleaned.lastIndexOf('}')
                    if (start == -1 || end == -1 || end <= start) {
                        return@withContext Result.failure(IllegalStateException("Phản hồi JSON không hợp lệ"))
                    }
                    val parsed = JSONObject(cleaned.substring(start, end + 1))
                    uncached.forEach { (idx, origText) ->
                        val translated = parsed.optString(idx.toString(), "").trim()
                        if (translated.isNotEmpty()) {
                            results[idx] = translated
                            Prefs.putCached(context, origText, translated)
                        } else {
                            results[idx] = origText
                        }
                    }
                    Result.success(results)
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("Lỗi đọc JSON dịch: ${e.message}"))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun translateSummary(
        context: Context,
        fullText: String,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cached = Prefs.getCached(context, "SUM::$fullText")
        if (cached != null) return@withContext Result.success(cached)

        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Chưa nhập Gemini API Key!"))
        }

        val apiResult = callGemini(
            apiKey = apiKey,
            systemPrompt = SYSTEM_PROMPT_SUMMARY,
            userContent = fullText,
            expectJson = false
        )

        apiResult.onSuccess { translated ->
            Prefs.putCached(context, "SUM::$fullText", translated)
        }
    }

    private fun callGemini(
        apiKey: String,
        systemPrompt: String,
        userContent: String,
        expectJson: Boolean
    ): Result<String> {
        var lastError = "Không kết nối được Gemini API"

        for (model in MODELS) {
            try {
                val url = "[https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey](https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey)"
                val genConfig = JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 1024)
                    if (expectJson) {
                        put("responseMimeType", "application/json")
                    }
                }
                val bodyJson = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", userContent)))
                    }))
                    put("generationConfig", genConfig)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val respText = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        lastError = "Lỗi ($model): HTTP ${resp.code}"
                        continue
                    }
                    val root = JSONObject(respText)
                    val text = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                        ?: ""
                    if (text.isNotEmpty()) {
                        return Result.success(text)
                    }
                }
            } catch (e: Exception) {
                lastError = "Lỗi mạng ($model): ${e.message}"
            }
        }
        return Result.failure(IllegalStateException(lastError))
    }
}
