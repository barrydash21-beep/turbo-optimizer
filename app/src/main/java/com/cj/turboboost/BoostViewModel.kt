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
    val note: String? = null,
    /** The newest session played under the cap held now that measured an average FPS. */
    val capSession: SessionRecord? = null
)

/** The diagnostics panel: its rows once checked, and whether a check is running. */
data class DiagnosticsUi(
    val rows: List<DiagnosticRow>? = null,
    val running: Boolean = false,
    val checkedAt: String? = null
)

/** The background restriction list editor, while it is open. */
data class RestrictEditorUi(
    /** Installed packages; null while loading or without Shizuku. */
    val installed: Set<String>? = null,
    /** Never restrictable: the fixed set plus the launcher, keyboard, SMS app and dialer. */
    val protected: Set<String>? = null,
    val loading: Boolean = true,
    /** Why the last add was refused, or why adding is unavailable. */
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
    private val restrictionStore: KeyValueStore = Stores.restrictions(app)
    private val sessionStore: KeyValueStore = Stores.session(app)
    private val sessions = SessionStore(Stores.sessions(app))
    private val optionsStore: KeyValueStore = Stores.options(app)
    private val resolutionStore: KeyValueStore = Stores.gameResolution(app)
    private val legacyResolutionStore: KeyValueStore = Stores.legacyResolution(app)
    private var staleRevertStarted = false
    private var bucketRecheckStarted = false
    private var overrideCheckStarted = false

    var step by mutableStateOf(BoostStep.IDLE)
        private set
    var profile by mutableStateOf(prefs.profile)
        private set
    var vpnEnabled by mutableStateOf(prefs.vpnEnabled)
        private set
    var restrictEnabled by mutableStateOf(prefs.restrictBackground)
        private set

    /** The user's restriction list, or null while it has never been edited (defaults apply). */
    var restrictList by mutableStateOf(prefs.restrictedApps)
        private set

    /** The list editor, or null while it is closed. */
    var restrictEditor by mutableStateOf<RestrictEditorUi?>(null)
        private set

    /** Per package: what the last restrict or restore did, for the editor's rows. */
    var restrictOutcomes by mutableStateOf<Map<String, String>>(emptyMap())
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

    /** The read-only diagnostics panel. */
    var diagnostics by mutableStateOf(DiagnosticsUi())
        private set

    /** Saved sessions for the comparison, newest last; null until the section is opened. */
    var comparisonSessions by mutableStateOf<List<SessionRecord>?>(null)
        private set

    /**
     * Per package, what the system ignored in this process's last restriction, for the
     * diagnostics panel. Null until a boost restricts; not kept across app restarts.
     */
    @Volatile
    private var restrictIgnored: Map<String, List<String>>? = null

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
            // A baseline ran at the rate the system chose, not one picked here: nothing to suggest.
            summarySuggestion = if (record.isBaseline) null else Diagnostics.suggestion(record, supported)
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

    /** Off leaves every package alone from the next boost; a session already held still restores. */
    fun setRestrictionEnabled(enabled: Boolean) {
        restrictEnabled = enabled
        prefs.restrictBackground = enabled
    }

    // -----------------------------------------------------------------------
    // Background restriction list
    // -----------------------------------------------------------------------

    /**
     * The list a session restricts, as the editor shows it: the user's own, or else the
     * installed defaults that are not protected (all defaults until Shizuku has answered).
     */
    fun restrictShown(): List<String> {
        restrictList?.let { return it }
        val editor = restrictEditor
        if (editor?.installed != null && editor.protected != null) {
            return BackgroundRestrictor.defaultsFor(editor.installed, editor.protected)
        }
        return AppPackages.RESTRICT_DEFAULTS
    }

    /** Reads what is installed and what is protected, which adding needs. */
    fun openRestrictEditor() {
        restrictEditor = RestrictEditorUi()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val shell = RamCleaner.shell
                if (!shell.isAvailable()) {
                    null
                } else {
                    RamCleaner.parsePackageList(shell.capture("pm list packages")) to
                        RamCleaner.protectedPackages(shell, null)
                }
            }
            val editor = restrictEditor ?: return@launch
            val installed = loaded?.first
            val protected = loaded?.second
            restrictEditor = if (installed == null || protected == null) {
                editor.copy(loading = false, note = "Connect Shizuku to add apps: it checks which are installed and protected")
            } else {
                editor.copy(installed = installed, protected = protected, loading = false)
            }
        }
    }

    fun closeRestrictEditor() {
        restrictEditor = null
    }

    /** Adds [raw] to the list unless it is protected, not installed or not a package name. */
    fun addRestricted(raw: String) {
        val editor = restrictEditor ?: return
        val pkg = raw.trim()
        val installed = editor.installed
        val protected = editor.protected
        val refusal = when {
            !GameRegistry.isValidPackageName(pkg) -> "\"$pkg\" is not a package name"
            installed == null || protected == null -> "Connect Shizuku to add apps"
            AppPackages.isProtected(pkg, protected) -> "$pkg is protected and is never restricted"
            pkg !in installed -> "$pkg is not installed"
            else -> null
        }
        if (refusal == null) saveRestricted(restrictShown() + pkg)
        restrictEditor = editor.copy(note = refusal)
    }

    fun removeRestricted(pkg: String) = saveRestricted(restrictShown() - pkg)

    /** Back to the installed defaults. */
    fun resetRestricted() {
        restrictList = null
        prefs.restrictedApps = null
    }

    private fun saveRestricted(list: List<String>) {
        val kept = list.distinct()
        restrictList = kept
        prefs.restrictedApps = kept
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

    /** What a baseline starts with: why it can't, or the game's state, its check and the rate held. */
    private data class BaselineStart(
        val refusal: String? = null,
        val state: String? = null,
        val check: Contamination? = null,
        val rate: Float = 0f
    )

    /**
     * MEASURE ONLY: launches the selected game under the monitor with nothing changed, to record
     * a baseline the boosted sessions are compared with. Refused while anything a boost changes
     * is still held ([Baseline.refusal]). Needs Shizuku, which the watcher reads the game through.
     */
    fun onMeasureRequested() {
        if (step != BoostStep.IDLE || boostJob?.isActive == true) return
        val (game, chosenRate) = selectedTarget() ?: run {
            viewModelScope.launch { _events.send(BoostEvent.ShowToast("Pick an installed game first")) }
            return
        }
        val pkg = game.packageName
        val app = getApplication<Application>()
        boostJob = viewModelScope.launch {
            try {
                step = BoostStep.LAUNCHING
                shizukuState = probeShizuku()
                if (shizukuState != ShizukuState.READY) {
                    if (shizukuState == ShizukuState.NEEDS_PERMISSION) _events.send(BoostEvent.RequestShizukuPermission)
                    _events.send(BoostEvent.ShowToast("Measuring needs Shizuku: the game's frames and state are read through it"))
                    return@launch
                }
                val start = withContext(Dispatchers.IO) { prepareBaseline(pkg, chosenRate) }
                start.refusal?.let {
                    _events.send(BoostEvent.ShowToast(it))
                    return@launch
                }
                if (start.state == null || GameStateRef.hasConfigs(start.state)) {
                    _events.send(BoostEvent.ShowToast("The game holds a Game Manager config - this baseline will be marked invalid"))
                }
                try {
                    GameMonitorService.start(app, pkg, start.rate, SessionRecord.BASELINE, start.check)
                } catch (e: Exception) {
                    _events.send(BoostEvent.ShowToast("The monitor could not start - nothing measured"))
                    return@launch
                }
                if (app.packageManager.getLaunchIntentForPackage(pkg) == null) {
                    _events.send(BoostEvent.ShowToast("${gameName(pkg)} can't be launched"))
                    // Nothing was changed: ending it only closes the session.
                    withContext(Dispatchers.IO) { SessionEnd.finish(SessionContext.of(app), EndReason.NEVER_STARTED) }
                    GameMonitorService.stop(app)
                } else {
                    pendingLaunch = pkg
                }
            } finally {
                step = BoostStep.IDLE
            }
        }
    }

    /** The guard, then the game's state checked against its reference, and the rate the system holds now. */
    private fun prepareBaseline(pkg: String, chosenRate: Float): BaselineStart {
        val open = SessionEnd.state(sessionStore).let { it == SessionEnd.STATE_ACTIVE || it == SessionEnd.STATE_ENDING }
        val held = Baseline.Held(
            tweaks = PerformanceTuner.isActive(tunerStore),
            restriction = BackgroundRestrictor.isActive(restrictionStore),
            systemSnapshot = SystemSnapshot.exists(snapshot),
            gamePreset = GameResolution.holds(resolutionStore, pkg),
            sessionOpen = open || GameMonitorService.isRunning,
            stabilizer = NetworkBlockerVpnService.isRunning.value
        )
        Baseline.refusal(held)?.let { return BaselineStart(refusal = it) }
        val shell = RamCleaner.shell
        val (state, check) = GameStateRef.check(shell, resolutionStore, pkg)
        // Nothing is pinned, so the session's rate is the one the system holds.
        val rate = shell.capture("settings get system peak_refresh_rate")?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: chosenRate
        return BaselineStart(state = state, check = check, rate = rate)
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
     * The whole pipeline, in order: (a) restrict the background list, then kill background
     * apps, (b) apply the tweaks, (c) start the exit monitor, (d) launch the game. Shizuku work
     * runs on IO, the launch on Main.
     */
    private fun executeBoostAndLaunch(game: GameProfile, rate: Float) {
        val app = getApplication<Application>()
        val gamePackage = game.packageName
        boostJob = viewModelScope.launch {
            try {
                step = BoostStep.CLEANING
                val active = profile
                // Off: no package state is read or changed by the restriction at all.
                val restrictCandidates = if (restrictEnabled) restrictList ?: AppPackages.RESTRICT_DEFAULTS else null

                if (shizukuState == ShizukuState.READY) {
                    // Before anything changes: did another app change the game's mode or configs?
                    val startCheck = withContext(Dispatchers.IO) {
                        if (resolutionSupported) GameStateRef.check(RamCleaner.shell, resolutionStore, gamePackage).second else null
                    }
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
                            // (a) Before the kill, so nothing it stops is started again by an
                            // app it has not reached yet. Saves each package's state itself.
                            val restricted = restrictCandidates?.let {
                                BackgroundRestrictor.restrict(RamCleaner.shell, restrictionStore, it, gamePackage)
                            }
                            // + the profile's extras; the kill runs first inside it. A render
                            // resolution preset lives in the game's custom mode: keep that mode.
                            val keepMode = GameResolution.holds(resolutionStore, gamePackage)
                            val report = RamCleaner.applyProfile(active, gamePackage, snapshot, keepGameMode = keepMode)
                            // (b) The per-game rate and power mode. Saves the originals itself.
                            Triple(report, PerformanceTuner.applyGamingTweaks(tunerStore, game, rate), restricted)
                        }
                    }
                    val report = outcome?.first
                    val tune = outcome?.second
                    outcome?.third?.let { restricted ->
                        restrictOutcomes = restricted.packages.associate { it.packageName to it.detail() }
                        restrictIgnored = restricted.packages.filter { it.skipped == null }.associate { it.packageName to it.ignored }
                        _events.send(BoostEvent.ShowToast(restricted.restrictSummary()))
                    }
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
                    if (PerformanceTuner.isActive(tunerStore) || BackgroundRestrictor.isActive(restrictionStore)) {
                        try {
                            GameMonitorService.start(app, gamePackage, rate, active.name, startCheck)
                        } catch (e: Exception) {
                            _events.send(
                                BoostEvent.ShowToast("Exit monitor could not start - tap RESTORE after your match")
                            )
                        }
                    }
                } else {
                    _events.send(BoostEvent.ShowToast("Shizuku not ready - system tuning and background restriction skipped"))
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
                val saved = sessions.all()
                packages.associateWith { pkg ->
                    val read = GameResolution.status(shell, resolutionStore, pkg).also { status ->
                        if (status.unavailableReason == null && GameResolution.needsMeasurement(resolutionStore, pkg)) {
                            GameResolution.startWatcher(
                                shell,
                                pkg,
                                GameResolution.applied(resolutionStore, pkg),
                                GameResolution.stalePid(resolutionStore, pkg)
                            )
                        }
                    }
                    read to read.cap?.let { cap ->
                        saved.lastOrNull { it.packageName == pkg && !it.isBaseline && it.fpsCap == cap.fps && it.avgFps != null }
                    }
                }
            } ?: return@launch
            gameResolution = found.mapValues { (pkg, value) ->
                (gameResolution[pkg] ?: GameResolutionUi()).copy(status = value.first, capSession = value.second)
            }
        }
    }

    /**
     * A preset picked on [packageName]'s card; null is the game's own resolution. The cap held
     * is kept; with none, null is the game's own default (a restore).
     */
    fun pickResolution(packageName: String, level: ResolutionLevel?) {
        val current = gameResolution[packageName] ?: GameResolutionUi()
        if (level == current.status.level && current.status.changedOutside == null) return
        changeResolution(packageName) { shell, running ->
            val cap = GameResolution.appliedFps(resolutionStore, packageName)
            GameResolution.apply(shell, resolutionStore, packageName, level, cap, running)
        }
    }

    /**
     * A frame-rate cap picked on [packageName]'s card; null is no cap. The preset held is
     * kept; with none, null is the game's own default (a restore).
     */
    fun pickFpsCap(packageName: String, cap: FpsCap?) {
        val current = gameResolution[packageName] ?: GameResolutionUi()
        if (cap == current.status.cap && current.status.changedOutside == null) return
        changeResolution(packageName) { shell, running ->
            val level = ResolutionLevel.forScale(GameResolution.applied(resolutionStore, packageName))
            GameResolution.apply(shell, resolutionStore, packageName, level, cap, running)
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
    // Diagnostics and session comparison: read-only
    // -----------------------------------------------------------------------

    /**
     * Runs the panel's probes once, on IO: when it opens and on Re-check, never from
     * recomposition. Reads only; the one write is the card status refresh it reuses.
     */
    fun runDiagnostics() {
        if (diagnostics.running) return
        diagnostics = diagnostics.copy(running = true)
        val pkg = selectedGame?.takeIf { GameRegistry.isValidPackageName(it) }
        viewModelScope.launch {
            try {
                shizukuState = probeShizuku()
                val shizuku = shizukuState
                val note = pkg?.let { gameResolution[it]?.note }
                val input = withContext(Dispatchers.IO) { gatherDiagnostics(pkg, shizuku, note) }
                diagnostics = DiagnosticsUi(
                    rows = DiagnosticsPanel.rows(input),
                    checkedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                )
            } finally {
                diagnostics = diagnostics.copy(running = false)
            }
        }
    }

    private fun gatherDiagnostics(pkg: String?, shizuku: ShizukuState, note: String?): DiagnosticsInput {
        val app = getApplication<Application>()
        val shell = RamCleaner.shell
        val ready = shell.isAvailable()
        val version = pkg?.let {
            runCatching { app.packageManager.getPackageInfo(it, 0).versionName.orEmpty() }.getOrNull()
        }
        val probe = ready && resolutionSupported
        // The card's own status call: reads the game, files a finished measurement, nothing else.
        val status = if (probe && pkg != null) GameResolution.status(shell, resolutionStore, pkg) else null
        // Baselines are for the comparison: the cap's evidence comes from boosted sessions only.
        val saved = if (status?.cap != null) sessions.all().filterNot { it.isBaseline } else emptyList()
        val capSession = status?.cap?.let { cap ->
            saved.lastOrNull { it.packageName == pkg && it.fpsCap == cap.fps && it.avgFps != null }
        }
        // Same game, same render resolution as the capped session, no cap: what the cap is measured against.
        val uncappedSession = capSession?.let { capped ->
            saved.lastOrNull {
                it.packageName == pkg && it.fpsCap == null && it.renderResolution == capped.renderResolution && it.avgFps != null
            }
        }
        return DiagnosticsInput(
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            buildDisplay = Build.DISPLAY,
            shizukuRunning = shizuku != ShizukuState.OFFLINE,
            shizukuPermission = shizuku == ShizukuState.READY,
            shellReady = ready,
            gamePackage = pkg,
            gameVersion = version,
            helpOutput = if (ready) DiagnosticsPanel.gameHelp(shell).first else null,
            read = if (probe && pkg != null) GameResolution.read(shell, pkg) else null,
            status = status,
            lastChangeNote = note,
            capSession = capSession,
            uncappedSession = uncappedSession,
            thermalDump = if (ready) shell.capture("dumpsys thermalservice") else null,
            restrictIgnored = restrictIgnored
        )
    }

    /** The panel as plain text for the clipboard, or null before the first check. */
    fun diagnosticsReport(): String? {
        val ui = diagnostics
        val rows = ui.rows ?: return null
        val app = getApplication<Application>()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "unknown"
        return DiagnosticsPanel.report(rows, version, ui.checkedAt ?: "unknown")
    }

    /** Reads the saved sessions for the comparison; nothing is written. */
    fun loadComparison() {
        viewModelScope.launch {
            comparisonSessions = withContext(Dispatchers.IO) { sessions.all() }
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
     *
     * Per-game render resolution presets are left alone: like the per-game refresh rate they
     * are the user's saved choice for that game, undone with DEFAULT on its card.
     */
    fun restoreEverything() {
        stopVpn()
        val app = getApplication<Application>()
        viewModelScope.launch {
            val report = try {
                withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val system = RamCleaner.restoreSystemState(snapshot)
                        // The session's own end: its record, the refresh rate and fixed performance
                        // mode, each restricted package's op mode and bucket, and its watcher.
                        val end = SessionEnd.finish(SessionContext.of(app), EndReason.MANUAL)
                        withContext(Dispatchers.Main) { noteRestored(end.restrictions) }
                        val failed = end.tweaks.failed + end.restrictions.failedPackages.map { "Background app $it" }
                        if (failed.isEmpty()) system else system.copy(failed = system.failed + failed)
                    }
                }
            } finally {
                // After the end, so the monitor finds the session ended rather than ending it twice.
                GameMonitorService.stop(app)
            }
            _events.send(
                BoostEvent.ShowToast(report?.summary("System restored") ?: "Restore timed out - try again")
            )
        }
    }

    private fun noteRestored(report: RestrictionReport) {
        if (report.packages.isNotEmpty()) {
            restrictOutcomes = restrictOutcomes + report.packages.associate { it.packageName to "restore: ${it.detail()}" }
        }
    }

    /**
     * On app start, in a new boot: puts back any standby bucket a restart returned to
     * restricted after it was restored ([BucketRecheck]). Waits for Shizuku like
     * [revertStaleTweaks]; does nothing in the boot the restore happened in.
     */
    fun recheckBuckets() {
        if (bucketRecheckStarted) return
        bucketRecheckStarted = true
        viewModelScope.launch {
            repeat(STALE_REVERT_ATTEMPTS) {
                val outcomes = withContext(Dispatchers.IO) {
                    val shell = RamCleaner.shell
                    if (!shell.isAvailable()) {
                        null
                    } else {
                        BucketRecheck.recheck(shell, restrictionStore, GameResolution.bootId(shell)) ?: emptyList()
                    }
                }
                if (outcomes == null) {
                    delay(2000)
                    return@repeat
                }
                val setAgain = outcomes.filter { it.setAgain }
                if (setAgain.isNotEmpty()) {
                    Log.i(TAG, "Buckets after a restart: $outcomes")
                    restrictOutcomes = restrictOutcomes + setAgain.associate {
                        it.packageName to "after restart: bucket ${it.found} set back to ${it.saved}, reads ${it.after ?: "unreadable"}"
                    }
                    val names = setAgain.joinToString(", ") { it.packageName }
                    val held = setAgain.all { it.after == it.saved }
                    _events.send(BoostEvent.ShowToast(if (held) "Put back the background setting of $names after the restart" else "Could not put back the background setting of $names after the restart"))
                }
                return@launch
            }
        }
    }

    /**
     * On app start: finishes a session the app, the game or the phone went down in the middle
     * of, through the same [SessionEnd.finish] every other path uses. That is one left
     * "ending", or one still open with neither the monitor nor its watcher running; an open
     * session whose watcher still runs is left to the watcher. With no session on record,
     * tweaks or a restriction still held are put back as before.
     *
     * Shizuku can take a few seconds to bind after launch, so this waits for it.
     */
    fun revertStaleTweaks() {
        if (staleRevertStarted) return
        staleRevertStarted = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            repeat(STALE_REVERT_ATTEMPTS) {
                val (tweaks, restrictions) = withContext(Dispatchers.IO) {
                    PerformanceTuner.isActive(tunerStore) to BackgroundRestrictor.isActive(restrictionStore)
                }
                val open = SessionEnd.state(sessionStore).let { it == SessionEnd.STATE_ACTIVE || it == SessionEnd.STATE_ENDING }
                if (!(tweaks || restrictions || open) || GameMonitorService.isRunning) return@launch
                // Asked with Shizuku up, so a watcher that cannot be checked is never taken for gone.
                var nothingToDo = false
                val outcome = withContext(Dispatchers.IO) {
                    val shell = RamCleaner.shell
                    when {
                        !shell.isAvailable() -> null
                        !SessionEnd.needsFinishAtStart(sessionStore, tweaks, restrictions, GameMonitorService.isRunning) {
                            SessionWatcher.isAlive(shell).ok
                        } -> null.also { nothingToDo = true }
                        else -> SessionEnd.finish(SessionContext.of(app), EndReason.APP_START)
                    }
                }
                if (nothingToDo) return@launch
                if (outcome == null) {
                    delay(2000)
                    return@repeat
                }
                noteRestored(outcome.restrictions)
                if (outcome.ok) {
                    val what = listOfNotNull("display settings".takeIf { tweaks }, "background apps".takeIf { restrictions })
                    if (what.isNotEmpty()) _events.send(BoostEvent.ShowToast("Restored your original ${what.joinToString(" and ")}"))
                    return@launch
                }
                delay(2000)
            }
        }
    }
}
