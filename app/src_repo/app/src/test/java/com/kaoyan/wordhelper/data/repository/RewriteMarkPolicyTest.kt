package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteMarkPolicyTest {

    private val hint = "口令不对，请重新粘贴配对行"

    @Test
    fun `sent goes to sent message`() {
        assertEquals(
            RewriteMarkPolicy.SENT,
            RewriteMarkPolicy.result(sent = true, authFailed = false, hasToken = false, authHint = hint)
        )
    }

    @Test
    fun `sent wins even if auth failed`() {
        assertEquals(
            RewriteMarkPolicy.SENT,
            RewriteMarkPolicy.result(sent = true, authFailed = true, hasToken = true, authHint = hint)
        )
    }

    @Test
    fun `auth failure with token shows hint`() {
        assertEquals(
            hint,
            RewriteMarkPolicy.result(sent = false, authFailed = true, hasToken = true, authHint = hint)
        )
    }

    @Test
    fun `auth failure without token stays deferred`() {
        assertEquals(
            RewriteMarkPolicy.DEFERRED,
            RewriteMarkPolicy.result(sent = false, authFailed = true, hasToken = false, authHint = hint)
        )
    }

    @Test
    fun `offline stays deferred`() {
        assertEquals(
            RewriteMarkPolicy.DEFERRED,
            RewriteMarkPolicy.result(sent = false, authFailed = false, hasToken = true, authHint = hint)
        )
    }

    @Test
    fun `marking text is non empty`() {
        assertEquals("已标记，正在发送…", RewriteMarkPolicy.MARKING)
    }
}
