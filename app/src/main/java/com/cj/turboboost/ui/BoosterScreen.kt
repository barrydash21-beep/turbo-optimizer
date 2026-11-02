package com.cj.turboboost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cj.turboboost.AppPackages
import com.cj.turboboost.BackgroundRestrictor
import com.cj.turboboost.BoostProfile
import com.cj.turboboost.CheckStatus
import com.cj.turboboost.Contamination
import com.cj.turboboost.DiagnosticsUi
import com.cj.turboboost.DisplayState
import com.cj.turboboost.EndReason
import com.cj.turboboost.FpsCap
import com.cj.turboboost.GameRegistry
import com.cj.turboboost.GameStateRef
import com.cj.turboboost.GameResolutionUi
import com.cj.turboboost.GroupStats
import com.cj.turboboost.HeavyAppsUi
import com.cj.turboboost.InstalledGame
import com.cj.turboboost.LaunchableApp
import com.cj.turboboost.RamInfo
import com.cj.turboboost.RefreshRate
import com.cj.turboboost.ResolutionLevel
import com.cj.turboboost.RestrictEditorUi
import com.cj.turboboost.SessionComparison
import com.cj.turboboost.SessionRecord
import com.cj.turboboost.SessionStore
import com.cj.turboboost.SystemTelemetry
import com.cj.turboboost.VpnState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// State models
// ---------------------------------------------------------------------------

enum class ShizukuState { OFFLINE, NEEDS_PERMISSION, READY }

enum class BoostStep(val label: String) {
    IDLE("READY"),
    CLEANING("PURGING RAM"),
    STABILIZING("LOCKING PING"),
    LAUNCHING("LAUNCHING")
}

// ---------------------------------------------------------------------------
// Main Screen
// ---------------------------------------------------------------------------

@Composable
fun BoosterScreen(
    shizuku: ShizukuState,
    vpnState: VpnState,
    step: BoostStep,
    profile: BoostProfile,
    vpnEnabled: Boolean,
    games: List<InstalledGame>?,
    pickerOpen: Boolean,
    pickerApps: List<LaunchableApp>?,
    selectedGame: String?,
    rates: Map<String, Float>,
    summary: SessionRecord?,
    summarySuggestion: Float?,
    onBoost: () -> Unit,
    onMeasure: () -> Unit,
    onStopVpn: () -> Unit,
    onRestore: () -> Unit,
    onProfileChange: (BoostProfile) -> Unit,
    onVpnEnabledChange: (Boolean) -> Unit,
    onSelectGame: (String) -> Unit,
    onRateChange: (String, Float) -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onAddGame: (String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onDismissSummary: () -> Unit,
    onApplySuggestion: () -> Unit,
    resolutionSupported: Boolean,
    gameResolution: Map<String, GameResolutionUi>,
    onPickResolution: (String, ResolutionLevel?) -> Unit,
    onPickFpsCap: (String, FpsCap?) -> Unit,
    onRestoreResolutionAnyway: (String) -> Unit,
    onRestartGame: (String) -> Unit,
    displayOverride: DisplayState?,
    onResetDisplayOverride: () -> Unit,
    onKeepDisplayOverride: () -> Unit,
    restrictEnabled: Boolean,
    restrictShown: List<String>,
    restrictEditor: RestrictEditorUi?,
    restrictOutcomes: Map<String, String>,
    onRestrictEnabledChange: (Boolean) -> Unit,
    onOpenRestrictEditor: () -> Unit,
    onCloseRestrictEditor: () -> Unit,
    onAddRestricted: (String) -> Unit,
    onRemoveRestricted: (String) -> Unit,
    onResetRestricted: () -> Unit,
    diagnostics: DiagnosticsUi,
    onRunDiagnostics: () -> Unit,
    diagnosticsReport: () -> String?,
    comparisonSessions: List<SessionRecord>?,
    onLoadComparison: () -> Unit,
    heavyApps: HeavyAppsUi,
    onRefreshHeavyApps: () -> Unit,
    onStopHeavyApp: (String) -> Unit,
    onRestrictHeavyApp: (String) -> Unit,
    onClearRam: () -> Unit
) {
    val context = LocalContext.current
    val busy = step != BoostStep.IDLE
    val accent = if (shizuku == ShizukuState.READY) Tac.Cyan else Tac.Amber
    val selected = games?.firstOrNull { it.profile.packageName == selectedGame }
    /** The game whose "Restart now" is waiting for confirmation. */
    var restartTarget by rememberSaveable { mutableStateOf<String?>(null) }

    // Polling is scoped to STARTED so neither the 3 s memory read nor the 2 s network probe
    // keeps waking the radio while the user is in the game (L3/L4).
    // The non-deprecated replacement lives in lifecycle-runtime-compose, which this module
    // does not depend on; this one behaves identically.
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh rate is polled alongside RAM rather than remembered once, so the DISPLAY tile
    // updates after the boost changes the rate (L1).
    val deviceStats by produceState<Pair<RamInfo, Float>?>(null, context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = SystemTelemetry.getRamInfo(context) to SystemTelemetry.getRefreshRate(context)
                delay(3000)
            }
        }
    }
    val reachability by produceState(-1, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            SystemTelemetry.getReachabilityMs().collect { value = it }
        }
    }

    // Each section is built once and placed by the layout below, wide or narrow.
    val gamesSection: @Composable () -> Unit = {
        GameSelector(
            games = games,
            selectedGame = selectedGame,
            rates = rates,
            enabled = !busy,
            onSelectGame = onSelectGame,
            onRateChange = onRateChange,
            onAddGame = onOpenPicker,
            onRemoveGame = onRemoveGame,
            resolution = if (resolutionSupported) gameResolution else null,
            shizukuReady = shizuku == ShizukuState.READY,
            onPickResolution = onPickResolution,
            onPickFpsCap = onPickFpsCap,
            onRestoreResolutionAnyway = onRestoreResolutionAnyway,
            onRestartGame = { restartTarget = it }
        )
    }
    val optionsSection: @Composable () -> Unit = {
        OptionsModule(
            profile = profile,
            vpnEnabled = vpnEnabled,
            restrictEnabled = restrictEnabled,
            enabled = !busy,
            onProfileChange = onProfileChange,
            onVpnEnabledChange = onVpnEnabledChange,
            onRestrictEnabledChange = onRestrictEnabledChange,
            onEditRestricted = onOpenRestrictEditor
        )
    }
    val statusCards: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InteractiveStatusCard(
                label = "SHIZUKU",
                state = shizuku,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            InteractiveStatusCard(
                label = "STABILIZER",
                vpn = vpnState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onStop = onStopVpn,
                showStop = !busy
            )
        }
    }
    val diagnosticsSection: @Composable () -> Unit = {
        DiagnosticsSection(ui = diagnostics, onRun = onRunDiagnostics, report = diagnosticsReport)
    }
    val comparisonSection: @Composable () -> Unit = {
        SessionComparisonSection(
            gamePackage = selectedGame,
            gameLabel = selected?.label,
            sessions = comparisonSessions,
            onOpen = onLoadComparison
        )
    }
    // One read when Shizuku becomes ready, then only when the user asks.
    LaunchedEffect(shizuku) { if (shizuku == ShizukuState.READY) onRefreshHeavyApps() }
    val heavySection: @Composable () -> Unit = {
        HeavyAppsCard(
            ui = heavyApps,
            ram = deviceStats?.first,
            shizukuReady = shizuku == ShizukuState.READY,
            enabled = !busy,
            onRefresh = onRefreshHeavyApps,
            onStop = onStopHeavyApp,
            onRestrict = onRestrictHeavyApp,
            onClearRam = onClearRam
        )
    }
    val statTiles: @Composable () -> Unit = {
        TelemetryDashboard(
            ram = deviceStats?.first,
            reachabilityMs = reachability,
            hz = deviceStats?.second?.let { RefreshRate.display(it) },
            // No live source is wired for these yet; they read N/A rather than a guess.
            cpuPercent = null,
            gpuPercent = null,
            fps = null,
            thermalStatus = null
        )
    }
    // Always reachable: the system changes a boost makes (DND, animation scales,
    // refresh rate) outlive the VPN, so the exit must not be gated on it.
    val restore: @Composable () -> Unit = { RestoreButton(enabled = !busy, onClick = onRestore) }

    Box(Modifier.fillMaxSize().background(Tac.BgBrush)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            if (wide) {
                var tabName by rememberSaveable { mutableStateOf(RailTab.OVERVIEW.name) }
                val tab = RailTab.entries.firstOrNull { it.name == tabName } ?: RailTab.OVERVIEW
                Row(Modifier.fillMaxSize()) {
                    IconRail(selected = tab, onSelect = { tabName = it.name })
                    // Left: pick, boost, undo. Right: the section the rail selects.
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Header()
                        Spacer(Modifier.height(12.dp))
                        BoostButton(step = step, accent = accent, gameName = selected?.profile?.displayName, onClick = onBoost, diameter = 210.dp)
                        Spacer(Modifier.height(8.dp))
                        StepTracker(current = step, includeStabilizing = vpnEnabled)
                        Spacer(Modifier.height(12.dp))
                        gamesSection()
                        Spacer(Modifier.height(10.dp))
                        MeasureButton(enabled = !busy && selected != null, onClick = onMeasure)
                        Spacer(Modifier.height(4.dp))
                        restore()
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (tab) {
                            RailTab.OVERVIEW -> {
                                statTiles()
                                statusCards()
                                heavySection()
                            }
                            RailTab.OPTIONS -> optionsSection()
                            RailTab.REPORTS -> {
                                diagnosticsSection()
                                comparisonSection()
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Header()
                    statTiles()
                    gamesSection()
                    BoostButton(step = step, accent = accent, gameName = selected?.profile?.displayName, onClick = onBoost)
                    MeasureButton(enabled = !busy && selected != null, onClick = onMeasure)
                    StepTracker(current = step, includeStabilizing = vpnEnabled)
                    optionsSection()
                    statusCards()
                    heavySection()
                    restore()
                    diagnosticsSection()
                    comparisonSection()
                }
            }
        }

        if (pickerOpen) {
            AppPickerDialog(apps = pickerApps, onPick = onAddGame, onDismiss = onClosePicker)
        }

        if (restrictEditor != null) {
            RestrictListDialog(
                shown = restrictShown,
                editor = restrictEditor,
                outcomes = restrictOutcomes,
                onAdd = onAddRestricted,
                onRemove = onRemoveRestricted,
                onReset = onResetRestricted,
                onDismiss = onCloseRestrictEditor
            )
        }

        if (summary != null) {
            SessionSummary(
                record = summary,
                suggestion = summarySuggestion,
                onDismiss = onDismissSummary,
                onApplySuggestion = onApplySuggestion
            )
        }

        restartTarget?.let { pkg ->
            val label = games?.firstOrNull { it.profile.packageName == pkg }?.label ?: pkg
            RestartGameDialog(
                gameLabel = label,
                onConfirm = {
                    restartTarget = null
                    onRestartGame(pkg)
                },
                onDismiss = { restartTarget = null }
            )
        }

        if (displayOverride != null) {
            DisplayOverrideDialog(state = displayOverride, onReset = onResetDisplayOverride, onKeep = onKeepDisplayOverride)
        }
    }
}

// ---------------------------------------------------------------------------
// Render resolution: per-game downscale through GameManager, shown on each game card
// ---------------------------------------------------------------------------

/**
 * A game card's render resolution row: DEFAULT plus the presets, the measured result, and
 * anything that still needs the user (a restart, or a mode changed outside this app).
 */
@Composable
private fun GameResolutionRow(
    gameLabel: String,
    ui: GameResolutionUi?,
    shizukuReady: Boolean,
    enabled: Boolean,
    onPick: (ResolutionLevel?) -> Unit,
    onRestoreAnyway: () -> Unit,
    onRestart: () -> Unit
) {
    val status = ui?.status
    // No silent failure: every reason the row is off is spelled out.
    val reason = when {
        !shizukuReady -> "Needs Shizuku to read and change the game's mode."
        status == null -> "Reading the game's mode..."
        else -> status.unavailableReason
    }
    val usable = enabled && reason == null && ui?.busy != true
    /** The chip waiting for confirmation, by level name; [DEFAULT_CHOICE] for DEFAULT. */
    var pendingChoice by rememberSaveable { mutableStateOf<String?>(null) }

    pendingChoice?.let { choice ->
        val level = ResolutionLevel.entries.firstOrNull { it.name == choice }
        ResolutionConfirmDialog(
            gameLabel = gameLabel,
            level = level,
            keptCap = status?.cap,
            onConfirm = {
                pendingChoice = null
                onPick(level)
            },
            onDismiss = { pendingChoice = null }
        )
    }

    Text("RENDER RESOLUTION", color = Tac.TextLo, fontSize = 10.sp, letterSpacing = LabelTracking, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    SegmentedBar {
        val options = listOf<Triple<String, String?, ResolutionLevel?>>(Triple("DEFAULT", null, null)) +
            ResolutionLevel.entries.map { Triple(it.label, it.factor, it) }
        options.forEach { (label, factor, level) ->
            val isSelected = reason == null && status?.level == level
            ResolutionChip(
                label = label,
                factor = factor,
                selected = isSelected,
                enabled = usable,
                // Nothing changes until the dialog is confirmed; the selected chip asks nothing.
                onClick = { if (!isSelected) pendingChoice = level?.name ?: DEFAULT_CHOICE }
            )
        }
    }

    status?.result?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Tac.Cyan, fontSize = 11.sp, fontFamily = UiFont)
    }

    if (status?.pendingRestart == true) {
        Spacer(Modifier.height(8.dp))
        Text("Applies next time you open $gameLabel.", color = Tac.Amber, fontSize = 11.sp, lineHeight = 16.sp)
        ResolutionAction("RESTART NOW", enabled = usable, onClick = onRestart)
    }

    if (status?.resetByRestart == true) {
        Spacer(Modifier.height(8.dp))
        Text(
            "The phone restarted, so Android put $gameLabel back in its default mode. Turbo sets " +
                "${heldText(status.level, status.cap)} again when you launch the game from Turbo.",
            color = Tac.Amber,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    status?.changedOutside?.let { now ->
        Spacer(Modifier.height(8.dp))
        Text(
            "$gameLabel's game mode was changed outside Turbo (now $now). Turbo won't overwrite it unless you say so.",
            color = Tac.Amber,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        ResolutionAction("RESTORE ANYWAY", enabled = enabled && ui?.busy != true, onClick = onRestoreAnyway)
    }

    (reason?.let { if (shizukuReady && status != null) "Unavailable: $it" else it } ?: ui?.note)?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Tac.Amber, fontSize = 11.sp, lineHeight = 16.sp)
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Lowers the size $gameLabel renders at through Android's game mode; the screen itself " +
            "is not changed. The game picks its own size from it, so the result is measured " +
            "after its next launch.",
        color = Tac.TextLo,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Lower factors cut GPU work but look softer; the effect depends on the game's own render cap, so check the measured line.",
        color = Tac.TextLo,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}

/**
 * A game card's frame-rate cap row: OFF plus the caps, and the average FPS the last session at
 * the cap held actually measured. Shares the resolution row's state, reasons and warnings.
 */
@Composable
private fun FpsCapRow(
    gameLabel: String,
    ui: GameResolutionUi?,
    shizukuReady: Boolean,
    enabled: Boolean,
    onPick: (FpsCap?) -> Unit
) {
    val status = ui?.status
    // Why it is off is spelled out once, in the resolution row above.
    val usable = enabled && shizukuReady && status != null && status.unavailableReason == null && ui?.busy != true
    /** The chip waiting for confirmation, by cap name; [OFF_CHOICE] for OFF. */
    var pendingChoice by rememberSaveable { mutableStateOf<String?>(null) }

    pendingChoice?.let { choice ->
        val cap = FpsCap.entries.firstOrNull { it.name == choice }
        FpsCapConfirmDialog(
            gameLabel = gameLabel,
            cap = cap,
            keptLevel = status?.level,
            onConfirm = {
                pendingChoice = null
                onPick(cap)
            },
            onDismiss = { pendingChoice = null }
        )
    }

    Text("THERMAL FPS CAP", color = Tac.TextLo, fontSize = 10.sp, letterSpacing = LabelTracking, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    SegmentedBar {
        val options = listOf<Pair<String, FpsCap?>>("OFF" to null) + FpsCap.entries.map { it.label to it }
        options.forEach { (label, cap) ->
            val isSelected = status != null && status.unavailableReason == null && status.cap == cap
            ResolutionChip(
                label = label,
                factor = cap?.let { "FPS" },
                selected = isSelected,
                enabled = usable,
                onClick = { if (!isSelected) pendingChoice = cap?.name ?: OFF_CHOICE }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Caps frames to cut heat. Can't raise FPS above the game's own setting. Takes effect on next launch.",
        color = Tac.TextLo,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    status?.cap?.let { cap ->
        Spacer(Modifier.height(4.dp))
        val measured = ui?.capSession?.avgFps
        Text(
            measured?.let { "Last session at ${cap.label}: ${fpsText(it)} FPS average" } ?: "Not measured yet",
            color = Tac.Cyan,
            fontSize = 11.sp,
            fontFamily = UiFont
        )
        // Only while the game holds exactly what Turbo set; otherwise the resolution row says what changed.
        if (status.changedOutside == null && !status.resetByRestart) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Android reads back Fps ${cap.label} for $gameLabel. That does not prove the game follows it; " +
                    "the measured average after a session does.",
                color = Tac.TextLo,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

private const val OFF_CHOICE = "OFF"

/** A cap chip changes the game's mode like a resolution chip: asked every time, never applied on tap. */
@Composable
private fun FpsCapConfirmDialog(
    gameLabel: String,
    cap: FpsCap?,
    /** The preset that stays; OFF without one is a full restore. */
    keptLevel: ResolutionLevel?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = cap?.let { "SET ${it.label} FPS CAP?" } ?: "TURN FPS CAP OFF?"
    val body = when {
        cap != null ->
            "Caps $gameLabel at ${cap.label} FPS through Android's game mode, to cut heat. It can't raise FPS above " +
                "the game's own setting, and the game may ignore it. It takes effect the next time $gameLabel opens." +
                (keptLevel?.let { " The ${it.label} ${it.factor} resolution stays." } ?: "")
        keptLevel != null -> "Removes the frame-rate cap for $gameLabel. The ${keptLevel.label} ${keptLevel.factor} resolution stays."
        else -> "Puts back the game mode and settings $gameLabel had before Turbo changed them."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tac.Surface,
        title = {
            Text(title, color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            Text(body, color = Tac.TextLo, fontSize = 12.sp, lineHeight = 17.sp)
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Tac.Cyan), shape = RoundedCornerShape(10.dp)) {
                Text(if (cap == null) "TURN OFF" else "APPLY", color = Tac.Bg, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}

@Composable
private fun ResolutionAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text(
            label,
            color = if (enabled) Tac.Amber else Tac.TextLo,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            fontFamily = UiFont
        )
    }
}

@Composable
private fun RowScope.ResolutionChip(
    label: String,
    factor: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Segment(
        selected = selected,
        enabled = enabled,
        description = "$label${factor?.let { " $it" }.orEmpty()}${if (selected) ", selected" else ""}",
        onClick = onClick
    ) { tint ->
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont, maxLines = 1)
        factor?.let { Text(it, color = tint, fontSize = 10.sp, fontFamily = UiFont, maxLines = 1) }
    }
}

private const val DEFAULT_CHOICE = "DEFAULT"

/** `LOW`, `the 40 FPS cap`, `LOW and the 40 FPS cap`, or `the preset`. */
private fun heldText(level: ResolutionLevel?, cap: FpsCap?): String =
    listOfNotNull(level?.label, cap?.let { "the ${it.label} FPS cap" }).joinToString(" and ").ifEmpty { "the preset" }

/** A resolution chip changes the game's mode: asked every time, never applied on tap. */
@Composable
private fun ResolutionConfirmDialog(
    gameLabel: String,
    level: ResolutionLevel?,
    /** The cap that stays when the resolution changes; DEFAULT is then not a full restore. */
    keptCap: FpsCap?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = level?.let { "SET ${it.label} ${it.factor}?" } ?: "RESTORE DEFAULT?"
    val body = if (level == null && keptCap != null) {
        "Turns the downscale off for $gameLabel. The ${keptCap.label} FPS cap stays until you set it to OFF."
    } else if (level == null) {
        "Puts back the game mode and settings $gameLabel had before Turbo changed them."
    } else {
        "Sets the ${level.factor} downscale factor for $gameLabel through Android's game mode. " +
            "Lower can look softer. It takes effect the next time $gameLabel opens, and the card then shows the size it actually renders at."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tac.Surface,
        title = {
            Text(title, color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            Text(body, color = Tac.TextLo, fontSize = 12.sp, lineHeight = 17.sp)
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Tac.Cyan), shape = RoundedCornerShape(10.dp)) {
                Text(if (level == null) "RESTORE" else "APPLY", color = Tac.Bg, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}

/** "Restart now" closes the game: asked every time, never done on its own. */
@Composable
private fun RestartGameDialog(gameLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tac.Surface,
        title = {
            Text("RESTART $gameLabel?", color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            Text(
                "This closes $gameLabel right away and opens it again with the new resolution. " +
                    "Anything unsaved, including a match in progress, may be lost.",
                color = Tac.TextLo,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Tac.Amber), shape = RoundedCornerShape(10.dp)) {
                Text("CLOSE AND RESTART", color = Tac.Bg, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}

/** This build's one-time question about a display-wide override. Not dismissable: it needs an answer. */
@Composable
private fun DisplayOverrideDialog(state: DisplayState, onReset: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Tac.Surface,
        title = {
            Text("DISPLAY OVERRIDE", color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            Text(
                "A display override is active. Reset to device default?\n\n" +
                    "Now: ${state.size} @ ${state.density} dpi\n" +
                    "Device default: ${state.physicalSize} @ ${state.physicalDensity} dpi",
                color = Tac.TextLo,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        },
        confirmButton = {
            Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = Tac.Cyan), shape = RoundedCornerShape(10.dp)) {
                Text("RESET", color = Tac.Bg, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("KEEP", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// Options module: performance profile + VPN opt-in
// ---------------------------------------------------------------------------

@Composable
private fun OptionsModule(
    profile: BoostProfile,
    vpnEnabled: Boolean,
    restrictEnabled: Boolean,
    enabled: Boolean,
    onProfileChange: (BoostProfile) -> Unit,
    onVpnEnabledChange: (Boolean) -> Unit,
    onRestrictEnabledChange: (Boolean) -> Unit,
    onEditRestricted: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "PROFILE",
                color = Tac.TextLo,
                fontSize = 10.sp,
                letterSpacing = LabelTracking,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(12.dp))

            SegmentedBar {
                BoostProfile.entries.forEach { entry ->
                    ProfileChip(
                        profile = entry,
                        selected = entry == profile,
                        enabled = enabled,
                        onClick = { onProfileChange(entry) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                profile.blurb,
                color = Tac.TextLo,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            // Applies to every profile: force-stopping an app also stops its alarms, jobs
            // and notifications until it is opened by hand (M12).
            Spacer(Modifier.height(8.dp))
            Text(
                "Every boost closes your other running apps (and some Transsion bloat), and " +
                    "holds the screen at the rate you picked for the game until it closes. " +
                    "Closed apps won't notify you again until you reopen them.",
                color = Tac.TextLo,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Tac.Line, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "PING STABILIZER",
                        color = Tac.TextHi,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = UiFont
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // L12: the DNS redirect is a side effect worth stating, since it
                        // applies to every app that is not exempted from the tunnel.
                        if (vpnEnabled) {
                            "Blocks background traffic. Routes DNS through Cloudflare while active."
                        } else {
                            "Left untouched"
                        },
                        color = Tac.TextLo,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = "Ping stabilizer" },
                    checked = vpnEnabled,
                    onCheckedChange = onVpnEnabledChange,
                    enabled = enabled,
                    colors = optionSwitchColors()
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Tac.Line, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "RESTRICT BACKGROUND APPS",
                        color = Tac.TextHi,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = UiFont
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (restrictEnabled) {
                            "During the session, the apps on your list can't run in the background. " +
                                "Put back as they were when the game closes."
                        } else {
                            "Left untouched"
                        },
                        color = Tac.TextLo,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    TextButton(
                        onClick = onEditRestricted,
                        enabled = enabled,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("EDIT LIST", color = Tac.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
                    }
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = "Restrict background apps during session" },
                    checked = restrictEnabled,
                    onCheckedChange = onRestrictEnabledChange,
                    enabled = enabled,
                    colors = optionSwitchColors()
                )
            }
        }
    }
}

@Composable
private fun optionSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Tac.Bg,
    checkedTrackColor = Tac.Cyan,
    uncheckedThumbColor = Tac.TextLo,
    uncheckedTrackColor = Tac.SurfaceElevated,
    uncheckedBorderColor = Tac.Line
)

/**
 * The background restriction list. Removing works any time; adding needs Shizuku, since only
 * it can say what is installed and what is protected, and a protected app is never accepted.
 */
@Composable
private fun RestrictListDialog(
    shown: List<String>,
    editor: RestrictEditorUi,
    outcomes: Map<String, String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val installed = editor.installed
    val protected = editor.protected
    var query by rememberSaveable { mutableStateOf("") }
    // A package added or removed: start the next one from an empty field.
    LaunchedEffect(shown) { query = "" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tac.Surface,
        title = {
            Text("BACKGROUND APPS", color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Kept from running in the background during a session, then put back exactly as they were. " +
                        "This app, Shizuku, the game, your launcher, keyboard, SMS app and dialer, system UI and " +
                        "Play services are protected and never restricted.",
                    color = Tac.TextLo,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))

                if (shown.isEmpty()) Text("No apps on the list.", color = Tac.TextLo, fontSize = 12.sp)
                shown.forEach { pkg ->
                    val status = when {
                        installed != null && pkg !in installed -> "not installed"
                        protected != null && AppPackages.isProtected(pkg, protected) -> "protected, never restricted"
                        else -> outcomes[pkg]
                    }
                    RestrictRow(
                        title = GameRegistry.labelFor(context, pkg) ?: pkg,
                        packageName = pkg,
                        status = status,
                        action = "REMOVE",
                        onAction = { onRemove(pkg) }
                    )
                }

                if (installed != null && AppPackages.HIOS_AI_SERVICE in installed && AppPackages.HIOS_AI_SERVICE !in shown) {
                    RestrictRow(
                        title = "Optional: HiOS AIService",
                        packageName = AppPackages.HIOS_AI_SERVICE,
                        status = "A HiOS system service. HiOS may start it again right away.",
                        action = "ADD",
                        onAction = { onAdd(AppPackages.HIOS_AI_SERVICE) }
                    )
                }

                Spacer(Modifier.height(12.dp))
                when {
                    editor.loading -> Text("Checking installed apps...", color = Tac.TextLo, fontSize = 12.sp)
                    installed != null -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.trim() },
                            label = { Text("Package name", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = UiFont),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Tac.TextHi,
                                unfocusedTextColor = Tac.TextHi,
                                focusedBorderColor = Tac.Cyan,
                                unfocusedBorderColor = Tac.Line,
                                focusedLabelColor = Tac.Cyan,
                                unfocusedLabelColor = Tac.TextLo,
                                cursorColor = Tac.Cyan
                            )
                        )
                        // Matching installed apps that could be added, so names need not be typed in full.
                        if (query.length >= 2) {
                            installed
                                .filter { it.contains(query, ignoreCase = true) && it !in shown }
                                .filter { protected == null || !AppPackages.isProtected(it, protected) }
                                .sorted()
                                .take(5)
                                .forEach { match ->
                                    Text(
                                        match,
                                        color = Tac.TextHi,
                                        fontSize = 12.sp,
                                        fontFamily = UiFont,
                                        modifier = Modifier.fillMaxWidth().clickable { onAdd(match) }.padding(vertical = 6.dp)
                                    )
                                }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onAdd(query) },
                            enabled = query.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Tac.Cyan),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("ADD", color = Tac.Bg, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
                        }
                    }
                }
                editor.note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Tac.Amber, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text("DEFAULTS", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}

@Composable
private fun RestrictRow(title: String, packageName: String, status: String?, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tac.TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (title != packageName) Text(packageName, color = Tac.TextLo, fontSize = 11.sp, fontFamily = UiFont)
            if (status != null) Text(status, color = Tac.TextLo, fontSize = 11.sp, lineHeight = 15.sp)
        }
        TextButton(onClick = onAction) {
            Text(action, color = Tac.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        }
    }
}

@Composable
private fun RowScope.ProfileChip(
    profile: BoostProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Segment(
        selected = selected,
        enabled = enabled,
        description = "${profile.label} profile. ${profile.blurb}",
        onClick = onClick
    ) { tint ->
        Text(profile.label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = UiFont, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Game selection: one card per game the user added
// ---------------------------------------------------------------------------

@Composable
private fun GameSelector(
    games: List<InstalledGame>?,
    selectedGame: String?,
    rates: Map<String, Float>,
    enabled: Boolean,
    onSelectGame: (String) -> Unit,
    onRateChange: (String, Float) -> Unit,
    onAddGame: () -> Unit,
    onRemoveGame: (String) -> Unit,
    /** Per-game render resolution; null hides it (Android 13 and older). */
    resolution: Map<String, GameResolutionUi>?,
    shizukuReady: Boolean,
    onPickResolution: (String, ResolutionLevel?) -> Unit,
    onPickFpsCap: (String, FpsCap?) -> Unit,
    onRestoreResolutionAnyway: (String) -> Unit,
    onRestartGame: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "GAME",
            color = Tac.TextLo,
            fontSize = 10.sp,
            letterSpacing = LabelTracking,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(10.dp))
        when {
            games == null -> Text("Looking for installed games...", color = Tac.TextLo, fontSize = 12.sp)
            games.isEmpty() -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Tac.Surface,
                border = BorderStroke(1.dp, Tac.Line)
            ) {
                Text(
                    "No games added yet. Tap ADD GAME and pick one from your installed apps.",
                    modifier = Modifier.padding(16.dp),
                    color = Tac.TextLo,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                games.forEach { game ->
                    val pkg = game.profile.packageName
                    GameCard(
                        game = game,
                        selected = pkg == selectedGame,
                        rate = rates[pkg],
                        enabled = enabled,
                        onSelect = { onSelectGame(pkg) },
                        onRateChange = { onRateChange(pkg, it) },
                        onRemove = { onRemoveGame(pkg) },
                        showResolution = resolution != null,
                        resolution = resolution?.get(pkg),
                        shizukuReady = shizukuReady,
                        onPickResolution = { onPickResolution(pkg, it) },
                        onPickFpsCap = { onPickFpsCap(pkg, it) },
                        onRestoreResolutionAnyway = { onRestoreResolutionAnyway(pkg) },
                        onRestart = { onRestartGame(pkg) }
                    )
                }
            }
        }
        if (games != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onAddGame,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Tac.Line)
            ) {
                Text("+ ADD GAME", color = Tac.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    }
}

/** Lists every launchable app; tapping one adds it as a game. */
@Composable
private fun AppPickerDialog(apps: List<LaunchableApp>?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tac.Surface,
        title = {
            Text("ADD A GAME", color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            when {
                apps == null -> Text("Loading installed apps...", color = Tac.TextLo, fontSize = 12.sp)
                apps.isEmpty() -> Text("No other apps to add.", color = Tac.TextLo, fontSize = 12.sp)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app.packageName) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                            } else {
                                Box(Modifier.size(32.dp).background(Tac.SurfaceElevated, RoundedCornerShape(8.dp)))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, color = Tac.TextHi, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}

@Composable
private fun GameCard(
    game: InstalledGame,
    selected: Boolean,
    rate: Float?,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRateChange: (Float) -> Unit,
    onRemove: () -> Unit,
    showResolution: Boolean,
    resolution: GameResolutionUi?,
    shizukuReady: Boolean,
    onPickResolution: (ResolutionLevel?) -> Unit,
    onPickFpsCap: (FpsCap?) -> Unit,
    onRestoreResolutionAnyway: () -> Unit,
    onRestart: () -> Unit
) {
    val border by animateColorAsState(if (selected) Tac.Cyan else Tac.Line, label = "gameBorder")
    val bg by animateColorAsState(
        if (selected) Tac.Cyan.copy(alpha = 0.08f) else Tac.Surface,
        label = "gameBg"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = "${game.label}${if (selected) ", selected" else ""}" },
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (game.icon != null) {
                    Image(
                        bitmap = game.icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(Modifier.size(40.dp).background(Tac.SurfaceElevated, RoundedCornerShape(10.dp)))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    game.label,
                    modifier = Modifier.weight(1f),
                    color = Tac.TextHi,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (selected) {
                    Text("SELECTED", color = Tac.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
                }
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text("REMOVE", color = Tac.TextLo, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
                }
            }

            Spacer(Modifier.height(12.dp))
            SegmentedBar {
                game.supportedRates.forEach { option ->
                    RateChip(
                        rate = option,
                        selected = option == rate,
                        enabled = enabled,
                        onClick = { onRateChange(option) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // The app pins the display's refresh rate; the game decides its own frame rate.
            Text(
                "Also set ${rate?.let { "${RefreshRate.display(it)} FPS" } ?: "the matching frame rate"} " +
                    "in ${game.label}'s own graphics settings. This app sets the display " +
                    "refresh rate only; it can't force the game's frame rate.",
                color = Tac.TextLo,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            if (showResolution) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Tac.Line, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))
                GameResolutionRow(
                    gameLabel = game.label,
                    ui = resolution,
                    shizukuReady = shizukuReady,
                    enabled = enabled,
                    onPick = onPickResolution,
                    onRestoreAnyway = onRestoreResolutionAnyway,
                    onRestart = onRestart
                )
                Spacer(Modifier.height(12.dp))
                FpsCapRow(
                    gameLabel = game.label,
                    ui = resolution,
                    shizukuReady = shizukuReady,
                    enabled = enabled,
                    onPick = onPickFpsCap
                )
            }
        }
    }
}

@Composable
private fun RowScope.RateChip(rate: Float, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val hz = RefreshRate.display(rate)
    Segment(
        selected = selected,
        enabled = enabled,
        description = "$hz hertz${if (selected) ", selected" else ""}",
        onClick = onClick
    ) { tint ->
        Text("$hz HZ", color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Session summary: shown once after a game exits
// ---------------------------------------------------------------------------

private val THERMAL_NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

private fun thermalText(status: Int?): String =
    status?.let { "$it (${THERMAL_NAMES.getOrElse(it) { "unknown" }})" } ?: "unavailable"

private fun durationText(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "$min min $sec s" else "$sec s"
}

/** `LOW 0.6` or `default`; records from the removed display override hold `900x2050@400`. */
private fun renderResolutionText(value: String?): String = when {
    value == null -> "unavailable"
    value.contains('@') -> "display " + value.replace("@", ", ") + " dpi"
    else -> value
}

private fun fpsText(fps: Float?): String = fps?.let { String.format(Locale.US, "%.1f", it) } ?: "unavailable"

private fun tempText(celsius: Float): String = String.format(Locale.US, "%.1f °C", celsius)

private fun profileText(profile: String?): String = when (profile) {
    null -> "not recorded"
    SessionRecord.BASELINE -> "baseline, nothing changed"
    else -> BoostProfile.entries.firstOrNull { it.name == profile }?.label ?: profile
}

private fun contaminationText(raw: String?): String = when (Contamination.fromName(raw)) {
    Contamination.CLEAN -> "no"
    Contamination.CONTAMINATED -> "yes"
    Contamination.UNKNOWN -> "unknown: no earlier state to compare with"
    null -> "not checked"
}

private fun endReasonText(code: String?): String = when (EndReason.fromCode(code)) {
    EndReason.EXITED -> "game closed"
    EndReason.RESTARTED -> "game restarted, not compared"
    EndReason.NEVER_STARTED -> "game never started"
    EndReason.MANUAL -> "RESTORE"
    EndReason.APP_START -> "at the next app start"
    EndReason.REPLACED -> "by a new boost"
    EndReason.SHIZUKU_LOST -> "Shizuku lost"
    null -> "not recorded"
}

@Composable
private fun SessionSummary(
    record: SessionRecord,
    suggestion: Float?,
    onDismiss: () -> Unit,
    onApplySuggestion: () -> Unit
) {
    val name = GameRegistry.labelFor(LocalContext.current, record.packageName) ?: record.packageName
    val rateHz = RefreshRate.display(record.refreshRate)
    val timeToThermal = when {
        record.msToThermal1 != null -> "${durationText(record.msToThermal1)} (30 s sampling)"
        record.maxThermal != null -> "never reached"
        else -> "unavailable"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tac.Bg.copy(alpha = 0.97f))
            // Swallow taps so nothing underneath is pressed through the overlay.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Tac.Surface,
            border = BorderStroke(1.dp, Tac.Line)
        ) {
            // Scrolls: in landscape the rows are taller than the screen and CLOSE ended up off it.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("SESSION SUMMARY", color = Tac.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = LabelTracking, fontFamily = UiFont)
                Spacer(Modifier.height(4.dp))
                Text(name, color = Tac.TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                SummaryRow("Session", profileText(record.profile))
                SummaryRow(if (record.isBaseline) "Refresh rate held" else "Refresh rate chosen", "$rateHz Hz")
                SummaryRow(
                    "Peak rate the system held",
                    record.observedPeak?.let { peak ->
                        val held = RefreshRate.display(peak)
                        if (held == rateHz) "$held Hz" else "$held Hz (HiOS overrode $rateHz)"
                    } ?: "unavailable"
                )
                SummaryRow("Render resolution", renderResolutionText(record.renderResolution))
                AliveRow("Restricted apps alive at ${BackgroundRestrictor.ALIVE_CHECK_DELAY_MS / 1000} s", record.restrictedAliveEarly)
                AliveRow("Restricted apps alive at end", record.restrictedAliveAtEnd)
                // Every session saved since these existed has a profile; one without predates them.
                val recorded = record.profile != null
                SummaryRow("Game mode at start", if (recorded) GameStateRef.display(record.gameStateStart) else "not recorded")
                SummaryRow("Game mode at end", if (recorded) GameStateRef.display(record.gameStateEnd) else "not recorded")
                SummaryRow("Changed by another app", contaminationText(record.contamination))
                SummaryRow("Duration", durationText(record.durationMs))
                SummaryRow("Ended", endReasonText(record.endReason))
                SummaryRow(
                    "Sampled",
                    when {
                        !recorded -> "not recorded"
                        record.continuity == null -> "too short to say"
                        else -> "${(record.continuity * 100).roundToInt()}% of the session"
                    }
                )
                SummaryRow("Average FPS", fpsText(record.avgFps))
                SummaryRow("1% low FPS", fpsText(record.onePercentLowFps))
                SummaryRow(
                    "Pauses of 1 s or more",
                    record.pauseCount?.let { n -> if (n == 0) "none" else "$n, ${durationText(record.pauseMs ?: 0L)} in all (not in FPS)" } ?: "not recorded"
                )
                SummaryRow(
                    "Battery temperature",
                    if (record.batteryTempStart == null && record.batteryTempEnd == null) {
                        "unavailable"
                    } else {
                        "${record.batteryTempStart?.let { tempText(it) } ?: "?"} to ${record.batteryTempEnd?.let { tempText(it) } ?: "?"}"
                    }
                )
                SummaryRow("Time to thermal status 1+", timeToThermal)
                SummaryRow("Max thermal status", thermalText(record.maxThermal))
                SummaryRow(
                    "Samples",
                    "${record.samples} (FPS in ${record.fpsSamples}, thermal in ${record.thermalSamples})"
                )

                if (record.baselineInvalid || record.contaminated || record.gameRestarted) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Left out of the Session comparison: " + listOfNotNull(
                            "the game held a Game Manager config, so it is not a baseline".takeIf { record.baselineInvalid },
                            "another app changed the game's mode or config".takeIf { record.contaminated },
                            "the game restarted mid-session".takeIf { record.gameRestarted }
                        ).joinToString("; ") + ".",
                        color = Tac.Amber,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                if (suggestion != null) {
                    Spacer(Modifier.height(14.dp))
                    val why = buildList {
                        if (record.avgFps != null && record.avgFps < record.refreshRate * 0.85f) {
                            add("average FPS was more than 15% below $rateHz")
                        }
                        if (record.maxThermal != null && record.maxThermal >= 2) add("the phone reached thermal status ${record.maxThermal}")
                    }.joinToString(" and ")
                    Text(
                        "This session struggled: $why. Try ${RefreshRate.display(suggestion)} Hz next time.",
                        color = Tac.Amber,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onApplySuggestion,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Tac.Amber),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "USE ${RefreshRate.display(suggestion)} HZ FOR ${name.uppercase(Locale.US)}",
                            color = Tac.Bg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = UiFont
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("CLOSE", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontFamily = UiFont)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Tac.TextLo, fontSize = 12.sp)
        Text(value, color = Tac.TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
    }
}

/** Null is "not checked" (restriction off, or unreadable); empty is "none"; packages go one per line. */
@Composable
private fun AliveRow(label: String, packages: List<String>?) {
    if (packages.isNullOrEmpty()) {
        SummaryRow(label, if (packages == null) "not checked" else "none")
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Tac.TextLo, fontSize = 12.sp)
        packages.forEach { Text(it, color = Tac.TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont) }
    }
}

// ---------------------------------------------------------------------------
// Diagnostics: read-only, probed only when opened or re-checked
// ---------------------------------------------------------------------------

/** A collapsible section header: the title and SHOW / HIDE. */
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide $title" else "Show $title", onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), color = Tac.TextLo, fontSize = 10.sp, letterSpacing = LabelTracking, fontWeight = FontWeight.Black)
        Text(if (expanded) "HIDE" else "SHOW", color = Tac.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
    }
}

private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.SUPPORTED -> Tac.Green
    CheckStatus.UNSUPPORTED -> Tac.Red
    CheckStatus.UNVERIFIED -> Tac.Amber
    CheckStatus.NOT_CHECKED -> Tac.TextLo
}

/**
 * What this phone, Shizuku and the selected game support, each with its evidence. Opening it
 * or RE-CHECK runs the probes once; COPY REPORT copies it as plain text.
 */
@Composable
private fun DiagnosticsSection(ui: DiagnosticsUi, onRun: () -> Unit, report: () -> String?) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("DIAGNOSTICS", expanded) {
                expanded = !expanded
                if (expanded) onRun()
            }
            if (!expanded) return@Column
            Spacer(Modifier.height(8.dp))
            Text(
                "Read-only. Supported means a read-back confirmed it. Unverified means the command ran but " +
                    "nothing confirmed the effect.",
                color = Tac.TextLo,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            val rows = ui.rows
            if (rows == null) {
                Text(if (ui.running) "Checking..." else "Not checked yet", color = Tac.TextLo, fontSize = 12.sp)
            } else {
                rows.forEach { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, modifier = Modifier.weight(1f), color = Tac.TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                row.status.label.uppercase(Locale.US),
                                color = statusColor(row.status),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = UiFont
                            )
                        }
                        Text(row.evidence, color = Tac.TextLo, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = UiFont)
                    }
                }
                ui.checkedAt?.let { Text("Checked $it", color = Tac.TextLo, fontSize = 10.sp, fontFamily = UiFont) }
            }
            Row {
                TextButton(onClick = onRun, enabled = !ui.running, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (ui.running) "CHECKING" else "RE-CHECK", color = if (ui.running) Tac.TextLo else Tac.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
                }
                Spacer(Modifier.width(16.dp))
                TextButton(
                    onClick = {
                        val text = report() ?: return@TextButton
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Turbo diagnostics", text))
                        // Android 13+ confirms a copy itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = rows != null,
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text("COPY REPORT", color = if (rows != null) Tac.Cyan else Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = UiFont)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session comparison: read-only, from the saved sessions
// ---------------------------------------------------------------------------

/**
 * Two groups of the selected game's saved sessions side by side, grouped by refresh rate,
 * render resolution and FPS cap. Medians only; a difference only with 3 runs in each group.
 */
@Composable
private fun SessionComparisonSection(
    gamePackage: String?,
    gameLabel: String?,
    sessions: List<SessionRecord>?,
    onOpen: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    /** The picked groups, by label. */
    var pickA by rememberSaveable { mutableStateOf<String?>(null) }
    var pickB by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("SESSION COMPARISON", expanded) {
                expanded = !expanded
                if (expanded) onOpen()
            }
            if (!expanded) return@Column
            Spacer(Modifier.height(8.dp))
            val groups = if (gamePackage != null && sessions != null) SessionComparison.groups(sessions, gamePackage) else null
            when {
                gamePackage == null -> Text("Pick a game to compare its sessions.", color = Tac.TextLo, fontSize = 12.sp)
                groups == null -> Text("Reading saved sessions...", color = Tac.TextLo, fontSize = 12.sp)
                groups.isEmpty() -> Text("No saved sessions for ${gameLabel ?: gamePackage} yet.", color = Tac.TextLo, fontSize = 12.sp)
                else -> {
                    val a = groups.firstOrNull { it.key.label == pickA } ?: groups[0]
                    val b = groups.firstOrNull { it.key.label == pickB } ?: groups.getOrElse(1) { groups[0] }
                    GroupPicker("A", groups, a) { pickA = it.key.label }
                    Spacer(Modifier.height(6.dp))
                    GroupPicker("B", groups, b) { pickB = it.key.label }
                    Spacer(Modifier.height(10.dp))
                    ComparisonTable(a, b)
                }
            }
            val left = if (gamePackage != null && sessions != null) SessionComparison.excludedCount(sessions, gamePackage) else 0
            if (left > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "$left ${if (left == 1) "session is" else "sessions are"} left out: the game restarted mid-session, " +
                        "another app changed its game mode, or a baseline ran with a Game Manager config.",
                    color = Tac.Amber,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Only the newest ${SessionStore.MAX_SESSIONS} sessions are kept. Each median uses only the runs " +
                    "that recorded it; (n/m) is how many did.",
                color = Tac.TextLo,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun GroupPicker(name: String, groups: List<GroupStats>, picked: GroupStats, onPick: (GroupStats) -> Unit) {
    Text("GROUP $name", color = Tac.TextLo, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black)
    groups.forEach { group ->
        val selected = group == picked
        Text(
            "${if (selected) "\u25CF" else "\u25CB"} ${group.key.label} (${group.runs} ${if (group.runs == 1) "run" else "runs"})",
            color = if (selected) Tac.Cyan else Tac.TextLo,
            fontSize = 11.sp,
            fontFamily = UiFont,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(group) })
                .padding(vertical = 4.dp)
        )
    }
}

private fun withRuns(value: String?, had: Int, of: Int): String = value?.let { "$it ($had/$of)" } ?: "no data"

private fun signed(value: Float): String = String.format(Locale.US, "%+.1f", value)

private fun signedDuration(ms: Long): String = (if (ms < 0) "-" else "+") + durationText(kotlin.math.abs(ms))

/** Metric rows for A and B, and B minus A only when both groups have 3 runs. */
@Composable
private fun ComparisonTable(a: GroupStats, b: GroupStats) {
    val diff = SessionComparison.difference(a, b)
    val short = listOf("A" to a, "B" to b).filter { !it.second.enoughRuns }
    val rows = listOf(
        Triple("Runs", "${a.runs}", "${b.runs}") to null,
        Triple(
            "Median avg FPS",
            withRuns(a.avgFps?.let(::fpsText), a.avgFpsRuns, a.runs),
            withRuns(b.avgFps?.let(::fpsText), b.avgFpsRuns, b.runs)
        ) to diff?.avgFps?.let(::signed),
        Triple(
            "Median 1% low",
            withRuns(a.onePercentLow?.let(::fpsText), a.onePercentLowRuns, a.runs),
            withRuns(b.onePercentLow?.let(::fpsText), b.onePercentLowRuns, b.runs)
        ) to diff?.onePercentLow?.let(::signed),
        Triple(
            "Median time to thermal 1",
            withRuns(a.msToThermal1?.let(::durationText), a.reachedThermal1, a.runs),
            withRuns(b.msToThermal1?.let(::durationText), b.reachedThermal1, b.runs)
        ) to diff?.msToThermal1?.let(::signedDuration),
        Triple("Never reached thermal 1", "${a.neverReachedThermal1}/${a.thermalRuns}", "${b.neverReachedThermal1}/${b.thermalRuns}") to null,
        Triple("Worst max thermal", thermalText(a.worstThermal), thermalText(b.worstThermal)) to null
    )
    Row(Modifier.fillMaxWidth()) {
        listOf("" to 1.3f, "A" to 1f, "B" to 1f, "B\u2212A" to 0.8f).forEach { (title, weight) ->
            Text(title, modifier = Modifier.weight(weight), color = Tac.TextLo, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = UiFont)
        }
    }
    rows.forEach { (values, delta) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(values.first, modifier = Modifier.weight(1.3f).padding(end = 8.dp), color = Tac.TextLo, fontSize = 11.sp, lineHeight = 15.sp)
            Text(values.second, modifier = Modifier.weight(1f), color = Tac.TextHi, fontSize = 11.sp, fontFamily = UiFont, lineHeight = 15.sp)
            Text(values.third, modifier = Modifier.weight(1f), color = Tac.TextHi, fontSize = 11.sp, fontFamily = UiFont, lineHeight = 15.sp)
            Text(delta ?: "", modifier = Modifier.weight(0.8f), color = Tac.Cyan, fontSize = 11.sp, fontFamily = UiFont, lineHeight = 15.sp)
        }
    }
    if (short.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "No difference shown: " + short.joinToString(", ") { (name, g) -> "$name ${g.runs}/${SessionComparison.MIN_RUNS} runs" },
            color = Tac.Amber,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

/** Launches the game under the monitor with nothing changed, for a baseline to compare boosts with. */
@Composable
private fun MeasureButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Measure only: launch the game and record a baseline session without changing anything"
            },
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            "MEASURE ONLY · NO CHANGES",
            fontSize = 10.sp,
            color = if (enabled) Tac.Cyan else Tac.TextLo.copy(alpha = 0.4f),
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontFamily = UiFont
        )
    }
}

@Composable
private fun RestoreButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Restore normal settings, undoing every change the boost made"
            },
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            "RESTORE NORMAL SETTINGS",
            fontSize = 10.sp,
            color = if (enabled) Tac.TextLo else Tac.TextLo.copy(alpha = 0.4f),
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontFamily = UiFont
        )
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "TURBO",
            color = Tac.Accent,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = UiFont,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )
        )
        SectionLabel("ENGINEERING DASHBOARD")
    }
}

/** Thermal state as a chip; null is "N/A", never a guessed level. */
@Composable
private fun ThermalPill(status: Int?) {
    val color = when {
        status == null -> Tac.TextLo
        status <= 1 -> Tac.Ok
        status == 2 -> Tac.Warn
        else -> Tac.Danger
    }
    val word = status?.let { THERMAL_NAMES.getOrElse(it) { "unknown" }.uppercase(Locale.US) } ?: "N/A"
    StatusPill("THERMAL $word", color)
}

/**
 * CPU, GPU, RAM and FPS as large tiles. A figure that has no source reads "N/A"; REACH and
 * DISPLAY stay below as smaller cards.
 */
@Composable
private fun TelemetryDashboard(
    ram: RamInfo?,
    reachabilityMs: Int,
    hz: Int?,
    cpuPercent: Int?,
    gpuPercent: Int?,
    fps: Int?,
    thermalStatus: Int?
) {
    val tile = Modifier.fillMaxHeight()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThermalPill(thermalStatus)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("CPU", cpuPercent?.toString(), tile.weight(1f), unit = "%")
            StatTile("GPU", gpuPercent?.toString(), tile.weight(1f), unit = "%")
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val used = ram?.used?.takeIf { it != "--" }
            StatTile("RAM", used, tile.weight(1f), unit = ram?.total?.let { "of $it" }, progress = ram?.fraction)
            StatTile("FPS", fps?.toString(), tile.weight(1f))
        }
        val reachColor = when {
            reachabilityMs < 0 -> Tac.TextLo
            reachabilityMs < 50 -> Tac.Green
            reachabilityMs < 100 -> Tac.Amber
            else -> Tac.Red
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Not a ping: this is round-trip reachability to a DNS host, measured outside the
            // tunnel, so it is labelled for what it is (L3).
            TelemetryCard(
                label = "REACH",
                value = if (reachabilityMs >= 0) "$reachabilityMs" else "--",
                subValue = "MS TO DNS",
                statusColor = reachColor,
                modifier = tile.weight(1f)
            )
            TelemetryCard(
                label = "DISPLAY",
                value = hz?.toString() ?: "--",
                subValue = "HZ RATE",
                modifier = tile.weight(1f)
            )
        }
    }
}

@Composable
private fun TelemetryCard(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier,
    statusColor: Color = Tac.Accent
) {
    SectionCard(modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Tac.TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).background(statusColor, CircleShape))
        }
        Text(subValue, color = Tac.TextLo, fontSize = 10.sp, letterSpacing = LabelTracking, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BoostButton(step: BoostStep, accent: Color, gameName: String?, onClick: () -> Unit, diameter: Dp = 250.dp) {
    val busy = step != BoostStep.IDLE
    val canBoost = !busy && gameName != null
    val launchLabel = if (gameName != null) "Boost and launch $gameName" else "Pick a game to boost"
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f, 
        spring(stiffness = Spring.StiffnessLow), 
        label = "scale"
    )
    
    val fx = rememberInfiniteTransition(label = "fx")
    val sweep by fx.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sweep")
    val glowPulse by fx.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(2500), RepeatMode.Reverse), label = "pulse")

    Box(
        Modifier
            .size(diameter)
            .scale(scale)
            .alpha(if (busy || canBoost) 1f else 0.45f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = canBoost,
                role = Role.Button,
                onClickLabel = launchLabel,
                onClick = onClick
            )
            .semantics {
                role = Role.Button
                contentDescription = if (busy) "Boosting: ${step.label}" else launchLabel
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val ringR = r - 30.dp.toPx()
            
            // Ambient Radial Button Glow
            drawCircle(
                Brush.radialGradient(
                    0f to accent.copy(alpha = 0.25f * glowPulse),
                    0.6f to accent.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                    center = center, radius = r
                ),
                radius = r
            )

            // Tactical Ring
            drawCircle(Tac.Line, radius = ringR, style = Stroke(4.dp.toPx()))
            
            if (busy) {
                rotate(sweep) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                        startAngle = 0f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(center.x - ringR, center.y - ringR),
                        size = Size(ringR * 2, ringR * 2),
                        style = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            } else {
                drawCircle(accent.copy(alpha = 0.5f), radius = ringR, style = Stroke(1.dp.toPx()))
            }
            
            // Central Reactor Core
            drawCircle(
                Brush.verticalGradient(listOf(Tac.SurfaceElevated, Tac.Surface)),
                radius = ringR - 8.dp.toPx()
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                Text(step.label, color = accent, fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
            } else {
                Text("BOOST", color = Tac.TextHi, fontFamily = UiFont, fontWeight = FontWeight.Black, fontSize = 38.sp, letterSpacing = 2.sp)
                Text(
                    if (gameName != null) "& PLAY" else "PICK A GAME",
                    color = accent,
                    fontFamily = UiFont,
                    fontSize = 13.sp,
                    letterSpacing = 5.sp
                )
            }
        }
    }
}

@Composable
private fun InteractiveStatusCard(
    label: String,
    modifier: Modifier = Modifier,
    state: ShizukuState? = null,
    vpn: VpnState? = null,
    onStop: (() -> Unit)? = null,
    showStop: Boolean = false
) {
    val (title, detail, color) = when {
        state != null -> when(state) {
            ShizukuState.READY -> Triple("LINKED", "Core Access Active", Tac.Green)
            ShizukuState.NEEDS_PERMISSION -> Triple("LOCKED", "Grant Permission", Tac.Amber)
            ShizukuState.OFFLINE -> Triple("OFFLINE", "Start Shizuku", Tac.Red)
        }
        // A failed tunnel used to read "STANDBY / System Ready", which does not look like
        // an error (M5).
        vpn != null -> when (vpn) {
            VpnState.RUNNING -> Triple("ACTIVE", "Traffic Routed", Tac.Cyan)
            VpnState.STARTING -> Triple("LINKING", "Opening Tunnel", Tac.Amber)
            VpnState.FAILED -> Triple("FAILED", "Another VPN may be active", Tac.Red)
            VpnState.STOPPED -> Triple("STANDBY", "System Ready", Tac.TextLo)
        }
        else -> Triple("---", "---", Tac.TextLo)
    }

    // Not clickable: the empty lambda made these announce as actionable to TalkBack and do
    // nothing (L5). The DISCONNECT button below is the only real control here.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Tac.TextLo, fontSize = 10.sp, letterSpacing = LabelTracking, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(color)
                Spacer(Modifier.width(10.dp))
                Text(title, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
            }
            Spacer(Modifier.height(4.dp))
            Text(detail, color = Tac.TextLo, fontSize = 11.sp, lineHeight = 16.sp)
            
            if (showStop && vpn == VpnState.RUNNING && onStop != null) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tac.SurfaceElevated),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Tac.Line)
                ) {
                    Text("DISCONNECT", fontSize = 9.sp, color = Tac.TextHi, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(color: Color) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "alpha"
    )
    Box(Modifier.size(8.dp).alpha(pulse).background(color, CircleShape))
}

@Composable
private fun StepTracker(current: BoostStep, includeStabilizing: Boolean) {
    // STABILIZING is skipped entirely when the stabilizer is off, so showing its segment
    // left a bar that never lights and reads as a stalled step (L7).
    val steps = BoostStep.entries.filter {
        it != BoostStep.IDLE && (includeStabilizing || it != BoostStep.STABILIZING)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEach { step ->
            val active = step == current
            val done = current != BoostStep.IDLE && step.ordinal < current.ordinal
            val color = when {
                done -> Tac.Green
                active -> Tac.Cyan
                else -> Tac.Line
            }
            
            Box(
                Modifier
                    .size(width = 65.dp, height = 4.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}
