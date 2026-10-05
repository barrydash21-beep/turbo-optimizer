package com.cj.turboboost

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cj.turboboost.ui.BoostStep
import com.cj.turboboost.ui.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** One-shot things only an Activity can do. Buffered, so a recreation cannot swallow them. */
sealed interface BoostEvent {
    data class ShowToast(val message: String) : BoostEvent
    data object RequestShizukuPermission : BoostEvent
    data object RequestVpnConsent : BoostEvent
}

/** An app the user added as a game that is still installed, with the rates this panel can show. */
data class InstalledGame(
    val profile: GameProfile,
    val label: String,
    val icon: ImageBitmap?,
    /** [GameProfile.allowedRefreshRates] filtered to the display's supported modes. */
    val supportedRates: List<Float>
)

/** A launchable app offered in the "add a game" picker. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?
)

/**
 * Owns the boost. Everything that used to be a `mutableStateOf` field on `MainActivity` and a
 * coroutine on `lifecycleScope` lives here instead.
 *
 * The point is `viewModelScope`: a rotation, a dark-mode switch, a font-size change or a fold
 * used to destroy the Activity mid-boost, cancelling the sequence at its first suspension
 * point — after the device had already been mutated but before the game was launched, with no
 * error shown (H3). The ViewModel outlives the Activity, so the sequence runs to completion
 * and the game still launches via [pendingLaunch].
 */
class BoostViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "TurboBooster"

        /** Ceiling on the whole privileged command sequence, so the UI can never wedge. */
        const val BOOST_TIMEOUT_MS = 60_000L // force-stopping every third-party app takes longer

        /** Same idea for the restore sequence, which issues a comparable number of commands. */
        const val RESTORE_TIMEOUT_MS = 30_000L

        /** How long to wait for the stabilizer tunnel to come up before giving up on it. */
        const val VPN_START_TIMEOUT_MS = 3_000L

        const val SHIZUKU_PROBE_RETRIES = 5

        /** 2 s apart: about how long Shizuku may take to bind after a cold start. */
        const val STALE_REVERT_ATTEMPTS = 8
    }

    private val prefs = BoosterPrefs(app)
    private val snapshot: KeyValueStore = Stores.snapshot(app)
    private val tunerStore: KeyValueStore = Stores.tuner(app)
    private val sessions = SessionStore(Stores.sessions(app))
    private val resolutionStore: KeyValueStore = Stores.resolution(app)
    private var staleRevertStarted = false
    private var resolutionRecoveryStarted = false

    var step by mutableStateOf(BoostStep.IDLE)
        private set
    var profile by mutableStateOf(prefs.profile)
        private set
    var vpnEnabled by mutableStateOf(prefs.vpnEnabled)
        private set
    var shizukuState by mutableStateOf(ShizukuState.OFFLINE)
        private set

    /** The user's games that are installed, or null while they are still being looked up. */
    var games by mutableStateOf<List<InstalledGame>?>(null)
        private set

    /** Apps that can be added as games; null while the picker is closed or still loading. */
    var pickerApps by mutableStateOf<List<LaunchableApp>?>(null)
        private set
    var pickerOpen by mutableStateOf(false)
        private set

    /** Package of the picked game; null until the user picks one. */
    var selectedGame by mutableStateOf(prefs.selectedGame)
        private set

    /** The chosen rate per installed game, always one of its supported rates. */
    var rates by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    /** The finished session whose summary is on screen, or null. */
    var summary by mutableStateOf<SessionRecord?>(null)
        private set

    /** The lower rate the summary suggests, or null when the session went fine. */
    var summarySuggestion by mutableStateOf<Float?>(null)
        private set

    /** Last `wm size` / `wm density` read, or null before the first successful read. */
    var display by mutableStateOf<DisplayState?>(null)
        private set

    /** The last read failed (Shizuku was up but `wm` gave nothing parseable). */
    var displayReadFailed by mutableStateOf(false)
        private set

    /** Percent of the preset picked on the resolution card; null means follow the current state. */
    var resolutionPick by mutableStateOf<Int?>(null)
        private set

    var resolutionBusy by mutableStateOf(false)
        private set

    /** Why the last apply was blocked or failed, shown on the card until the next action. */
    var resolutionNote by mutableStateOf<String?>(null)
        private set

    /** The note text when it is a "can't apply right now" block, so it can be re-checked. */
    private var blockNote: String? = null

    /**
     * The package to launch once the boost finishes, held as state rather than sent as a
     * one-shot event: `Channel.receiveAsFlow()` can drop an element if the collector is
     * cancelled mid-emit, which is precisely the rotation-during-a-boost case H3 is about.
     * State survives in the ViewModel until an Activity acknowledges it.
     */
    var pendingLaunch by mutableStateOf<String?>(null)
        private set

    private var resumeAfterPermission = false
    private var boostJob: Job? = null

    private val _events = Channel<BoostEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refreshGames()
        // Fires once now and again whenever the monitor saves a session, so a summary shows
        // even if the booster is already open when the game exits.
        viewModelScope.launch { SessionStore.saved.collect { checkSummary() } }
        // The guard can restore on its own (countdown expiry, notification action): re-read then.
        viewModelScope.launch { ResolutionGuardService.state.collect { refreshResolution() } }
        // A session ending while the screen is open clears a "session in progress" note.
        viewModelScope.launch { GameMonitorService.running.collect { if (!it) recheckResolutionBlock() } }
    }

    // -----------------------------------------------------------------------
    // Games
    // -----------------------------------------------------------------------

    /** Re-reads which of the user's games are installed. Cheap; called on every resume. */
    fun refreshGames() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { loadInstalledGames() }
            games = found
            rates = found.mapNotNull { game ->
                val pkg = game.profile.packageName
                RefreshRate.resolve(game.supportedRates, prefs.rateFor(pkg), game.profile.defaultRefreshRate)
                    ?.let { pkg to it }
            }.toMap()
            // A selection whose game has since been uninstalled is no selection at all.
            if (found.none { it.profile.packageName == selectedGame }) selectedGame = null
        }
    }

    private fun loadInstalledGames(): List<InstalledGame> {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val panelModes = SystemTelemetry.getSupportedRefreshRates(app)
        Log.i(TAG, "Display supported refresh rates: $panelModes")
        return prefs.games.mapNotNull { pkg ->
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            val profile = GameRegistry.profileFor(pkg, label)
            InstalledGame(
                profile = profile,
                label = label,
                icon = runCatching { pm.getApplicationIcon(info).toBitmap(128, 128).asImageBitmap() }.getOrNull(),
                supportedRates = RefreshRate.supported(profile.allowedRefreshRates, panelModes)
            )
        }
    }

    /**
     * Every app with a launcher entry, minus this app, Shizuku and games already added.
     * Visible on Android 11+ through the launcher-intent `<queries>` entry, so no
     * QUERY_ALL_PACKAGES is needed.
     */
    private fun loadLaunchableApps(): List<LaunchableApp> {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val added = prefs.games.toSet()
        val excluded = setOf(app.packageName, AppPackages.SHIZUKU)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in excluded && it !in added }
            .mapNotNull { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                LaunchableApp(
                    packageName = pkg,
                    label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: pkg,
                    icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun openPicker() {
        pickerOpen = true
        pickerApps = null
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { loadLaunchableApps() }
            if (pickerOpen) pickerApps = found
        }
    }

    fun closePicker() {
        pickerOpen = false
        pickerApps = null
    }

    /** Adds [packageName] as a game and selects it. */
    fun addGame(packageName: String) {
        if (!GameRegistry.isValidPackageName(packageName)) return
        prefs.games = prefs.games + packageName
        closePicker()
        selectGame(packageName)
        refreshGames()
    }

    /** Removes a game from the list. Its saved refresh rate is kept in case it is re-added. */
    fun removeGame(packageName: String) {
        prefs.games = prefs.games - packageName
        if (selectedGame == packageName) {
            selectedGame = null
            prefs.selectedGame = null
        }
        refreshGames()
    }

    fun selectGame(packageName: String) {
        selectedGame = packageName
        prefs.selectedGame = packageName
        // A note about the previously selected game running no longer applies.
        recheckResolutionBlock()
    }

    /** Picking a rate on a card also picks that card's game. */
    fun setRate(packageName: String, rate: Float) {
        rates = rates + (packageName to rate)
        prefs.setRate(packageName, rate)
        selectGame(packageName)
    }

    // -----------------------------------------------------------------------
    // Session summary
    // -----------------------------------------------------------------------

    fun checkSummary() {
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { sessions.unseen() } ?: return@launch
            val supported = games?.firstOrNull { it.profile.packageName == record.packageName }?.supportedRates
                ?: RefreshRate.supported(
                    GameRegistry.DEFAULT_RATES,
                    SystemTelemetry.getSupportedRefreshRates(getApplication())
                )
            summarySuggestion = Diagnostics.suggestion(record, supported)
            summary = record
        }
    }

    fun dismissSummary() {
        summary?.let { record -> viewModelScope.launch(Dispatchers.IO) { sessions.markSeen(record) } }
        summary = null
        summarySuggestion = null
    }

    /** One tap from the summary: store the suggested lower rate for that game. */
    fun applySuggestion() {
        val record = summary ?: return
        val rate = summarySuggestion ?: return
        rates = rates + (record.packageName to rate)
        prefs.setRate(record.packageName, rate)
        val name = gameName(record.packageName)
        viewModelScope.launch { _events.send(BoostEvent.ShowToast("$name will use ${RefreshRate.display(rate)} Hz")) }
        dismissSummary()
    }

    // -----------------------------------------------------------------------
    // Options
    // -----------------------------------------------------------------------

    fun selectProfile(value: BoostProfile) {
        profile = value
        prefs.profile = value
    }

    fun setStabilizerEnabled(enabled: Boolean) {
        vpnEnabled = enabled
        prefs.vpnEnabled = enabled
        // Turning it off should take effect now, not just next boost.
        if (!enabled && NetworkBlockerVpnService.isRunning.value) stopVpn()
    }

    // -----------------------------------------------------------------------
    // Shizuku
    // -----------------------------------------------------------------------

    /**
     * `pingBinder` and `checkSelfPermission` are synchronous binder transactions into another
     * process; a wedged Shizuku server used to block the main thread straight into an ANR
     * (M15). Both now run on [Dispatchers.IO] with only the assignment on Main.
     */
    private suspend fun probeShizuku(): ShizukuState = withContext(Dispatchers.IO) {
        try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.OFFLINE
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuState.NEEDS_PERMISSION
                else -> ShizukuState.READY
            }
        } catch (e: Exception) {
            ShizukuState.OFFLINE
        }
    }

    fun refreshShizuku() {
        viewModelScope.launch {
            shizukuState = probeShizuku()
            // Shizuku arriving late leaves the resolution card with nothing read yet.
            if (shizukuState == ShizukuState.READY && display == null) refreshResolution()
        }
    }

    /** Shizuku can take a moment to bind after launch. Stops as soon as it is found (M15). */
    fun startShizukuDetection() {
        viewModelScope.launch {
            repeat(SHIZUKU_PROBE_RETRIES) {
                if (shizukuState != ShizukuState.OFFLINE) return@launch
                delay(1000)
                shizukuState = probeShizuku()
            }
        }
    }

    fun onShizukuPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            shizukuState = probeShizuku()
            if (!resumeAfterPermission) return@launch
            resumeAfterPermission = false
            if (!granted) _events.send(BoostEvent.ShowToast("Shizuku denied. RAM purge skipped"))
            proceedToVpn()
        }
    }

    // -----------------------------------------------------------------------
    // Boost entry points
    // -----------------------------------------------------------------------

    fun onBoostRequested() {
        if (step != BoostStep.IDLE) return
        // The button is disabled without a pick; this covers a pick uninstalled since.
        if (selectedTarget() == null) {
            viewModelScope.launch { _events.send(BoostEvent.ShowToast("Pick an installed game first")) }
            return
        }
        viewModelScope.launch {
            shizukuState = probeShizuku()
            if (shizukuState == ShizukuState.NEEDS_PERMISSION) {
                resumeAfterPermission = true
                _events.send(BoostEvent.RequestShizukuPermission)
                return@launch
            }
            proceedToVpn()
        }
    }

    fun onShizukuRequestFailed() {
        resumeAfterPermission = false
        viewModelScope.launch { _events.send(BoostEvent.ShowToast("Could not connect to Shizuku")) }
    }

    private suspend fun proceedToVpn() {
        // The stabilizer is opt-in; skip the consent dialog entirely when it is off.
        if (!vpnEnabled) {
            runSequence()
            return
        }
        _events.send(BoostEvent.RequestVpnConsent)
    }

    /**
     * Declining the consent dialog used to abort the whole boost — a total first-run failure
     * for an opt-in extra (M6). It now just turns the stabilizer off and carries on.
     */
    fun onVpnConsentResult(granted: Boolean) {
        viewModelScope.launch {
            if (!granted) {
                setStabilizerEnabled(false)
                _events.send(BoostEvent.ShowToast("Stabilizer off, boosting without it"))
            }
            runSequence()
        }
    }

    /** No VPN consent activity on this device (M14), or consent was not needed. */
    fun onVpnConsentUnavailable(reason: String?) {
        viewModelScope.launch {
            if (reason != null) {
                setStabilizerEnabled(false)
                _events.send(BoostEvent.ShowToast(reason))
            }
            runSequence()
        }
    }

    // -----------------------------------------------------------------------
    // The sequence
    // -----------------------------------------------------------------------

    /** The picked game and its rate, or null if either is missing or no longer installed. */
    private fun selectedTarget(): Pair<GameProfile, Float>? {
        val pkg = selectedGame ?: return null
        val profile = games?.firstOrNull { it.profile.packageName == pkg }?.profile ?: return null
        val rate = rates[pkg] ?: return null
        return profile to rate
    }

    fun runSequence() {
        if (step != BoostStep.IDLE || boostJob?.isActive == true) return
        val (game, rate) = selectedTarget() ?: return
        executeBoostAndLaunch(game, rate)
    }

    /**
     * The whole pipeline, in order: (a) kill background apps, (b) apply the tweaks, (c) start
     * the exit monitor, (d) launch the game. Shizuku work runs on IO, the launch on Main.
     */
    private fun executeBoostAndLaunch(game: GameProfile, rate: Float) {
        val app = getApplication<Application>()
        val gamePackage = game.packageName
        boostJob = viewModelScope.launch {
            try {
                step = BoostStep.CLEANING
                val active = profile

                if (shizukuState == ShizukuState.READY) {
                    val outcome = withTimeoutOrNull(BOOST_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            // (a) + the profile's extras; the kill runs first inside it.
                            val report = RamCleaner.applyProfile(active, gamePackage, snapshot)
                            // (b) The per-game rate and power mode. Saves the originals itself.
                            report to PerformanceTuner.applyGamingTweaks(tunerStore, game, rate)
                        }
                    }
                    val report = outcome?.first
                    val tune = outcome?.second
                    val stopped = report?.let { stoppedText(it) }
                    when {
                        report == null ->
                            _events.send(BoostEvent.ShowToast("Boost timed out - some settings may not have applied"))
                        !report.allOk ->
                            _events.send(BoostEvent.ShowToast("${report.summary("Boosted")} ($stopped)"))
                        else ->
                            _events.send(BoostEvent.ShowToast("Boosted - $stopped"))
                    }
                    if (tune != null && !tune.ok) {
                        _events.send(BoostEvent.ShowToast("Display tweaks: ${tune.failed.joinToString(", ")}"))
                    }

                    // (c) Whatever happened above, if tweaks are on something must undo them.
                    // A pending resolution override is also undone by the monitor on game exit.
                    if (PerformanceTuner.isActive(tunerStore) || ResolutionLever.isPending(resolutionStore)) {
                        try {
                            GameMonitorService.start(app, gamePackage, rate)
                        } catch (e: Exception) {
                            _events.send(
                                BoostEvent.ShowToast("Exit monitor could not start - tap RESTORE after your match")
                            )
                        }
                    }
                } else {
                    _events.send(BoostEvent.ShowToast("Shizuku not ready - system tuning skipped"))
                    delay(400)
                }

                if (vpnEnabled) {
                    step = BoostStep.STABILIZING
                    var requested = true
                    if (!NetworkBlockerVpnService.isRunning.value) {
                        // Clear any stale FAILED before asking, then wait for a definite
                        // answer rather than sitting out the full timeout on failure (M5).
                        NetworkBlockerVpnService.markStarting()
                        requested = try {
                            app.startService(Intent(app, NetworkBlockerVpnService::class.java))
                            true
                        } catch (e: Exception) {
                            // Background service-start restrictions, mainly. The stabilizer
                            // is optional, so this must never take the boost down with it.
                            NetworkBlockerVpnService.markStopped()
                            false
                        }
                    }
                    val outcome = if (!requested) null else withTimeoutOrNull(VPN_START_TIMEOUT_MS) {
                        NetworkBlockerVpnService.state.first {
                            it == VpnState.RUNNING || it == VpnState.FAILED
                        }
                    }
                    if (outcome != VpnState.RUNNING) {
                        _events.send(
                            BoostEvent.ShowToast("Stabilizer could not start - another VPN may be active")
                        )
                    }
                }

                step = BoostStep.LAUNCHING
                delay(350)

                // (d) On Main. A game with no launch intent must not leave the tweaks on.
                if (app.packageManager.getLaunchIntentForPackage(gamePackage) == null) {
                    onLaunchFailed(gamePackage)
                } else {
                    pendingLaunch = gamePackage
                }
            } catch (e: CancellationException) {
                // Never fail silently: the device has already been mutated by this point.
                withContext(NonCancellable) {
                    _events.trySend(BoostEvent.ShowToast("Boost cancelled - tap RESTORE to undo"))
                }
                throw e
            } catch (e: Exception) {
                _events.send(BoostEvent.ShowToast("Boost failed: ${e.localizedMessage}"))
            } finally {
                step = BoostStep.IDLE
            }
        }
    }

    /** Called by the Activity once it has actually started the game. */
    fun onLaunchHandled() {
        pendingLaunch = null
    }

    /** No launch intent for the game: say so and undo the boost straight away. */
    fun onLaunchFailed(packageName: String) {
        val name = gameName(packageName)
        viewModelScope.launch { _events.send(BoostEvent.ShowToast("$name can't be launched - restoring your settings")) }
        restoreEverything()
    }

    private fun gameName(packageName: String): String =
        games?.firstOrNull { it.profile.packageName == packageName }?.label
            ?: GameRegistry.labelFor(getApplication(), packageName)
            ?: packageName

    private fun stoppedText(report: StepReport): String {
        val base = "stopped ${report.stoppedCount} apps"
        if (report.killFailed.isEmpty()) return base
        val names = report.killFailed.take(3).joinToString(", ") +
            if (report.killFailed.size > 3) " +${report.killFailed.size - 3} more" else ""
        return "$base, ${report.killFailed.size} failed: $names"
    }

    // -----------------------------------------------------------------------
    // Render resolution
    // -----------------------------------------------------------------------

    /** Re-reads `wm size` / `wm density`. Keeps the last good read when Shizuku is down. */
    fun refreshResolution() {
        viewModelScope.launch {
            val shell = RamCleaner.shell
            val state = withContext(Dispatchers.IO) {
                if (shell.isAvailable()) ResolutionLever.read(shell) to true else null to false
            }
            if (!state.second) return@launch
            displayReadFailed = state.first == null
            if (state.first != null) display = state.first
        }
        // Called on every resume: the game may have been closed while the user was away.
        recheckResolutionBlock()
    }

    fun pickResolution(percent: Int) {
        resolutionPick = percent
        resolutionNote = null
    }

    /**
     * Applies the picked preset before launch. Blocked while the selected game (or any monitored
     * session) is running: a running game would be resized under it.
     */
    fun applyResolution() {
        if (step != BoostStep.IDLE || resolutionBusy) return
        val state = display ?: return
        val preset = DisplayResolution.presets(state).firstOrNull { !it.native && it.percent == resolutionPick } ?: return
        val app = getApplication<Application>()
        resolutionBusy = true
        resolutionNote = null
        viewModelScope.launch {
            try {
                shizukuState = probeShizuku()
                if (shizukuState != ShizukuState.READY) {
                    resolutionNote = "Shizuku is not ready - resolution unchanged"
                    return@launch
                }
                val blocked = resolutionBlockReason()
                if (blocked != null) {
                    resolutionNote = blocked
                    blockNote = blocked
                    _events.send(BoostEvent.ShowToast(blocked))
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    ResolutionLever.apply(RamCleaner.shell, resolutionStore, preset.target)
                }
                result.state?.let { display = it }
                if (!result.ok) {
                    resolutionNote = result.detail
                    _events.send(BoostEvent.ShowToast(result.detail))
                    return@launch
                }
                try {
                    // The deadline that still fires if HiOS freezes this app once it is backgrounded.
                    val watchdog = withContext(Dispatchers.IO) {
                        ResolutionLever.startWatchdog(
                            RamCleaner.shell,
                            resolutionStore,
                            (ResolutionGuardService.CONFIRM_MS / 1000).toInt()
                        )
                    }
                    check(watchdog) { "shell watchdog did not start" }
                    ResolutionGuardService.arm(app)
                } catch (e: Exception) {
                    // No countdown means no safety net: do not leave the override on.
                    Log.w(TAG, "Resolution guard could not start", e)
                    val undo = withContext(Dispatchers.IO) { ResolutionGuardService.restoreBlocking(app, onlyIfPending = true) }
                    undo.state?.let { display = it }
                    resolutionNote = "Safety countdown could not start - ${if (undo.ok) "restored" else undo.detail}"
                    _events.send(BoostEvent.ShowToast(resolutionNote!!))
                }
            } finally {
                resolutionBusy = false
            }
        }
    }

    /**
     * Why an apply must wait, or null when it may go ahead. pidof exits 0 when running and 1
     * when not; anything else is a check that failed, and a failed check blocks rather than
     * risk resizing a running game.
     */
    private suspend fun resolutionBlockReason(): String? {
        if (GameMonitorService.isRunning) {
            return "A game session is in progress. The resolution is only changed before launch."
        }
        val pkg = selectedGame?.takeIf { GameRegistry.isValidPackageName(it) } ?: return null
        val pid = withContext(Dispatchers.IO) { RamCleaner.shell.run("pidof $pkg") }
        return when {
            pid.ok -> "${gameName(pkg)} is running. Close it first: the resolution is only changed before launch."
            pid.exitCode != 1 || pid.timedOut || pid.unavailable || pid.apiUnsupported ->
                "Could not check whether ${gameName(pkg)} is running - resolution unchanged"
            else -> null
        }
    }

    /**
     * A "blocked" note describes a moment, not a state: once the session ends or the game
     * closes it is wrong. Re-runs the same check and drops the note when it no longer holds.
     * Error notes from an apply or restore are left alone.
     */
    private fun recheckResolutionBlock() {
        val shown = resolutionNote ?: return
        if (shown != blockNote) return
        viewModelScope.launch {
            // Without Shizuku the check cannot run; the card shows that reason instead anyway.
            if (!withContext(Dispatchers.IO) { RamCleaner.shell.isAvailable() }) return@launch
            val still = resolutionBlockReason()
            if (resolutionNote != shown) return@launch // replaced while the check ran
            resolutionNote = still
            blockNote = still
        }
    }

    /** "Keep this resolution?" answered yes. */
    fun keepResolution() {
        ResolutionGuardService.keep(getApplication())
    }

    /**
     * Restore default. With an override this app applied, puts the recorded original back;
     * with one it did not apply, resets both axes. Pending is read here, before the IO hop, so
     * a guard restore landing first turns this into a no-op instead of a reset of the original.
     */
    fun restoreResolution() {
        if (resolutionBusy) return
        val app = getApplication<Application>()
        val pending = ResolutionLever.isPending(resolutionStore)
        resolutionBusy = true
        resolutionNote = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ResolutionGuardService.restoreBlocking(app, onlyIfPending = pending)
                }
                result.state?.let { display = it }
                if (!result.ok) resolutionNote = result.detail
                _events.send(BoostEvent.ShowToast(if (result.ok) "Display back to default" else result.detail))
                refreshResolution()
            } finally {
                resolutionBusy = false
            }
        }
    }

    /**
     * On app start, undo an override that should not be there: one a dead process left pending
     * (crash, force-close, reboot), one that no longer matches what this app applied, or, with
     * this app's records gone, one that is exactly this app's own preset. An override from
     * anywhere else is the user's and is left alone; the card still offers Restore default.
     */
    fun recoverResolution() {
        if (resolutionRecoveryStarted) return
        resolutionRecoveryStarted = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            repeat(STALE_REVERT_ATTEMPTS) {
                val outcome = withContext(Dispatchers.IO) {
                    val shell = RamCleaner.shell
                    if (!shell.isAvailable()) return@withContext null
                    val state = ResolutionLever.read(shell) ?: return@withContext null
                    val pending = ResolutionLever.isPending(resolutionStore)
                    val intended = ResolutionLever.intended(resolutionStore)
                    val stale = when {
                        pending && !ResolutionLever.appliedInThisProcess -> true
                        pending -> intended == null || !DisplayResolution.matches(state, intended)
                        else -> DisplayResolution.isOwnPreset(state)
                    }
                    if (!stale) return@withContext state to null
                    Log.w(TAG, "Unintended resolution override $state (pending=$pending) - restoring")
                    state to ResolutionGuardService.restoreBlocking(app, onlyIfPending = pending)
                }
                if (outcome == null) {
                    delay(2000)
                    return@repeat
                }
                val (state, restore) = outcome
                display = restore?.state ?: state
                displayReadFailed = false
                if (restore != null) {
                    if (!restore.ok) resolutionNote = restore.detail
                    _events.send(
                        BoostEvent.ShowToast(
                            if (restore.ok) "Restored the default display resolution" else restore.detail
                        )
                    )
                }
                return@launch
            }
        }
    }

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------

    fun stopVpn() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, NetworkBlockerVpnService::class.java)
                .setAction(NetworkBlockerVpnService.ACTION_STOP)
        )
    }

    /**
     * The single "return to normal" action: drops the VPN and reverts every system change
     * the booster made. Always reachable from the UI, since those changes outlive the VPN.
     */
    fun restoreEverything() {
        stopVpn()
        val app = getApplication<Application>()
        GameMonitorService.stop(app)
        viewModelScope.launch {
            val report = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val system = RamCleaner.restoreSystemState(snapshot)
                    // Refresh rate and fixed performance mode: the user's exact saved values.
                    val tune = PerformanceTuner.revertGamingTweaks(tunerStore)
                    // Render resolution: only an override this app applied.
                    val resolution = ResolutionGuardService.restoreBlocking(app, onlyIfPending = true)
                    val failed = tune.failed + if (resolution.ok) emptyList() else listOf("Resolution")
                    if (failed.isEmpty()) system else system.copy(failed = system.failed + failed)
                }
            }
            refreshResolution()
            _events.send(
                BoostEvent.ShowToast(report?.summary("System restored") ?: "Restore timed out - try again")
            )
        }
    }

    /**
     * On app start: if the tweaks are still marked active and no monitor is watching a live
     * game, the app or game crashed or the phone rebooted mid-session, so put things back.
     * The monitor runs in this process, so it is only ever "running" for a genuine session.
     *
     * Shizuku can take a few seconds to bind after launch, so this waits for it.
     */
    fun revertStaleTweaks() {
        if (staleRevertStarted) return
        staleRevertStarted = true
        viewModelScope.launch {
            repeat(STALE_REVERT_ATTEMPTS) {
                val stale = withContext(Dispatchers.IO) {
                    PerformanceTuner.isActive(tunerStore) && !GameMonitorService.isRunning
                }
                if (!stale) return@launch
                val tune = withContext(Dispatchers.IO) {
                    if (shizukuState == ShizukuState.READY || RamCleaner.shell.isAvailable()) {
                        PerformanceTuner.revertGamingTweaks(tunerStore)
                    } else {
                        null
                    }
                }
                if (tune != null && tune.ok) {
                    _events.send(BoostEvent.ShowToast("Restored your original display settings"))
                    return@launch
                }
                delay(2000)
            }
        }
    }
}
