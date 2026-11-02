package online.bottleneck.adfree

import android.content.Context
import online.bottleneck.adfree.core.DomainBlocklist

/** Loads the bundled blocklist once per process, and combines it with the user's allowlist. */
object Blocklists {
    /** The general list plus AdFree's own mobile ad network list (both in blocklists/). */
    private val FILES = listOf("blocklist.txt", "mobile-ads.txt")
    private const val PREFS = "adfree"
    private const val KEY_ALLOWLIST = "allowlist"

    @Volatile private var bundled: List<String>? = null

    fun load(context: Context): DomainBlocklist {
        val domains = bundled ?: synchronized(this) {
            bundled ?: FILES.flatMap { name ->
                context.assets.open(name).reader().use(DomainBlocklist::parseDomains)
            }.also { bundled = it }
        }
        return DomainBlocklist(domains, allowlist(context))
    }

    fun allowlistText(context: Context): String =
        prefs(context).getString(KEY_ALLOWLIST, "") ?: ""

    fun saveAllowlist(context: Context, text: String) {
        prefs(context).edit().putString(KEY_ALLOWLIST, text).apply()
    }

    private fun allowlist(context: Context): List<String> =
        allowlistText(context).lines().mapNotNull { DomainBlocklist.normalize(it) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
