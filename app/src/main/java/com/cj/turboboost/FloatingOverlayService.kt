package com.cj.turboboost

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.cj.turboboost.ui.Tac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A small read-only readout drawn over the game: FPS, CPU and RAM, once a second.
 *
 * FPS is [GameMonitorService.fpsFlow], RAM is `ActivityManager.MemoryInfo`, CPU is two
 * consecutive `/proc/stat` reads ([CpuSampler]). A figure that cannot be read shows "N/A".
 *
 * It is up only while a session runs: [GameMonitorService] starts it, and it stops when the
 * monitor does (session end, RESTORE) or when the app's process dies with it. It needs
 * "Display over other apps", which only the user can grant; without it the service stops itself.
 *
 * The one touch it handles is dragging itself, and where it was left is remembered. It sends
 * no input anywhere, and with the overlay off nothing here runs.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "TurboBooster"
        private const val CHANNEL_ID = "floating_overlay"

        /** Not 4202 (stabilizer) or 4203 (game monitor): removing ours must not take theirs down. */
        private const val NOTIFICATION_ID = 4204
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"

        /** One reading per second. */
        const val TICK_MS = 1_000L

        private val _showing = MutableStateFlow(false)

        /** Whether the overlay is up. The FPS poll runs only while this is true. */
        val showing: StateFlow<Boolean> = _showing.asStateFlow()

        /** Whether the user has granted "Display over other apps". */
        fun canDraw(context: Context): Boolean =
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        /** The system screen where the user grants "Display over other apps" to this app. */
        fun permissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

        /** May throw if the system refuses a foreground start; callers must not let that escape. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before any early exit: a startForegroundService() caller must see startForeground().
        goForeground()
        if (!canDraw(this) || !GameMonitorService.isRunning) {
            Log.i(TAG, "Overlay not shown: no 'Display over other apps' or no session running")
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null && !showOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The monitor starts it again for the next session; a killed overlay is not worth reviving.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _showing.value = false
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(): Boolean {
        val manager = getSystemService(WindowManager::class.java) ?: return false
        val store = Stores.options(this)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = store.getString(KEY_X)?.toIntOrNull() ?: 24
            y = store.getString(KEY_Y)?.toIntOrNull() ?: 96
        }

        val text = TextView(this).apply {
            this.text = OverlayText.format(null, CpuReading(readable = true), null)
            setTextColor(Tac.TextHi.toArgb())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = dp(10f)
            setPadding(pad, dp(6f), pad, dp(6f))
            background = GradientDrawable().apply {
                setColor(Tac.Surface.copy(alpha = 0.9f).toArgb())
                setStroke(dp(1f), Tac.Line.toArgb())
                cornerRadius = dp(12f).toFloat()
            }
            contentDescription = "Frame rate, CPU and memory readout. Drag to move."
            setOnTouchListener(DragListener())
        }

        return try {
            manager.addView(text, layout)
            windowManager = manager
            view = text
            params = layout
            clampToScreen(text, layout)
            _showing.value = true
            scope.launch { tickLoop(text) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Overlay could not be shown", e)
            false
        }
    }

    private fun removeOverlay() {
        val shown = view
        view = null
        if (shown != null) {
            try {
                windowManager?.removeView(shown)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay could not be removed", e)
            }
        }
        windowManager = null
        params = null
    }

    /** Every [TICK_MS]: RAM, CPU and the monitor's FPS, until the session ends. */
    private suspend fun tickLoop(text: TextView) {
        val activityManager = getSystemService(ActivityManager::class.java)
        val cpu = CpuSampler(RamCleaner.shell)
        while (true) {
            if (!GameMonitorService.isRunning) {
                stopSelf()
                return
            }
            val ram = runCatching {
                ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
            }.getOrNull()?.let { RamUsage.percent(it.totalMem, it.availMem) }
            val reading = withContext(Dispatchers.IO) { cpu.sample() }
            text.text = OverlayText.format(GameMonitorService.fpsFlow.value, reading, ram)
            delay(TICK_MS)
        }
    }

    /** Moves the window with the finger and remembers where it was left. Nothing else is handled. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val layout = params ?: return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x
                    startY = layout.y
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - downX).roundToInt()
                    layout.y = startY + (event.rawY - downY).roundToInt()
                    clampToScreen(v, layout)
                    try {
                        windowManager?.updateViewLayout(v, layout)
                    } catch (e: Exception) {
                        Log.w(TAG, "Overlay could not be moved", e)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val store = Stores.options(this@FloatingOverlayService)
                    store.putString(KEY_X, layout.x.toString())
                    store.putString(KEY_Y, layout.y.toString())
                }
            }
            return true
        }
    }

    /** Keeps the window on screen: a position saved in one orientation may lie outside another. */
    private fun clampToScreen(v: View, layout: WindowManager.LayoutParams) {
        val screen = resources.displayMetrics
        layout.x = layout.x.coerceIn(0, max(0, screen.widthPixels - v.width))
        layout.y = layout.y.coerceIn(0, max(0, screen.heightPixels - v.height))
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating overlay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the FPS, CPU and RAM overlay is on screen."
            }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Overlay on")
            .setContentText("Showing FPS, CPU and RAM over your game.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        // Same pattern as the monitor: a refusal must not take the service down with it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not go foreground; running as a started service", e)
        }
    }
}
