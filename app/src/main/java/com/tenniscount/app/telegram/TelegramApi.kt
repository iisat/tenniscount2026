package com.tenniscount.app.telegram

import com.tenniscount.app.util.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
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
                AppLog.w(TAG, "sendMessage failed: ${json.optString("description")}")
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
            AppLog.w(TAG, "editMessageText failed: ${json.optString("description")}")
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
        json.optBoolean("ok", false)
    }

    private fun post(url: URL, body: String): String? = runCatching {
        val conn = url.openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.onFailure {
        AppLog.w(TAG, "Telegram request error", it)
    }.getOrNull()

    private fun parseJson(response: String): JSONObject? = runCatching {
        JSONObject(response)
    }.onFailure {
        AppLog.w(TAG, "Telegram response parse error", it)
    }.getOrNull()
}
