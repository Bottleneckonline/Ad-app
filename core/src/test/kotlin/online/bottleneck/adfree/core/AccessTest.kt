package online.bottleneck.adfree.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccessTest {
    private val day = 24L * 60 * 60 * 1000
    private val start = 1_800_000_000_000L

    @Test fun trialCountsDownThenExpires() {
        assertEquals(Access.Trial(7), Access.of(false, start, start))
        assertEquals(Access.Trial(7), Access.of(false, start, start + 1))
        assertEquals(Access.Trial(6), Access.of(false, start, start + day))
        assertEquals(Access.Trial(1), Access.of(false, start, start + 7 * day - 1))
        assertEquals(Access.Expired, Access.of(false, start, start + 7 * day))
        assertFalse(Access.of(false, start, start + 30 * day).canBlock)
    }

    @Test fun unlockedAlwaysBlocks() {
        assertEquals(Access.Unlocked, Access.of(true, start, start + 365 * day))
        assertTrue(Access.Unlocked.canBlock)
    }

    @Test fun clockMovedBackKeepsFullTrialNotNegative() {
        assertEquals(Access.Trial(7), Access.of(false, start, start - 3 * day))
    }
}

class LicenseCheckTest {
    private fun answer(ok: Boolean = true, status: String? = "active", store: Long? = 11, product: Long? = 22, error: String? = null) =
        LicenseAnswer(ok, status, store, product, error)

    @Test fun acceptsOwnActiveKey() {
        assertEquals(LicenseResult.Valid, LicenseCheck.evaluate(answer(), 11, 22))
    }

    @Test fun rejectsKeyFromAnotherProductOrStore() {
        assertIs<LicenseResult.Invalid>(LicenseCheck.evaluate(answer(product = 99), 11, 22))
        assertIs<LicenseResult.Invalid>(LicenseCheck.evaluate(answer(store = 99), 11, 22))
        assertIs<LicenseResult.Invalid>(LicenseCheck.evaluate(answer(store = null, product = null), 11, 22))
    }

    @Test fun rejectsRefusedDisabledOrExpired() {
        val refused = LicenseCheck.evaluate(answer(ok = false, error = "license_key not found."), 11, 22)
        assertEquals(LicenseResult.Invalid("license_key not found."), refused)
        assertIs<LicenseResult.Invalid>(LicenseCheck.evaluate(answer(status = "disabled"), 11, 22))
        assertIs<LicenseResult.Invalid>(LicenseCheck.evaluate(answer(status = "expired"), 11, 22))
    }
}
