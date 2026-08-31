package com.tenniscount.app.telegram

/**
 * Результат проверки подключения к Telegram Bot API.
 */
enum class TelegramCheckResult {
    /** Telegram отключён или не заполнены обязательные поля. */
    NotConfigured,

    /** Настройки заполнены, но проверка ещё не выполнялась. */
    Unchecked,

    /** Проверка выполняется. */
    Checking,

    /** Токен и чат прошли проверку. */
    Connected,

    /** Неверный или отозванный токен бота. */
    AuthError,

    /** Нет доступа к указанному чату (chatId не найден, бот заблокирован и т.п.). */
    ChatError,

    /** Сетевая ошибка или недоступность Telegram API. */
    NetworkError,
}
