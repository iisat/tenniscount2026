package com.tenniscount.app.telegram

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramApiLoggingTest {

    @Test
    fun `request failure message excludes exception details containing token`() {
        val token = "123456:secret-token"
        val error = SocketTimeoutException(
            "timeout calling https://api.telegram.org/bot$token/sendMessage",
        )

        val message = TelegramApi.requestFailureMessage(error)

        assertEquals("Telegram request failed: SocketTimeoutException", message)
        assertFalse(message.contains(token))
        assertFalse(message.contains("api.telegram.org"))
    }

    @Test
    fun `api error message redacts token`() {
        val token = "123456:secret-token"

        val message = TelegramApi.apiErrorMessage(
            description = "Unauthorized for bot$token",
            token = token,
        )

        assertEquals("Telegram API error: Unauthorized for bot[redacted]", message)
        assertFalse(message.contains(token))
    }
}
