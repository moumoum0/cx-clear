package dev.cxclear.profiles

import dev.cxclear.model.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProfileRiskTest {
    @Test
    fun `user session data is optional and never selected by default`() {
        val protectedIds = setOf(
            "claude.tasks",
            "claude.plans",
            "cursor.state-backup",
        )
        val targets = ALL_PROFILES.flatMap { it.targets }.filter { it.id in protectedIds }

        assertFalse(targets.isEmpty())
        targets.forEach { target ->
            assertEquals(Risk.OPTIONAL, target.risk, "${target.id} must remain OPTIONAL")
            assertFalse(target.defaultSelected, "${target.id} must not be selected by default")
        }
    }
}
