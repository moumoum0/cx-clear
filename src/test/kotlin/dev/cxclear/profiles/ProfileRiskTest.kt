package dev.cxclear.profiles

import dev.cxclear.model.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRiskTest {
    @Test
    fun `user session data is optional and never selected by default`() {
        val protectedIds = setOf(
            "codex.logs-db",
            "codex.sandbox-logs",
            "claude.cli-nodejs-cache",
            "claude.debug",
            "claude.shell-snapshots",
            "claude.session-env",
            "claude.paste-cache",
            "claude.image-cache",
            "claude.feedback-bundles",
            "claude.todos-legacy",
            "claude.logs-legacy",
            "claude.tasks",
            "claude.plans",
            "cursor.state-backup",
            "cursor.cached-profiles",
            "cursor.app-logs",
            "cursor.crashpad",
            "cursor.service-worker",
            "cursor.blob-storage",
            "cursor.partition-blob",
            "cursor.process-monitor",
            "cursor.home-logs",
            "cursor.agent-tools",
            "cursor.project-terminals",
        )
        val targets = ALL_PROFILES.flatMap { it.targets }.filter { it.id in protectedIds }

        assertFalse(targets.isEmpty())
        targets.forEach { target ->
            assertEquals(Risk.OPTIONAL, target.risk, "${target.id} must remain OPTIONAL")
            assertFalse(target.defaultSelected, "${target.id} must not be selected by default")
        }
    }

    @Test
    fun `every optional target is unselected by default`() {
        ALL_PROFILES.flatMap { it.targets }
            .filter { it.risk == Risk.OPTIONAL }
            .forEach { assertFalse(it.defaultSelected, "${it.id} must not be selected by default") }
    }

    @Test
    fun `target ids are globally unique`() {
        val ids = ALL_PROFILES.flatMap { it.targets }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `profile ids are unique`() {
        val ids = ALL_PROFILES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every profile declares running process prefixes`() {
        ALL_PROFILES.forEach {
            assertTrue(it.processNamePrefixes.isNotEmpty(), "${it.id} must block cleaning while running")
        }
    }

    @Test
    fun `active codex sqlite database is never a clean target`() {
        assertTrue(CodexProfile.targets.none { it.relPath == "sqlite" || it.id == "codex.sqlite-legacy" })
        val codexRoot = CodexProfile.baseDir() ?: return
        assertTrue(
            CodexProfile.protectedPaths().any { it.toAbsolutePath().normalize() == codexRoot.resolve("sqlite").toAbsolutePath().normalize() },
            "active Codex sqlite directory must be permanently protected",
        )
    }
}
