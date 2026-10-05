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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the guard is doing, for the confirm dialog. [deadline] is `elapsedRealtime`, null once kept. */
data class ResolutionGuardState(
    val target: ResolutionTarget? = null,
    val deadline: Long? = null
)

/**
 * Holds a render resolution override safe while it is active.
 *
 * - Runs the "Keep this resolution?" countdown, and restores when it runs out unanswered. A
 *   foreground service, so the countdown keeps running with the app backgrounded.
 * - Keeps an ongoing notification with a Restore default action for as long as the override is
 *   pending, which works with the app UI closed.
 * - A sticky restart means the process died with the override applied: it restores at once.
 */
class ResolutionGuardService : Service() {

    companion object {
        private const val TAG = "TurboBooster"

        private const val ACTION_ARM = "com.cj.turboboost.action.RESOLUTION_ARM"
        private const val ACTION_KEEP = "com.cj.turboboost.action.RESOLUTION_KEEP"
        private const val ACTION_RESTORE = "com.cj.turboboost.action.RESOLUTION_RESTORE"

        private const val CHANNEL_ID = "resolution_guard"
        /** Not 4202 (stabilizer) or 4203 (game monitor). */
        private const val NOTIFICATION_ID = 4204

        const val CONFIRM_MS = 15_000L

        /** Restore attempts after a sticky restart, 2 s apart, while Shizuku binds. */
        private const val RECOVERY_ATTEMPTS = 8

        private val _state = MutableStateFlow(ResolutionGuardState())
        val state: StateFlow<ResolutionGuardState> = _state.asStateFlow()

        @Volatile
        var isRunning = false
            private set

        /** Starts the countdown for an override that has just been applied and read back. */
        fun arm(context: Context) = send(context, ACTION_ARM, foreground = true)

        /** The user kept the resolution: stop the countdown, keep the notification. */
        fun keep(context: Context) = send(context, ACTION_KEEP, foreground = false)

        /**
         * Restores through [ResolutionLever] and, once verified, takes the guard down. Blocking:
         * call it on IO. This is the path the game monitor and Restore normal settings use.
         */
        fun restoreBlocking(context: Context, onlyIfPending: Boolean): ResolutionResult {
            val store = Stores.resolution(context)
            val result = if (onlyIfPending) {
                ResolutionLever.restoreIfPending(RamCleaner.shell, store)
            } else {
                ResolutionLever.restore(RamCleaner.shell, store)
            }
            if (!ResolutionLever.isPending(store)) {
                _state.value = ResolutionGuardState()
                if (isRunning) context.stopService(Intent(context, ResolutionGuardService::class.java))
            }
            return result
        }

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, ResolutionGuardService::class.java).setAction(action)
            if (foreground) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var countdown: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Stores.resolution(applicationContext)
        val target = ResolutionLever.intended(store)
        // Before any early exit: a startForegroundService() caller must see startForeground().
        goForeground(target, deadlineWallMs = null)
        isRunning = true

        when {
            // Sticky restart: this process did not apply the override, so the one that did died.
            intent == null -> {
                if (ResolutionLever.isPending(store) && !ResolutionLever.appliedInThisProcess) {
                    Log.w(TAG, "Resolution guard restarted with an override pending - restoring")
                    scope.launch { recoverAfterRestart() }
                    return START_STICKY
                }
                finish()
                return START_NOT_STICKY
            }
            // Before the target check: Restore default must work even if the stored target is unreadable.
            intent.action == ACTION_RESTORE && ResolutionLever.isPending(store) ->
                scope.launch { restoreAndNotify() }
            !ResolutionLever.isPending(store) || target == null -> {
                finish()
                return START_NOT_STICKY
            }
            intent.action == ACTION_ARM -> startCountdown(target)
            intent.action == ACTION_KEEP -> {
                countdown?.cancel()
                scope.launch { ResolutionLever.cancelWatchdog(RamCleaner.shell) }
                _state.value = ResolutionGuardState(target, deadline = null)
                goForeground(target, deadlineWallMs = null)
            }
            else -> goForeground(target, deadlineWallMs = null)
        }
        return START_STICKY
    }

    private fun startCountdown(target: ResolutionTarget) {
        countdown?.cancel()
        val deadline = SystemClock.elapsedRealtime() + CONFIRM_MS
        _state.value = ResolutionGuardState(target, deadline)
        goForeground(target, deadlineWallMs = System.currentTimeMillis() + CONFIRM_MS)
        countdown = scope.launch {
            delay(CONFIRM_MS)
            Log.i(TAG, "Resolution not confirmed in ${CONFIRM_MS / 1000} s - restoring")
            restoreAndNotify()
        }
    }

    private fun restoreAndNotify() {
        countdown?.cancel()
        // Only ever this app's own pending override: the countdown and the notification action
        // can both land (seen on-device after a HiOS thaw), and a second, unconditional restore
        // would reset an override the user had before this app touched anything.
        val result = restoreBlocking(applicationContext, onlyIfPending = true)
        if (!result.ok) {
            // Stay up so the Restore default action remains reachable.
            Log.w(TAG, "Resolution restore failed: ${result.detail}")
            val target = ResolutionLever.intended(Stores.resolution(applicationContext))
            _state.value = ResolutionGuardState(target, deadline = null)
            goForeground(target, deadlineWallMs = null, failure = result.detail)
        }
    }

    private suspend fun recoverAfterRestart() {
        repeat(RECOVERY_ATTEMPTS) {
            if (RamCleaner.shell.isAvailable()) {
                val result = restoreBlocking(applicationContext, onlyIfPending = true)
                if (result.ok) return
                goForeground(ResolutionLever.intended(Stores.resolution(applicationContext)), null, result.detail)
                return
            }
            delay(2000)
        }
        // Shizuku never came up: leave pending set, the next app start retries.
        Log.w(TAG, "Shizuku unavailable - leaving the resolution restore to the next app start")
        finish()
    }

    private fun finish() {
        isRunning = false
        countdown?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(target: ResolutionTarget?, deadlineWallMs: Long?, failure: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Render resolution", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a lower render resolution is applied, with a Restore default action."
            }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restore = PendingIntent.getService(
            this,
            1,
            Intent(this, ResolutionGuardService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val what = target?.toString() ?: "A lower resolution"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Restore default", restore)

        when {
            failure != null -> builder
                .setContentTitle("Resolution restore failed")
                .setContentText("$failure. Tap Restore default to retry.")
            deadlineWallMs != null -> {
                val keep = PendingIntent.getService(
                    this,
                    2,
                    Intent(this, ResolutionGuardService::class.java).setAction(ACTION_KEEP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder
                    .setContentTitle("Keep $what?")
                    .setContentText("Switches back automatically unless you keep it.")
                    .setWhen(deadlineWallMs)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .addAction(0, "Keep", keep)
            }
            else -> builder
                .setContentTitle("Render resolution: $what")
                .setContentText("Restored when the game closes, or tap Restore default.")
        }
        val notification: Notification = builder.build()

        // Same pattern as the other services: a refusal must not take the guard down with it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not go foreground; guarding as a started service", e)
        }
    }
}
