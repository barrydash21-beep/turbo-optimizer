package com.cj.turboboost

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

/** A game card's render resolution: what the game holds now, plus this screen's transient state. */
data class GameResolutionUi(
    val status: GameResolutionStatus = GameResolutionStatus(),
    val busy: Boolean = false,
    /** Why the last change failed, until the next one. */
    val note: String? = null
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
    private val optionsStore: KeyValueStore = Stores.options(app)
    private val resolutionStore: KeyValueStore = Stores.gameResolution(app)
    private val legacyResolutionStore: KeyValueStore = Stores.legacyResolution(app)
    private var staleRevertStarted = false
    private var overrideCheckStarted = false

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

    /** Per-game downscale needs Android 14+; below that the feature is hidden. */
    val resolutionSupported: Boolean = Build.VERSION.SDK_INT >= GameResolution.MIN_SDK

    /** Render resolution state per game package, for its card. */
    var gameResolution by mutableStateOf<Map<String, GameResolutionUi>>(emptyMap())
        private set

    /** A `wm size` / `wm density` override found by this build's one-time check, awaiting an answer. */
    var displayOverride by mutableStateOf<DisplayState?>(null)
        private set

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
            refreshGameResolution()
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
            val was = shizukuState
            shizukuState = probeShizuku()
            // Shizuku arriving late leaves the resolution rows with nothing read yet.
            if (shizukuState == ShizukuState.READY && was != ShizukuState.READY) refreshGameResolution()
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
                    // A phone restart puts the game back in standard mode; set its preset again first.
                    val reapplied = if (resolutionSupported) {
                        withContext(Dispatchers.IO) {
                            GameResolution.reapplyBeforeLaunch(RamCleaner.shell, resolutionStore, gamePackage)
                        }
                    } else {
                        null
                    }
                    reapplied?.let { _events.send(BoostEvent.ShowToast(it.detail)) }
                    val outcome = withTimeoutOrNull(BOOST_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            // (a) + the profile's extras; the kill runs first inside it. A render
                            // resolution preset lives in the game's custom mode: keep that mode.
                            val keepMode = GameResolution.applied(resolutionStore, gamePackage) != null
                            val report = RamCleaner.applyProfile(active, gamePackage, snapshot, keepGameMode = keepMode)
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
                    if (PerformanceTuner.isActive(tunerStore)) {
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
    // Render resolution: per-game downscale through GameManager
    // -----------------------------------------------------------------------

    /**
     * Re-reads every game's downscale state, files any finished measurement, and arms a
     * measurement watcher for each game whose card still lacks one. Keeps the last good
     * state when Shizuku is down.
     */
    fun refreshGameResolution() {
        if (!resolutionSupported) return
        val packages = games?.map { it.profile.packageName } ?: return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val shell = RamCleaner.shell
                if (!shell.isAvailable()) return@withContext null
                packages.associateWith { pkg ->
                    GameResolution.status(shell, resolutionStore, pkg).also { status ->
                        if (status.unavailableReason == null && GameResolution.needsMeasurement(resolutionStore, pkg)) {
                            GameResolution.startWatcher(
                                shell,
                                pkg,
                                GameResolution.applied(resolutionStore, pkg),
                                GameResolution.stalePid(resolutionStore, pkg)
                            )
                        }
                    }
                }
            } ?: return@launch
            gameResolution = found.mapValues { (pkg, status) ->
                (gameResolution[pkg] ?: GameResolutionUi()).copy(status = status)
            }
        }
    }

    /** A preset picked on [packageName]'s card; null is the game's own default (a restore). */
    fun pickResolution(packageName: String, level: ResolutionLevel?) {
        val current = gameResolution[packageName] ?: GameResolutionUi()
        if (level == current.status.level && current.status.changedOutside == null) return
        changeResolution(packageName) { shell, running ->
            if (level == null) {
                GameResolution.restore(shell, resolutionStore, packageName, force = false, runningPid = running)
            } else {
                GameResolution.apply(shell, resolutionStore, packageName, level, running)
            }
        }
    }

    /** The user confirmed restoring over a mode that was changed outside this app. */
    fun restoreResolutionAnyway(packageName: String) =
        changeResolution(packageName) { shell, running ->
            GameResolution.restore(shell, resolutionStore, packageName, force = true, runningPid = running)
        }

    /**
     * Runs one apply or restore. A game that is running keeps its old setting until it
     * restarts; the card says so and offers a restart, which only the user can start.
     */
    private fun changeResolution(packageName: String, change: (ShellExecutor, String?) -> GameResolutionResult) {
        if (step != BoostStep.IDLE || gameResolution[packageName]?.busy == true) return
        if (!GameRegistry.isValidPackageName(packageName)) return
        updateResolution(packageName) { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val shell = RamCleaner.shell
                    if (!shell.isAvailable()) {
                        GameResolutionResult(false, "Shizuku not connected - nothing changed", unavailable = true)
                    } else {
                        change(shell, GameResolution.pidOf(shell, packageName))
                    }
                }
                if (!result.ok) {
                    updateResolution(packageName) { it.copy(note = result.detail) }
                    if (!result.changedOutside) _events.send(BoostEvent.ShowToast(result.detail))
                }
            } finally {
                updateResolution(packageName) { it.copy(busy = false) }
                refreshGameResolution()
            }
        }
    }

    /** "Restart now", after the user confirmed it closes the game: force-stop, then relaunch. */
    fun restartGame(packageName: String) {
        if (step != BoostStep.IDLE || !GameRegistry.isValidPackageName(packageName)) return
        viewModelScope.launch {
            val stopped = withContext(Dispatchers.IO) { RamCleaner.shell.run("am force-stop $packageName").ok }
            if (!stopped) {
                _events.send(BoostEvent.ShowToast("Could not close ${gameName(packageName)} - nothing changed"))
                return@launch
            }
            val reapplied = withContext(Dispatchers.IO) {
                GameResolution.reapplyBeforeLaunch(RamCleaner.shell, resolutionStore, packageName)
            }
            reapplied?.let { _events.send(BoostEvent.ShowToast(it.detail)) }
            // Clears the pending note and arms the measurement for the new process.
            refreshGameResolution()
            pendingLaunch = packageName
        }
    }

    private fun updateResolution(packageName: String, change: (GameResolutionUi) -> GameResolutionUi) {
        gameResolution = gameResolution + (packageName to change(gameResolution[packageName] ?: GameResolutionUi()))
    }

    /**
     * This build's one-time check for a `wm size` / `wm density` override, which earlier
     * builds set and silently undid. Nothing is changed here: an override found is put to the
     * user, and only [resetDisplayOverride] touches the display. Waits for Shizuku to bind.
     */
    fun checkDisplayOverride() {
        if (overrideCheckStarted) return
        overrideCheckStarted = true
        viewModelScope.launch {
            repeat(STALE_REVERT_ATTEMPTS) {
                val outcome = withContext(Dispatchers.IO) {
                    val shell = RamCleaner.shell
                    when {
                        DisplayOverride.isChecked(optionsStore) -> true to null
                        !shell.isAvailable() -> false to null
                        else -> DisplayOverride.check(shell, optionsStore, legacyResolutionStore).let { found ->
                            (found != null || DisplayOverride.isChecked(optionsStore)) to found
                        }
                    }
                }
                if (outcome.first) {
                    displayOverride = outcome.second
                    return@launch
                }
                delay(2000)
            }
        }
    }

    /** "Reset" on the override prompt. */
    fun resetDisplayOverride() {
        viewModelScope.launch {
            val after = withContext(Dispatchers.IO) {
                if (RamCleaner.shell.isAvailable()) DisplayOverride.reset(RamCleaner.shell) else null
            }
            if (after != null && !after.hasOverride) {
                DisplayOverride.markChecked(optionsStore)
                displayOverride = null
                _events.send(BoostEvent.ShowToast("Display back to device default"))
            } else {
                val why = after?.let { "display still reports ${it.size} @ ${it.density} dpi" } ?: "Shizuku not connected"
                _events.send(BoostEvent.ShowToast("Reset not confirmed: $why"))
            }
        }
    }

    /** "Keep" on the override prompt: never asked again by this build. */
    fun keepDisplayOverride() {
        DisplayOverride.markChecked(optionsStore)
        displayOverride = null
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
     *
     * Per-game render resolution presets are left alone: like the per-game refresh rate they
     * are the user's saved choice for that game, undone with DEFAULT on its card.
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
                    if (tune.failed.isEmpty()) system else system.copy(failed = system.failed + tune.failed)
                }
            }
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
