package online.bottleneck.adfree

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQUEST_VPN = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val REFRESH_MS = 1000L
        const val START_TIMEOUT_MS = 10_000L
    }

    private lateinit var status: TextView
    private lateinit var toggle: Switch
    private lateinit var stats: TextView
    private lateinit var recent: TextView
    private lateinit var warning: TextView
    private val handler = Handler(Looper.getMainLooper())
    /** When the user switched blocking on and the tunnel is not up yet; 0 if not waiting. */
    private var startRequestedAt = 0L

    private val onToggle = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) requestStart() else stopBlocking()
    }

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        stats = findViewById(R.id.stats)
        recent = findViewById(R.id.recent)
        warning = findViewById(R.id.warning)
        toggle.setOnCheckedChangeListener(onToggle)

        val allowlist = findViewById<EditText>(R.id.allowlist)
        allowlist.setText(Blocklists.allowlistText(this))
        findViewById<Button>(R.id.save_allowlist).setOnClickListener {
            Blocklists.saveAllowlist(this, allowlist.text.toString())
            if (BlockerState.running) AdBlockVpnService.reload(this)
            Toast.makeText(this, R.string.allowlist_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun requestStart() {
        startRequestedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            AdBlockVpnService.start(this)
        }
    }

    private fun stopBlocking() {
        startRequestedAt = 0L
        AdBlockVpnService.stop(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            AdBlockVpnService.start(this)
        } else {
            startRequestedAt = 0L
            Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun render() {
        val running = BlockerState.running
        if (running || System.currentTimeMillis() - startRequestedAt > START_TIMEOUT_MS) startRequestedAt = 0L
        val starting = startRequestedAt != 0L
        status.setText(
            when {
                running -> R.string.status_on
                starting -> R.string.status_starting
                else -> R.string.status_off
            },
        )
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = running || starting
        toggle.setOnCheckedChangeListener(onToggle)

        stats.text = getString(
            R.string.stats,
            BlockerState.blocked.get(),
            BlockerState.allowed.get(),
            BlockerState.listSize,
        )
        val domains = BlockerState.recentBlocked()
        recent.text = if (domains.isEmpty()) getString(R.string.recent_empty) else domains.take(15).joinToString("\n")
        warning.visibility = if (strictPrivateDnsOn()) View.VISIBLE else View.GONE
    }

    /**
     * Android's "Private DNS" set to a specific provider sends lookups straight to that
     * provider over TLS, which bypasses this app. "Automatic" falls back to us.
     */
    private fun strictPrivateDnsOn(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getLinkProperties(it)?.privateDnsServerName != null }
    }
}
