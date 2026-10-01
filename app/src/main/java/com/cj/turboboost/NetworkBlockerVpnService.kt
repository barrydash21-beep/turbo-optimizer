package com.cj.turboboost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle of the stabilizer tunnel, so a failed start is distinguishable from "not on". */
enum class VpnState { STOPPED, STARTING, RUNNING, FAILED }

class NetworkBlockerVpnService : VpnService() {

    companion object {
        private const val TAG = "TurboBooster"

        const val ACTION_STOP = "com.cj.turboboost.ACTION_STOP"

        private const val CHANNEL_ID = "stabilizer"
        private const val NOTIFICATION_ID = 4202

        private val _state = MutableStateFlow(VpnState.STOPPED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * Clears any stale [VpnState.FAILED] before a start attempt, so a caller waiting on
         * the next outcome cannot immediately read the previous one.
         */
        fun markStarting() = publish(VpnState.STARTING)

        /** Undoes [markStarting] when the service could not even be asked to start. */
        fun markStopped() = publish(VpnState.STOPPED)

        private fun publish(value: VpnState) {
            _state.value = value
            _isRunning.value = value == VpnState.RUNNING
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    /** Per-thread stop flag, so a new reader is never confused with the previous one. */
    private var readerAlive: AtomicBoolean? = null

    /** Keeps [cleanup] from overwriting a FAILED result with STOPPED on the way out. */
    private var failedToStart = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after a kill. Bringing the tunnel back
        // up on its own would resurrect a VPN the user believed was gone (M4).
        if (intent == null) {
            Log.d(TAG, "Null intent restart; not re-establishing")
            failedToStart = false
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            // An explicit stop is a clean STOPPED, even if the previous attempt FAILED.
            failedToStart = false
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        failedToStart = false
        cleanup()
        publish(VpnState.STARTING)

        val builder = Builder()
            .setSession("Ping Stabilizer")
            // RFC 6598 carrier-grade NAT space as a /32: a 10.0.0.0/24 tunnel collided with
            // home routers that hand out 10.0.0.x (L22).
            .addAddress("100.64.0.2", 32)
            // Without an IPv6 address and route the tunnel claims only IPv4, and every
            // IPv6 flow takes the underlying network — which on an IPv6-first carrier is
            // most of the traffic this feature exists to block (H6).
            .addAddress("fd00:1:2:3::2", 64)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")   // Cloudflare primary
            .addDnsServer("1.0.0.1")   // Cloudflare secondary

        // Read at each start, so games added since the last boost are exempt too.
        (AppPackages.vpnBypass(BoosterPrefs(this).games) + packageName).forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }

        val established = try {
            builder.establish()
        } catch (e: Exception) {
            Log.w(TAG, "establish() threw", e)
            null
        }

        if (established == null) {
            // Consent revoked, or another VPN holds the single tunnel slot. Report it so the
            // boost does not sit out the full timeout and then launch silently (M5).
            Log.w(TAG, "establish() returned null")
            failedToStart = true
            publish(VpnState.FAILED)
            stopSelf()
            return START_NOT_STICKY
        }

        vpnInterface = established
        // Go foreground only once the tunnel exists: the process is about to be backgrounded
        // for the game, and a plain started service holding a VPN is evictable under exactly the
        // memory pressure a game creates (M4).
        goForeground()
        publish(VpnState.RUNNING)
        startPacketDiscardThread(established)

        return START_NOT_STICKY
    }

    private fun goForeground() {
        ensureChannel()

        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, NetworkBlockerVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Ping Stabilizer active")
            .setContentText("Background app traffic is blocked. DNS is set to Cloudflare.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()

        // A rejected service type throws rather than returning an error. Degrading to a
        // plain started service is worse than a foreground one, but far better than taking
        // the tunnel down with an exception.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not go foreground; running as a started service", e)
        }
    }

    /** Creating a channel that already exists is a no-op update, so this is idempotent. */
    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ping Stabilizer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the traffic-blocking tunnel is up."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startPacketDiscardThread(pfd: ParcelFileDescriptor) {
        val alive = AtomicBoolean(true)
        readerAlive = alive
        vpnThread = Thread {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(16384)
            while (alive.get()) {
                // read < 0 is EOF, and an IOException is what closing the fd underneath us
                // produces. Either way the tunnel is gone and the loop must end -- checking
                // an interrupt flag never worked, because interrupt() cannot unblock a
                // native read (M11).
                val read = try {
                    inputStream.read(buffer)
                } catch (e: IOException) {
                    break
                }
                if (read < 0) break
            }
        }.apply {
            name = "VpnPacketDiscardThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Order matters: closing the descriptor is the only thing that unblocks the reader, so it
     * happens before the join, and the fields are cleared first so nothing can observe a
     * half-torn-down service (M11).
     */
    private fun cleanup() {
        val thread = vpnThread
        val fd = vpnInterface
        val alive = readerAlive

        vpnThread = null
        vpnInterface = null
        readerAlive = null

        alive?.set(false)
        runCatching { fd?.close() } // this is what makes the blocked read() return
        runCatching { thread?.join(500) }

        if (!failedToStart) publish(VpnState.STOPPED)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
