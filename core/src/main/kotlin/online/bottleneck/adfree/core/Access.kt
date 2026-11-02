package online.bottleneck.adfree.core

/**
 * Whether the user may turn blocking on: a free trial after first launch, then a one-time
 * unlock. The same rules are used by the Android app and (in Swift) by the iOS app.
 */
sealed interface Access {
    val canBlock: Boolean

    /** Paid (App Store purchase or a valid license key). */
    data object Unlocked : Access {
        override val canBlock = true
    }

    /** Inside the free trial; [daysLeft] is at least 1 while the trial runs. */
    data class Trial(val daysLeft: Int) : Access {
        override val canBlock = true
    }

    /** Trial over and not paid. */
    data object Expired : Access {
        override val canBlock = false
    }

    companion object {
        const val TRIAL_DAYS = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * @param firstLaunchMillis when the app was first opened (wall clock, UTC millis).
         * A clock that moved backwards counts as no time passed.
         */
        fun of(unlocked: Boolean, firstLaunchMillis: Long, nowMillis: Long, trialDays: Int = TRIAL_DAYS): Access {
            if (unlocked) return Unlocked
            val elapsed = (nowMillis - firstLaunchMillis).coerceAtLeast(0)
            val trialMs = trialDays * DAY_MS
            if (elapsed >= trialMs) return Expired
            val left = ((trialMs - elapsed + DAY_MS - 1) / DAY_MS).toInt()
            return Trial(left)
        }
    }
}

/**
 * The parts of a license server answer the app cares about. Kept free of any JSON library
 * so the decision is unit tested here; the Android app fills it from Lemon Squeezy's
 * License API (`/v1/licenses/activate` and `/v1/licenses/validate`).
 */
data class LicenseAnswer(
    /** `activated` for an activation, `valid` for a validation. */
    val ok: Boolean,
    /** `license_key.status`: inactive, active, expired or disabled. */
    val status: String?,
    val storeId: Long?,
    val productId: Long?,
    val error: String?,
)

sealed interface LicenseResult {
    data object Valid : LicenseResult
    /** The server said no: wrong key, refunded, disabled, or a key for another product. */
    data class Invalid(val reason: String) : LicenseResult
}

object LicenseCheck {
    /**
     * Accepts the answer only for *our* store and product, so a key bought for some other
     * Lemon Squeezy product does not unlock AdFree.
     */
    fun evaluate(answer: LicenseAnswer, expectedStoreId: Long, expectedProductId: Long): LicenseResult {
        if (!answer.ok) return LicenseResult.Invalid(answer.error ?: "This license key is not valid.")
        if (answer.storeId != expectedStoreId || answer.productId != expectedProductId) {
            return LicenseResult.Invalid("This license key is for a different product.")
        }
        if (answer.status == "disabled" || answer.status == "expired") {
            return LicenseResult.Invalid("This license key has been ${answer.status}.")
        }
        return LicenseResult.Valid
    }
}
