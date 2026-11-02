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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds the session's foreground notification and starts its [SessionWatcher], which sees the
 * game exit, samples it, and ends the session through [SessionProvider] even while HiOS has
 * this app frozen. The session always ends through [SessionEnd.finish], whichever path gets
 * there first; this service stops once it is ended. It also records which restricted apps are
 * alive [BackgroundRestrictor.ALIVE_CHECK_DELAY_MS] after it starts.
 *
 * While the watcher runs this service only checks every [POLL_MS] that it still does. When the
 * watcher could not start, or is gone, it does what it always did: polls `pidof <package>`
 * through Shizuku every [POLL_MS] and samples thermal status and FPS every [SAMPLE_MS]. A game
 * needs a few seconds to appear after launch, so nothing is reverted until it has been seen
 * running once (or [LAUNCH_GRACE_MS] has passed without it ever appearing).
 *
 * Sticky: the package and rate are persisted at start, so if the system kills and restarts
 * the process mid-game the monitor picks the session back up. The watcher's samples survive
 * that; in-process samples taken before it are lost. If the service does not come back, the
 * watcher still ends the session, and the next app start finishes one left unfinished.
 */
class GameMonitorService : Service() {

    companion object {
        private const val TAG = "TurboBooster"

        const val EXTRA_PACKAGE = "package"
        const val EXTRA_RATE = "rate"

        /** The session's kind: a [BoostProfile] name, or [SessionRecord.BASELINE]. */
        const val EXTRA_KIND = "kind"

        /** [Contamination] name of the game's state checked before the boost changed anything. */
        const val EXTRA_START_CHECK = "start_check"
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

        /**
         * How late the early live-process check may run and still count. HiOS freezes a
         * backgrounded app even with this service in the foreground (seen on HiOS 16.3), and
         * the check then runs only when it thaws, minutes later.
         */
        const val ALIVE_CHECK_TOLERANCE_MS = 10_000L

        private val _running = MutableStateFlow(false)

        /** Observable form of [isRunning], so an open screen can tell when a session ends. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        val isRunning: Boolean get() = _running.value

        /** How often the overlay's FPS is read while frames are coming, and while none are. */
        const val OVERLAY_MS = 1_000L
        const val OVERLAY_IDLE_MS = 3_000L

        private val _fps = MutableStateFlow<Int?>(null)

        /**
         * The game's frame rate, rounded, from the overlay's poll; null while no FPS can be
         * called (menus, loading) and whenever the overlay is not up. Nothing reads SurfaceFlinger
         * for it unless [FloatingOverlayService.showing]. [FloatingOverlayService] collects it.
         */
        val fpsFlow: StateFlow<Int?> = _fps.asStateFlow()

        /**
         * Starts monitoring a session of [gamePackage]. [kind] is the profile's name or
         * [SessionRecord.BASELINE]; a baseline holds nothing to restore and is monitored anyway.
         */
        fun start(context: Context, gamePackage: String, refreshRate: Float, kind: String, startCheck: Contamination?) {
            val store = Stores.tuner(context)
            store.putString(KEY_PACKAGE, gamePackage)
            store.putString(KEY_RATE, RefreshRate.format(refreshRate))
            val intent = Intent(context, GameMonitorService::class.java)
                .putExtra(EXTRA_PACKAGE, gamePackage)
                .putExtra(EXTRA_RATE, refreshRate)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_START_CHECK, startCheck?.name)
            context.startForegroundService(intent)
        }

        /**
         * Stops the monitor for a session that has ended, and forgets what it was watching, as
         * its own [finish] does: a stop from outside (the watcher's end, RESTORE) left them set.
         */
        fun stop(context: Context) {
            val store = Stores.tuner(context)
            store.putString(KEY_PACKAGE, null)
            store.putString(KEY_RATE, null)
            context.stopService(Intent(context, GameMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var aliveJob: Job? = null
    private var fpsJob: Job? = null

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
        val restrictions = Stores.restrictions(applicationContext)
        val sessionState = SessionEnd.state(Stores.session(applicationContext))
        val open = sessionState == SessionEnd.STATE_ACTIVE || sessionState == SessionEnd.STATE_ENDING
        // A sticky restart picks the session on disk back up; a new boost starts one.
        val fresh = intent?.getStringExtra(EXTRA_PACKAGE) != null
        val kind = intent?.getStringExtra(EXTRA_KIND)
        // A baseline changes nothing, so nothing is held, and it is still a session to measure.
        val baseline = fresh && kind == SessionRecord.BASELINE
        val active = PerformanceTuner.isActive(store) || BackgroundRestrictor.isActive(restrictions) ||
            ProfileGameMode.held(Stores.gameResolution(applicationContext)) ||
            SystemSnapshot.exists(Stores.snapshot(applicationContext)) || open || baseline
        if (gamePackage == null || rate == null || !active) {
            finish()
            return START_NOT_STICKY
        }

        _running.value = true
        job?.cancel()
        aliveJob?.cancel()
        val startCheck = Contamination.fromName(intent?.getStringExtra(EXTRA_START_CHECK))
        job = scope.launch {
            if (fresh) beginSession(gamePackage, rate, kind, startCheck)
            watch(gamePackage, rate)
        }
        armFpsPoll(gamePackage)
        startOverlayIfWanted()
        if (fresh && BackgroundRestrictor.isActive(restrictions)) {
            aliveJob = scope.launch {
                val due = SystemClock.elapsedRealtime() + BackgroundRestrictor.ALIVE_CHECK_DELAY_MS
                delay(BackgroundRestrictor.ALIVE_CHECK_DELAY_MS)
                val lateMs = SystemClock.elapsedRealtime() - due
                if (lateMs > ALIVE_CHECK_TOLERANCE_MS) {
                    // Not the value at that time, so it is left unchecked rather than mislabelled.
                    Log.w(TAG, "Restricted apps check ran ${lateMs / 1000} s late (process frozen?) - not recorded")
                    return@launch
                }
                val alive = BackgroundRestrictor.alive(RamCleaner.shell, restrictions)
                SessionEnd.noteAliveEarly(Stores.session(applicationContext), alive)
                Log.i(TAG, "Restricted apps alive after ${BackgroundRestrictor.ALIVE_CHECK_DELAY_MS / 1000} s: ${alive ?: "unreadable"}")
            }
        }
        return START_STICKY
    }

    /**
     * Opens the session on disk under a fresh token and starts its watcher. A session still
     * open is closed first ([SessionEnd.begin]), keeping what it measured.
     */
    private fun beginSession(gamePackage: String, rate: Float, kind: String?, startCheck: Contamination?) {
        val ctx = SessionContext.of(applicationContext)
        // Read once: a preset change reaches the game only at its next launch.
        val resolution = Stores.gameResolution(applicationContext)
        val token = SessionEnd.newToken()
        SessionEnd.begin(
            ctx, gamePackage, rate,
            GameResolution.sessionValue(resolution, gamePackage), GameResolution.sessionFpsCap(resolution, gamePackage),
            token, kind, startCheck
        )
        if (ctx.shell.isAvailable()) SessionEnd.observeStart(ctx, gamePackage)
        val started = ctx.shell.isAvailable() && runCatching {
            SessionWatcher.start(ctx.shell, gamePackage, token, SessionProvider.authority(packageName))
        }.getOrDefault(false)
        SessionEnd.noteWatcher(ctx.session, started)
        Log.i(TAG, if (started) "Session watcher started for $gamePackage" else "Session watcher did not start - monitoring in-process")
    }

    private suspend fun watch(gamePackage: String, rate: Float) {
        val ctx = SessionContext.of(applicationContext)
        val shell = ctx.shell
        val sampler = SessionSampler(shell, gamePackage)
        var watcherRunning = SessionEnd.watcherHeld(ctx.session)
        var waitedMs = 0L
        var unavailablePolls = 0
        var lastSampleAt = 0L

        while (true) {
            if (SessionEnd.isEnded(ctx.session)) {
                Log.d(TAG, "Session ended - monitor stopping")
                finish()
                return
            }
            if (watcherRunning) {
                val alive = SessionWatcher.isAlive(shell)
                // Shizuku away is not the watcher gone: it runs without Shizuku's server.
                if (alive.ok || alive.unavailable || alive.timedOut || alive.apiUnsupported) {
                    delay(POLL_MS)
                    continue
                }
                Log.w(TAG, "Session watcher gone - monitoring in-process")
                watcherRunning = false
            }

            val result = shell.run("pidof $gamePackage")
            when {
                result.ok && SessionEnd.live == null && !Diagnostics.inFront(shell.capture("dumpsys activity activities"), gamePackage) -> {
                    // A process in the background is not a session: it starts once the game is in front.
                    unavailablePolls = 0
                    if (waitedMs >= LAUNCH_GRACE_MS) {
                        val outcome = SessionEnd.finish(ctx, EndReason.NEVER_STARTED)
                        if (outcome.ok) {
                            finish()
                            return
                        }
                    }
                }
                result.ok -> {
                    unavailablePolls = 0
                    val now = SystemClock.elapsedRealtime()
                    val current = SessionEnd.live ?: SessionAccumulator(gamePackage, rate, System.currentTimeMillis(), now)
                        .also {
                            SessionEnd.live = it
                            lastSampleAt = now
                            val resolution = Stores.gameResolution(applicationContext)
                            it.renderResolution = GameResolution.sessionValue(resolution, gamePackage)
                            it.fpsCap = GameResolution.sessionFpsCap(resolution, gamePackage)
                            it.batteryTempStart = Diagnostics.parseBatteryTemp(shell.capture("dumpsys battery"))
                        }
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
                    val seen = SessionEnd.live != null
                    if (seen || waitedMs >= LAUNCH_GRACE_MS) {
                        Log.d(TAG, "$gamePackage is gone - ending the session")
                        val outcome = SessionEnd.finish(ctx, if (seen) EndReason.EXITED else EndReason.NEVER_STARTED)
                        if (outcome.ok) {
                            finish()
                            return
                        }
                        // The session stays "ending" and this keeps trying; this poll's failure is not final.
                    }
                }
                else -> {
                    unavailablePolls++
                    if (unavailablePolls >= MAX_UNAVAILABLE_POLLS) {
                        Log.w(TAG, "Shizuku unavailable - leaving the restore to the next app start")
                        // Saves the record and leaves the session "ending" for the next app start.
                        SessionEnd.finish(ctx, EndReason.SHIZUKU_LOST)
                        finish()
                        return
                    }
                }
            }
            delay(POLL_MS)
            waitedMs += POLL_MS
        }
    }

    /**
     * Arms the FPS poll that feeds the overlay. It idles until the overlay is showing and stops
     * again when it is not, so with the overlay off nothing is read. It uses a sampler of its
     * own and never touches the session's numbers.
     */
    private fun armFpsPoll(gamePackage: String) {
        fpsJob?.cancel()
        fpsJob = scope.launch { runFpsPoll(gamePackage) }
    }

    /** Brings the floating overlay up for this session if the user turned it on and granted "Display over other apps". */
    private fun startOverlayIfWanted() {
        if (!BoosterPrefs(applicationContext).overlayEnabled) return
        if (!FloatingOverlayService.canDraw(applicationContext)) {
            Log.i(TAG, "Overlay is on but 'Display over other apps' is not granted")
            return
        }
        // A refusal (background foreground-service start) must not take the monitor down.
        try {
            FloatingOverlayService.start(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay service could not start", e)
        }
    }

    private suspend fun runFpsPoll(gamePackage: String) {
        val sampler = SessionSampler(RamCleaner.shell, gamePackage)
        while (true) {
            // Suspends without reading anything while the overlay is not up.
            FloatingOverlayService.showing.first { it }
            val tally = OverlayFps()
            try {
                while (FloatingOverlayService.showing.value) {
                    // One SurfaceFlinger read per tick: every extra shell process is load on the
                    // phone the numbers are being taken from.
                    val fps = tally.consume(sampler.framesAfter(tally.lastPresentNs))
                    _fps.value = fps
                    delay(if (fps != null) OVERLAY_MS else OVERLAY_IDLE_MS)
                }
            } finally {
                _fps.value = null
            }
        }
    }

    private fun stopOverlay() {
        fpsJob?.cancel()
        fpsJob = null
        _fps.value = null
        FloatingOverlayService.stop(applicationContext)
    }

    private fun finish() {
        _running.value = false
        aliveJob?.cancel()
        stopOverlay()
        val store = Stores.tuner(applicationContext)
        store.putString(KEY_PACKAGE, null)
        store.putString(KEY_RATE, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        stopOverlay()
        scope.cancel()
        // Nothing to save here: every path that stops a session ends it through SessionEnd first,
        // and one stopped any other way is still on disk for the watcher or the next app start.
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
            .setContentText("Your settings are restored when $gameName closes.")
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
