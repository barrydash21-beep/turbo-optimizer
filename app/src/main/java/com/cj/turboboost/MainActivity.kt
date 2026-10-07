package com.cj.turboboost

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.cj.turboboost.ui.BoosterScreen
import com.cj.turboboost.ui.TurboTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * The Activity keeps only what genuinely needs an Activity: the result launchers and starting
 * the selected game. Boost state and the boost sequence live in [BoostViewModel] so a
 * configuration change cannot cancel them (H3).
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "TurboBooster"
        const val REQUEST_SHIZUKU = 0
    }

    private val viewModel: BoostViewModel by viewModels()

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        viewModel.refreshShizuku()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        viewModel.refreshShizuku()
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        Log.d(TAG, "Shizuku permission result: $grantResult")
        // This callback arrives from a binder thread while the Shizuku manager's own dialog
        // is still on screen. Resuming the boost from here could start an activity while we
        // are backgrounded, which Android 10+ silently drops (M7).
        lifecycleScope.launch {
            lifecycle.withResumed {
                viewModel.onShizukuPermissionResult(
                    grantResult == PackageManager.PERMISSION_GRANTED
                )
            }
        }
    }

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onVpnConsentResult(result.resultCode == RESULT_OK)
        }

    /**
     * Asked for before a boost, since every boost now starts the game monitor and its ongoing
     * notification. A denial is not fatal: the monitor still reverts on game exit.
     */
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !notificationDenialShown) {
                notificationDenialShown = true
                toast("Notifications off - the game monitor runs without showing its notification")
            }
            viewModel.onBoostRequested()
        }

    /** Once per launch: after two denials Android answers instantly, which would toast every boost. */
    private var notificationDenialShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        Log.d(TAG, "MainActivity onCreate")

        // Phase 2: Safety Checks - Add listeners to safely monitor connection state
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        setContent {
            TurboTheme {
                val vpnState by NetworkBlockerVpnService.state.collectAsState()

                // Held as ViewModel state, so a boost that finishes while the Activity is
                // being recreated still launches the game (H3).
                val launchTarget = viewModel.pendingLaunch
                LaunchedEffect(launchTarget) {
                    if (launchTarget != null) {
                        lifecycle.withResumed { launchGame(launchTarget) }
                        viewModel.onLaunchHandled()
                    }
                }

                BoosterScreen(
                    shizuku = viewModel.shizukuState,
                    vpnState = vpnState,
                    step = viewModel.step,
                    onBoost = ::requestNotificationsThenBoost,
                    profile = viewModel.profile,
                    vpnEnabled = viewModel.vpnEnabled,
                    games = viewModel.games,
                    pickerOpen = viewModel.pickerOpen,
                    pickerApps = viewModel.pickerApps,
                    selectedGame = viewModel.selectedGame,
                    rates = viewModel.rates,
                    summary = viewModel.summary,
                    summarySuggestion = viewModel.summarySuggestion,
                    onStopVpn = { viewModel.stopVpn() },
                    onRestore = { viewModel.restoreEverything() },
                    onProfileChange = { viewModel.selectProfile(it) },
                    onVpnEnabledChange = { viewModel.setStabilizerEnabled(it) },
                    onSelectGame = { viewModel.selectGame(it) },
                    onRateChange = { pkg, rate -> viewModel.setRate(pkg, rate) },
                    onOpenPicker = { viewModel.openPicker() },
                    onClosePicker = { viewModel.closePicker() },
                    onAddGame = { viewModel.addGame(it) },
                    onRemoveGame = { viewModel.removeGame(it) },
                    onDismissSummary = { viewModel.dismissSummary() },
                    onApplySuggestion = { viewModel.applySuggestion() },
                    resolutionSupported = viewModel.resolutionSupported,
                    gameResolution = viewModel.gameResolution,
                    onPickResolution = { pkg, level -> viewModel.pickResolution(pkg, level) },
                    onRestoreResolutionAnyway = { viewModel.restoreResolutionAnyway(it) },
                    onRestartGame = { viewModel.restartGame(it) },
                    displayOverride = viewModel.displayOverride,
                    onResetDisplayOverride = { viewModel.resetDisplayOverride() },
                    onKeepDisplayOverride = { viewModel.keepDisplayOverride() }
                )
            }
        }

        // Collected at RESUMED so nothing that starts an activity fires while backgrounded
        // (M7). The events channel is buffered, so a recreation delays them, never drops them.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.events.collect(::handleEvent)
            }
        }

        viewModel.startShizukuDetection()
        viewModel.revertStaleTweaks()
        viewModel.checkDisplayOverride()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshShizuku()
        // A game may have been installed or removed, or a session may have ended, while away.
        viewModel.refreshGames()
        viewModel.checkSummary()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    private fun handleEvent(event: BoostEvent) {
        when (event) {
            is BoostEvent.ShowToast -> toast(event.message)
            BoostEvent.RequestShizukuPermission -> requestShizukuPermission()
            BoostEvent.RequestVpnConsent -> requestVpnConsent()
        }
    }

    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(REQUEST_SHIZUKU)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
            viewModel.onShizukuRequestFailed()
        }
    }

    private fun requestVpnConsent() {
        val vpnIntent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            Log.w(TAG, "VpnService.prepare failed", e)
            viewModel.onVpnConsentUnavailable("This device does not support VPN apps - boosting without the stabilizer")
            return
        }

        if (vpnIntent == null) {
            // Consent already granted.
            viewModel.onVpnConsentUnavailable(null)
            return
        }

        try {
            vpnLauncher.launch(vpnIntent)
        } catch (e: ActivityNotFoundException) {
            // Android TV, policy-managed devices and some emulator images have no consent
            // activity; this used to be an uncaught crash on the primary button (M14).
            Log.w(TAG, "No VPN consent activity", e)
            viewModel.onVpnConsentUnavailable("This device does not support VPN apps - boosting without the stabilizer")
        }
    }

    private fun launchGame(packageName: String) {
        // The ViewModel checked this already; the game could have been removed since.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            viewModel.onLaunchFailed(packageName)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun requestNotificationsThenBoost() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onBoostRequested()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
