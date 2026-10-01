package com.cj.turboboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches for the selected game to exit, then puts the gaming tweaks back and stops itself.
 * While the game runs it also samples thermal status and FPS for the session summary.
 *
 * Polls `pidof <package>` through Shizuku every [POLL_MS]. A game needs a few seconds to
 * appear after launch, so nothing is reverted until it has been seen running once (or
 * [LAUNCH_GRACE_MS] has passed without it ever appearing).
 *
 * Sticky: the package and rate are persisted at start, so if the system kills and restarts
 * the process mid-game the monitor picks the session back up. Samples taken before such a
 * restart are lost; the summary covers what was measured after it. If the service does not
 * come back, the next app start reverts the tweaks from the persisted `tweaks_active` flag.
 */
class GameMonitorService : Service() {

    companion object {
        private const val TAG = "TurboBooster"

        const val EXTRA_PACKAGE = "package"
        const val EXTRA_RATE = "rate"
        const val ACTION_STOP = "com.cj.turboboost.action.MONITOR_STOP"

        /** In the tuner store, so a restarted service knows what it was watching. */
        private const val KEY_PACKAGE = "monitor_package"
        private const val KEY_RATE = "monitor_rate"

        private const val CHANNEL_ID = "game_monitor"
        /** Not 4202: that is the stabilizer's, and removing ours would take theirs down. */
        private const val NOTIFICATION_ID = 4203

        const val POLL_MS = 10_000L
        const val SAMPLE_MS = 30_000L
        const val LAUNCH_GRACE_MS = 90_000L

        /** Consecutive polls without Shizuku before giving up and leaving revert to app start. */
        const val MAX_UNAVAILABLE_POLLS = 6

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, gamePackage: String, refreshRate: Float) {
            val store = Stores.tuner(context)
            store.putString(KEY_PACKAGE, gamePackage)
            store.putString(KEY_RATE, RefreshRate.format(refreshRate))
            val intent = Intent(context, GameMonitorService::class.java)
                .putExtra(EXTRA_PACKAGE, gamePackage)
                .putExtra(EXTRA_RATE, refreshRate)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GameMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** The session being measured, held in memory only until the game exits. */
    @Volatile
    private var session: SessionAccumulator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }

        // A null intent is a sticky restart after the process was killed: recover from the store.
        val store = Stores.tuner(applicationContext)
        val gamePackage = intent?.getStringExtra(EXTRA_PACKAGE) ?: store.getString(KEY_PACKAGE)
        val rate = intent?.getFloatExtra(EXTRA_RATE, 0f)?.takeIf { it > 0f }
            ?: store.getString(KEY_RATE)?.toFloatOrNull()
        // Before any early exit: a startForegroundService() caller must see startForeground().
        goForeground(gamePackage)
        if (gamePackage == null || rate == null || !PerformanceTuner.isActive(store)) {
            finish()
            return START_NOT_STICKY
        }

        isRunning = true
        job?.cancel()
        // A new boost replaces a session still running: keep what that one measured.
        saveSession()
        job = scope.launch { watch(gamePackage, rate) }
        return START_STICKY
    }

    private suspend fun watch(gamePackage: String, rate: Float) {
        val store = Stores.tuner(applicationContext)
        val sampler = SessionSampler(RamCleaner.shell, gamePackage)
        var waitedMs = 0L
        var unavailablePolls = 0
        var lastSampleAt = 0L

        while (true) {
            val result = RamCleaner.shell.run("pidof $gamePackage")
            when {
                result.ok -> {
                    unavailablePolls = 0
                    val now = SystemClock.elapsedRealtime()
                    val current = session ?: SessionAccumulator(gamePackage, rate, System.currentTimeMillis(), now)
                        .also { session = it; lastSampleAt = now }
                    if (now - lastSampleAt >= SAMPLE_MS) {
                        lastSampleAt = now
                        val sample = sampler.sample(current, now)
                        Log.i(
                            TAG,
                            "Sample $gamePackage: thermal=${sample.thermal ?: "unavailable"}" +
                                (if (sampler.thermalOverridden) " (overridden)" else "") +
                                " frames=${sample.frames} layer=${sampler.layer ?: "not found"}" +
                                " peak=${sample.peak ?: "unreadable"} (chose $rate)"
                        )
                    }
                }
                // pidof exits 1 when there is no such process; anything else is a failed check.
                result.exitCode == 1 && !result.timedOut && !result.unavailable && !result.apiUnsupported -> {
                    unavailablePolls = 0
                    if (session != null || waitedMs >= LAUNCH_GRACE_MS) {
                        Log.d(TAG, "$gamePackage is gone - reverting gaming tweaks")
                        saveSession()
                        val report = PerformanceTuner.revertGamingTweaks(store)
                        if (report.failed.isEmpty() && !report.unavailable) {
                            finish()
                            return
                        }
                        // Keep tweaks_active set and keep trying; this poll's failure is not final.
                    }
                }
                else -> {
                    unavailablePolls++
                    if (unavailablePolls >= MAX_UNAVAILABLE_POLLS) {
                        Log.w(TAG, "Shizuku unavailable - leaving revert to the next app start")
                        saveSession()
                        finish()
                        return
                    }
                }
            }
            delay(POLL_MS)
            waitedMs += POLL_MS
        }
    }

    /** Writes the in-memory session once, if the game was ever seen running. */
    @Synchronized
    private fun saveSession() {
        val done = session ?: return
        session = null
        val record = done.finish(SystemClock.elapsedRealtime())
        Log.i(TAG, "Session saved: ${record.encode()}")
        SessionStore(Stores.sessions(applicationContext)).save(record)
    }

    private fun finish() {
        isRunning = false
        val store = Stores.tuner(applicationContext)
        store.putString(KEY_PACKAGE, null)
        store.putString(KEY_RATE, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        // Stopped mid-session (manual Restore): the session still ended, so keep it.
        saveSession()
        super.onDestroy()
    }

    private fun goForeground(gamePackage: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Game monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while gaming tweaks are active, until the game exits."
            }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val gameName = GameRegistry.labelFor(this, gamePackage) ?: "the game"
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Gaming tweaks active")
            .setContentText("Display settings are restored when $gameName closes.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        // Same pattern as the VPN service: a refusal must not take the monitor down with it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not go foreground; monitoring as a started service", e)
        }
    }
}
