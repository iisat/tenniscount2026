package com.tenniscount.app.telegram

import com.tenniscount.app.util.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Обертка над HTTP-методами Telegram Bot API.
 * Токен нигде не логируется — только общие диагностические сообщения.
 */
object TelegramApi {
    private const val TAG = "TelegramApi"

    suspend fun sendMessage(token: String, chatId: String, text: String): Int? =
        withContext(Dispatchers.IO) {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
            }
            val response = post(url, body.toString()) ?: return@withContext null
            val json = parseJson(response) ?: return@withContext null
            if (!json.optBoolean("ok", false)) {
                AppLog.w(TAG, apiErrorMessage(json.optString("description"), token))
                return@withContext null
            }
            json.optJSONObject("result")?.optInt("message_id", -1)?.takeIf { it != -1 }
        }

    suspend fun editMessageText(
        token: String,
        chatId: String,
        messageId: Int,
        text: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = URL("https://api.telegram.org/bot$token/editMessageText")
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
        }
        val response = post(url, body.toString()) ?: return@withContext false
        val json = parseJson(response) ?: return@withContext false
        if (!json.optBoolean("ok", false)) {
            AppLog.w(TAG, apiErrorMessage(json.optString("description"), token))
            return@withContext false
        }
        true
    }

    suspend fun deleteMessage(
        token: String,
        chatId: String,
        messageId: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = URL("https://api.telegram.org/bot$token/deleteMessage")
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
        }
        val response = post(url, body.toString()) ?: return@withContext false
        val json = parseJson(response) ?: return@withContext false
        if (!json.optBoolean("ok", false)) {
            AppLog.w(TAG, apiErrorMessage(json.optString("description"), token))
            return@withContext false
        }
        true
    }

    /**
     * Проверяет токен бота и доступность чата.
     * Не логирует токен и не включает его в возвращаемый результат.
     */
    suspend fun checkConnection(token: String, chatId: String): TelegramCheckResult =
        withContext(Dispatchers.IO) {
            if (token.isBlank() || chatId.isBlank()) {
                return@withContext TelegramCheckResult.NotConfigured
            }

            val meResponse = getMe(token)
            val meResult = parseCheckResponse(meResponse, isChatCheck = false)
            if (meResult != TelegramCheckResult.Connected) {
                return@withContext meResult
            }

            val chatResponse = getChat(token, chatId)
            parseCheckResponse(chatResponse, isChatCheck = true)
        }

    private fun getMe(token: String): String? =
        get(URL("https://api.telegram.org/bot$token/getMe"))

    private fun getChat(token: String, chatId: String): String? {
        val url = URL("https://api.telegram.org/bot$token/getChat")
        val body = JSONObject().apply { put("chat_id", chatId) }
        return post(url, body.toString())
    }

    /**
     * Преобразует сырой ответ Telegram API в результат проверки.
     */
    private fun parseCheckResponse(
        response: String?,
        isChatCheck: Boolean,
    ): TelegramCheckResult {
        if (response == null) return TelegramCheckResult.NetworkError
        val json = parseJson(response) ?: return TelegramCheckResult.NetworkError
        val ok = json.optBoolean("ok", false)
        val description = json.optString("description", "")
        val errorCode = json.optInt("error_code", 0)
        return mapCheckResult(ok, errorCode, description, isChatCheck)
    }

    /**
     * Чистая функция классификации ответа Telegram API.
     * Вынесена отдельно, чтобы unit-тесты не зависели от Android-реализации JSONObject.
     */
    internal fun mapCheckResult(
        ok: Boolean,
        errorCode: Int,
        description: String,
        isChatCheck: Boolean,
    ): TelegramCheckResult {
        if (ok) return TelegramCheckResult.Connected
        val lower = description.lowercase()
        return when {
            isUnauthorized(lower, errorCode) -> TelegramCheckResult.AuthError
            isChatCheck && isChatAccessError(lower, errorCode) -> TelegramCheckResult.ChatError
            else -> TelegramCheckResult.NetworkError
        }
    }

    private fun isUnauthorized(description: String, errorCode: Int): Boolean =
        errorCode == 401 || description.contains("unauthorized")

    private fun isChatAccessError(description: String, errorCode: Int): Boolean =
        errorCode == 403 ||
            errorCode == 400 ||
            description.contains("chat not found") ||
            description.contains("chat_id") ||
            description.contains("wrong") ||
            description.contains("blocked") ||
            description.contains("kicked") ||
            description.contains("not a member") ||
            description.contains("forbidden")

    private fun get(url: URL): String? = runCatching {
        val conn = url.openConnection() as HttpsURLConnection
        try {
            configureTimeouts(conn)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) AppLog.w(TAG, "Telegram API returned HTTP $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.onFailure {
        AppLog.w(TAG, requestFailureMessage(it))
    }.getOrNull()

    private fun post(url: URL, body: String): String? = runCatching {
        val conn = url.openConnection() as HttpsURLConnection
        try {
            configureTimeouts(conn)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) AppLog.w(TAG, "Telegram API returned HTTP $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.onFailure {
        AppLog.w(TAG, requestFailureMessage(it))
    }.getOrNull()

    private fun parseJson(response: String): JSONObject? = runCatching {
        JSONObject(response)
    }.onFailure {
        AppLog.w(TAG, "Telegram response parse failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    internal fun configureTimeouts(connection: URLConnection) {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
    }

    internal fun requestFailureMessage(error: Throwable): String =
        "Telegram request failed: ${error.javaClass.simpleName.ifBlank { "Throwable" }}"

    internal fun apiErrorMessage(description: String, token: String): String {
        val safeDescription = if (token.isEmpty()) description else description.replace(token, "[redacted]")
        return "Telegram API error: $safeDescription"
    }
}
