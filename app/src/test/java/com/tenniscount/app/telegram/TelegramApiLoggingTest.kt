package com.tenniscount.app.telegram

import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramApiLoggingTest {

    @Test
    fun `connection has finite connect and read timeouts`() {
        val connection = TestUrlConnection()

        TelegramApi.configureTimeouts(connection)

        assertEquals(10_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
    }

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

    private class TestUrlConnection : URLConnection(URL("https://example.com")) {
        override fun connect() = Unit
    }
}
