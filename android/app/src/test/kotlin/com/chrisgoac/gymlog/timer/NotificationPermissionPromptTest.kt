package com.chrisgoac.gymlog.timer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPromptTest {
    @Test fun `asks once on API 33 plus when not granted`() {
        assertTrue(NotificationPermissionPrompt.shouldPrompt(33, granted = false, askedBefore = false))
        assertTrue(NotificationPermissionPrompt.shouldPrompt(36, granted = false, askedBefore = false))
        assertFalse(NotificationPermissionPrompt.shouldPrompt(36, granted = false, askedBefore = true))
        assertFalse(NotificationPermissionPrompt.shouldPrompt(36, granted = true, askedBefore = false))
    }

    @Test fun `never asks below API 33`() {
        assertFalse(NotificationPermissionPrompt.shouldPrompt(32, granted = false, askedBefore = false))
        assertFalse(NotificationPermissionPrompt.shouldPrompt(26, granted = false, askedBefore = false))
    }
}
