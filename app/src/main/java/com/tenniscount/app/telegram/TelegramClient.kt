package com.tenniscount.app.telegram

interface TelegramClient {
    suspend fun sendMessage(token: String, chatId: String, text: String): Int?

    suspend fun editMessage(token: String, chatId: String, messageId: Int, text: String): Boolean

    suspend fun deleteMessage(token: String, chatId: String, messageId: Int): Boolean
}

object TelegramApiClient : TelegramClient {
    override suspend fun sendMessage(token: String, chatId: String, text: String): Int? =
        TelegramApi.sendMessage(token, chatId, text)

    override suspend fun editMessage(
        token: String,
        chatId: String,
        messageId: Int,
        text: String,
    ): Boolean = TelegramApi.editMessageText(token, chatId, messageId, text)

    override suspend fun deleteMessage(token: String, chatId: String, messageId: Int): Boolean =
        TelegramApi.deleteMessage(token, chatId, messageId)
}
