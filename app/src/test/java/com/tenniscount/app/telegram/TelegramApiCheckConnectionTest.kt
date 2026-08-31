package com.tenniscount.app.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramApiCheckConnectionTest {

    @Test
    fun `ok true maps to connected`() {
        assertEquals(
            TelegramCheckResult.Connected,
            TelegramApi.mapCheckResult(ok = true, errorCode = 0, description = "", isChatCheck = false),
        )
    }

    @Test
    fun `getMe unauthorized maps to auth error`() {
        assertEquals(
            TelegramCheckResult.AuthError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 401, description = "Unauthorized", isChatCheck = false),
        )
    }

    @Test
    fun `getMe lowercase unauthorized maps to auth error`() {
        assertEquals(
            TelegramCheckResult.AuthError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 0, description = "unauthorized: token invalid", isChatCheck = false),
        )
    }

    @Test
    fun `getMe unknown error maps to network error`() {
        assertEquals(
            TelegramCheckResult.NetworkError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 500, description = "Internal Server Error", isChatCheck = false),
        )
    }

    @Test
    fun `getChat chat not found maps to chat error`() {
        assertEquals(
            TelegramCheckResult.ChatError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 400, description = "Bad Request: chat not found", isChatCheck = true),
        )
    }

    @Test
    fun `getChat bot blocked maps to chat error`() {
        assertEquals(
            TelegramCheckResult.ChatError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 403, description = "Forbidden: bot was blocked by the user", isChatCheck = true),
        )
    }

    @Test
    fun `getChat unauthorized maps to auth error`() {
        assertEquals(
            TelegramCheckResult.AuthError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 401, description = "Unauthorized", isChatCheck = true),
        )
    }

    @Test
    fun `getChat unknown error maps to network error`() {
        assertEquals(
            TelegramCheckResult.NetworkError,
            TelegramApi.mapCheckResult(ok = false, errorCode = 500, description = "Internal Server Error", isChatCheck = true),
        )
    }

    @Test
    fun `channel administrator with post permission can publish`() {
        assertTrue(TelegramApi.canPublish("channel", "administrator", true, false))
    }

    @Test
    fun `channel administrator without post permission cannot publish`() {
        assertFalse(TelegramApi.canPublish("channel", "administrator", false, false))
    }

    @Test
    fun `channel creator can publish`() {
        assertTrue(TelegramApi.canPublish("channel", "creator", false, false))
    }

    @Test
    fun `group administrator can publish without channel-specific post permission`() {
        assertTrue(TelegramApi.canPublish("supergroup", "administrator", false, false))
    }

    @Test
    fun `group member can publish`() {
        assertTrue(TelegramApi.canPublish("group", "member", false, false))
    }

    @Test
    fun `group creator can publish`() {
        assertTrue(TelegramApi.canPublish("supergroup", "creator", false, false))
    }

    @Test
    fun `group restricted user without send permission cannot publish`() {
        assertFalse(TelegramApi.canPublish("supergroup", "restricted", false, false))
    }

    @Test
    fun `group restricted user with send permission can publish`() {
        assertTrue(TelegramApi.canPublish("supergroup", "restricted", false, true))
    }

    @Test
    fun `left or kicked member cannot publish in group`() {
        assertFalse(TelegramApi.canPublish("group", "left", false, false))
        assertFalse(TelegramApi.canPublish("supergroup", "kicked", false, false))
    }

    @Test
    fun `private chat active member can publish`() {
        assertTrue(TelegramApi.canPublish("private", "member", false, false))
    }

    @Test
    fun `private chat left or kicked user cannot publish`() {
        assertFalse(TelegramApi.canPublish("private", "left", false, false))
        assertFalse(TelegramApi.canPublish("private", "kicked", false, false))
    }
}
