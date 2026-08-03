package dev.cxclear.storage

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPreferencesTest {
    private val tempDir = Files.createTempDirectory("cxclear-prefs-test")

    @BeforeTest
    fun setUp() {
        AppDir.overrideForTest(tempDir)
    }

    @AfterTest
    fun tearDown() {
        AppDir.overrideForTest(null)
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `defaults when file missing`() {
        val prefs = AppPreferences.read()
        assertEquals(setOf("codex"), prefs.defaultTools)
        assertFalse(prefs.rememberLastScreen)
        assertEquals("scan", prefs.lastScreenId)
        assertEquals("manual", prefs.defaultChatsMode)
        assertTrue(prefs.autoCleanEnabled)
        assertTrue(prefs.autoCleanNotify)
    }

    @Test
    fun `round trip write and read`() {
        AppPreferences.write(
            AppPrefs(
                defaultTools = setOf("claude", "cursor"),
                rememberLastScreen = true,
                lastScreenId = "chats",
                defaultChatsMode = "auto",
                autoCleanEnabled = false,
                autoCleanNotify = false,
            )
        )
        val prefs = AppPreferences.read()
        assertEquals(setOf("claude", "cursor"), prefs.defaultTools)
        assertTrue(prefs.rememberLastScreen)
        assertEquals("chats", prefs.lastScreenId)
        assertEquals("auto", prefs.defaultChatsMode)
        assertFalse(prefs.autoCleanEnabled)
        assertFalse(prefs.autoCleanNotify)
    }

    @Test
    fun `unknown tools and screens fall back`() {
        val path = tempDir.resolve("preferences.txt")
        Files.write(path, listOf(
            "default_tools=foo,codex,bar",
            "last_screen=nowhere",
            "default_chats_mode=weird",
        ))
        val prefs = AppPreferences.read()
        assertEquals(setOf("codex"), prefs.defaultTools)
        assertEquals("scan", prefs.lastScreenId)
        assertEquals("manual", prefs.defaultChatsMode)
    }
}
