package online.bottleneck.adfree

import android.content.Context
import android.os.Build
import online.bottleneck.adfree.core.Access
import online.bottleneck.adfree.core.LicenseAnswer
import online.bottleneck.adfree.core.LicenseCheck
import online.bottleneck.adfree.core.LicenseResult
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Trial and paid unlock for the direct-download Android app.
 *
 * Sales run through Lemon Squeezy, which acts as merchant of record (it charges the card,
 * handles VAT/sales tax and emails the buyer a license key). The app checks the key with
 * Lemon Squeezy's public License API, which needs no secret in the app. Once a key is
 * activated the app works offline; it re-checks about once a week and only locks again
 * when the server clearly says the key is no longer valid (for example after a refund),
 * never because the phone is offline.
 */
object License {
    private const val PREFS = "license"
    private const val KEY_FIRST_LAUNCH = "first_launch"
    private const val KEY_LICENSE = "key"
    private const val KEY_INSTANCE = "instance"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
    private const val API = "https://api.lemonsqueezy.com/v1/licenses/"
    private const val TIMEOUT_MS = 15_000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts the trial clock on first call. */
    fun access(context: Context): Access {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        var first = p.getLong(KEY_FIRST_LAUNCH, 0L)
        if (first == 0L) {
            first = now
            p.edit().putLong(KEY_FIRST_LAUNCH, first).apply()
        }
        return Access.of(unlocked = p.getString(KEY_INSTANCE, null) != null, firstLaunchMillis = first, nowMillis = now)
    }

    fun licenseKey(context: Context): String? = prefs(context).getString(KEY_LICENSE, null)

    /** True when the store is set up in this build (see app/build.gradle.kts). */
    val salesConfigured: Boolean
        get() = BuildConfig.LS_STORE_ID != 0L && BuildConfig.LS_PRODUCT_ID != 0L

    /** Activates [key] on this phone. Blocking network call: run it off the main thread. */
    fun activate(context: Context, key: String): LicenseResult {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return LicenseResult.Invalid("Enter the license key from your purchase email.")
        if (!salesConfigured) return LicenseResult.Invalid("Purchases are not set up in this build.")
        val json = try {
            post("activate", mapOf("license_key" to trimmed, "instance_name" to "${Build.MANUFACTURER} ${Build.MODEL}"))
        } catch (e: IOException) {
            return LicenseResult.Invalid("Could not reach the license server. Check your connection and try again.")
        }
        val result = LicenseCheck.evaluate(parse(json, "activated"), BuildConfig.LS_STORE_ID, BuildConfig.LS_PRODUCT_ID)
        if (result == LicenseResult.Valid) {
            prefs(context).edit()
                .putString(KEY_LICENSE, trimmed)
                .putString(KEY_INSTANCE, json.optJSONObject("instance")?.optString("id").orEmpty())
                .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                .apply()
        }
        return result
    }

    /**
     * Re-validates an activated key if the last check is over a week old. Network errors
     * keep the unlock. Blocking call: run it off the main thread.
     */
    fun recheckIfDue(context: Context) {
        val p = prefs(context)
        val key = p.getString(KEY_LICENSE, null) ?: return
        val instance = p.getString(KEY_INSTANCE, null) ?: return
        if (System.currentTimeMillis() - p.getLong(KEY_CHECKED_AT, 0L) < RECHECK_MS) return
        val json = try {
            val params = mutableMapOf("license_key" to key)
            if (instance.isNotEmpty()) params["instance_id"] = instance
            post("validate", params)
        } catch (e: IOException) {
            return
        }
        val result = LicenseCheck.evaluate(parse(json, "valid"), BuildConfig.LS_STORE_ID, BuildConfig.LS_PRODUCT_ID)
        val edit = p.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis())
        if (result is LicenseResult.Invalid) edit.remove(KEY_INSTANCE)
        edit.apply()
    }

    private fun parse(json: JSONObject, okField: String): LicenseAnswer {
        val meta = json.optJSONObject("meta")
        return LicenseAnswer(
            ok = json.optBoolean(okField, false),
            status = json.optJSONObject("license_key")?.optString("status"),
            storeId = meta?.optLong("store_id")?.takeIf { it != 0L },
            productId = meta?.optLong("product_id")?.takeIf { it != 0L },
            error = json.optString("error").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    /** Lemon Squeezy answers 4xx with a JSON body that explains the problem, so read it either way. */
    private fun post(action: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL(API + action).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code >= 500) throw IOException("License server error $code")
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: throw IOException("Empty answer")
            return try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("Unreadable answer", e)
            }
        } finally {
            conn.disconnect()
        }
    }
}
