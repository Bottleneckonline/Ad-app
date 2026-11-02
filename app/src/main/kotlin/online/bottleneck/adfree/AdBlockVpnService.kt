package online.bottleneck.adfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import online.bottleneck.adfree.core.DnsFilter
import online.bottleneck.adfree.core.FilterDecision
import online.bottleneck.adfree.core.PublicResolvers
import online.bottleneck.adfree.core.ReplyDecision
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A local, DNS-only VPN. Android sends every app's DNS lookups to a virtual DNS
 * server address that only exists inside this tunnel. Lookups for ad domains are
 * answered with 0.0.0.0 right here; everything else is passed on to a public
 * resolver. Only DNS goes through the tunnel; all other traffic uses the normal
 * network directly, so the app does not slow down browsing or games.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdFree"
        const val ACTION_START = "online.bottleneck.adfree.START"
        const val ACTION_STOP = "online.bottleneck.adfree.STOP"
        const val ACTION_RELOAD = "online.bottleneck.adfree.RELOAD"

        private const val TUN_ADDRESS_V4 = "10.111.222.1"
        private const val DNS_ADDRESS_V4 = "10.111.222.2"
        private const val TUN_ADDRESS_V6 = "fd00:111:222::1"
        private const val DNS_ADDRESS_V6 = "fd00:111:222::2"

        /** Resolvers used for allowed lookups, tried in order. */
        private val UPSTREAM = listOf("1.1.1.1", "9.9.9.9", "2606:4700:4700::1111")
        private const val UPSTREAM_TIMEOUT_MS = 4000

        private const val CHANNEL_ID = "adfree"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun reload(context: Context) = send(context, ACTION_RELOAD)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(action)
            if (action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var filter: DnsFilter? = null
    private var upstreamPool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                filter?.let { f -> Thread { f.blocklist = Blocklists.load(this) }.start() }
                return START_STICKY
            }
        }
        // ACTION_START, or a null/VpnService intent when the system starts us for Always-on VPN.
        showForegroundNotification()
        if (!License.access(this).canBlock) {
            // Trial over and not unlocked (also covers Always-on VPN starting us after a reboot).
            Log.i(TAG, "Trial ended; not starting the filter")
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user turned us off in system settings.
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    @Synchronized
    private fun startTunnel() {
        upstreamPool = Executors.newFixedThreadPool(8)
        worker = Thread({
            try {
                val blocklist = Blocklists.load(this)
                val dnsFilter = DnsFilter(blocklist)
                filter = dnsFilter
                BlockerState.listSize = blocklist.size

                val iface = buildTunnel() ?: run {
                    Log.w(TAG, "VPN permission missing; open the app to grant it")
                    stopSelf()
                    return@Thread
                }
                synchronized(this) {
                    if (worker !== Thread.currentThread()) {
                        iface.close() // stopped while we were starting
                        return@Thread
                    }
                    tun = iface
                }
                BlockerState.running = true
                runLoop(iface, dnsFilter)
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel stopped", e)
            } finally {
                synchronized(this) {
                    if (worker === Thread.currentThread()) {
                        // The loop ended on its own; let the next start command rebuild the tunnel.
                        worker = null
                        BlockerState.running = false
                    }
                }
            }
        }, "adfree-tun").also { it.start() }
    }

    private fun buildTunnel(): ParcelFileDescriptor? {
        val configure = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setConfigureIntent(configure)
            .setMtu(1500)
            .setBlocking(true)
            .addAddress(TUN_ADDRESS_V4, 32)
            .addDnsServer(DNS_ADDRESS_V4)
            .addRoute(DNS_ADDRESS_V4, 32)
            .addAddress(TUN_ADDRESS_V6, 128)
            .addDnsServer(DNS_ADDRESS_V6)
            .addRoute(DNS_ADDRESS_V6, 128)
            // Only DNS server addresses are routed into the tunnel. Keep all other
            // IPv4 and IPv6 traffic flowing over the normal network.
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        // Also catch apps and ad SDKs that skip the phone's DNS setting and ask a public
        // resolver such as 8.8.8.8 directly. Our own upstream sockets are protect()ed,
        // so they still reach these servers over the normal network.
        PublicResolvers.IPV4.forEach { builder.addRoute(it, 32) }
        PublicResolvers.IPV6.forEach { builder.addRoute(it, 128) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish()
    }

    private fun runLoop(iface: ParcelFileDescriptor, dnsFilter: DnsFilter) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                break // the tunnel was closed
            }
            if (length < 0) break
            if (length == 0) continue
            when (val decision = dnsFilter.decide(buffer, length)) {
                is FilterDecision.Blocked -> {
                    BlockerState.recordBlocked(decision.domain)
                    write(output, decision.response)
                }
                is FilterDecision.Refused -> write(output, decision.response)
                is FilterDecision.Forward -> {
                    upstreamPool?.execute { forward(decision, dnsFilter, output) }
                }
                FilterDecision.Drop -> Unit
            }
        }
    }

    private fun forward(decision: FilterDecision.Forward, dnsFilter: DnsFilter, output: FileOutputStream) {
        val query = decision.query.payload
        for (server in UPSTREAM) {
            try {
                DatagramSocket().use { socket ->
                    // Keep our own upstream lookups out of the tunnel.
                    protect(socket)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                    val reply = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(reply)
                    val answer = dnsFilter.upstreamReply(decision.query, reply.data, reply.length)
                    when (answer) {
                        is ReplyDecision.Blocked -> BlockerState.recordBlocked(answer.domain)
                        is ReplyDecision.Pass -> BlockerState.recordAllowed()
                    }
                    write(output, answer.response)
                }
                return
            } catch (e: IOException) {
                Log.d(TAG, "Upstream $server failed for ${decision.domain}: ${e.message}")
            }
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(output) { output.write(packet) }
        } catch (e: IOException) {
            Log.d(TAG, "Could not write to tunnel: ${e.message}")
        }
    }

    @Synchronized
    private fun shutdown() {
        worker?.interrupt()
        worker = null
        try {
            tun?.close()
        } catch (_: IOException) {
        }
        tun = null
        upstreamPool?.shutdownNow()
        upstreamPool = null
        BlockerState.running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.status_on))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
