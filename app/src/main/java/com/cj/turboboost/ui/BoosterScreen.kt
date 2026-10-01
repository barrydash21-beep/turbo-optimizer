package com.cj.turboboost.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cj.turboboost.BoostProfile
import com.cj.turboboost.GameRegistry
import com.cj.turboboost.InstalledGame
import com.cj.turboboost.LaunchableApp
import com.cj.turboboost.RamInfo
import com.cj.turboboost.RefreshRate
import com.cj.turboboost.SessionRecord
import com.cj.turboboost.SystemTelemetry
import com.cj.turboboost.VpnState
import kotlinx.coroutines.delay
import java.util.Locale

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
// Principal Palette & Theme
// ---------------------------------------------------------------------------

object Tac {
    val Bg = Color(0xFF0D1117)
    val Surface = Color(0xFF121824)
    val SurfaceElevated = Color(0xFF1A2234)
    val Line = Color(0xFF2D364D)
    
    val Cyan = Color(0xFF00F2FE)
    val Amber = Color(0xFFFFB020)
    val Green = Color(0xFF00E676)
    val Red = Color(0xFFFF3366)
    
    val TextHi = Color(0xFFE6EDF3)
    val TextLo = Color(0xFF8B949E)
}

private val Mono = FontFamily.Monospace

@Composable
fun TurboTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tac.Cyan,
            secondary = Tac.Amber,
            background = Tac.Bg,
            surface = Tac.Surface,
            onBackground = Tac.TextHi,
            onSurface = Tac.TextHi
        ),
        content = content
    )
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
    onApplySuggestion: () -> Unit
) {
    val context = LocalContext.current
    val busy = step != BoostStep.IDLE
    val accent = if (shizuku == ShizukuState.READY) Tac.Cyan else Tac.Amber
    val selected = games?.firstOrNull { it.profile.packageName == selectedGame }

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

    Box(
        Modifier
            .fillMaxSize()
            .background(Tac.Bg)
            .drawBehind {
                // Tactical Background Grid
                val gap = 45.dp.toPx()
                val gridColor = Tac.Line.copy(alpha = 0.12f)
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                    x += gap
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                    y += gap
                }
                
                // Ambient Center Glow
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Tac.Cyan.copy(alpha = 0.07f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.8f
                    )
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header()

            Spacer(Modifier.height(28.dp))

            TelemetryDashboard(
                ram = deviceStats?.first,
                reachabilityMs = reachability,
                hz = deviceStats?.second?.let { RefreshRate.display(it) }
            )

            Spacer(Modifier.height(28.dp))

            GameSelector(
                games = games,
                selectedGame = selectedGame,
                rates = rates,
                enabled = !busy,
                onSelectGame = onSelectGame,
                onRateChange = onRateChange,
                onAddGame = onOpenPicker,
                onRemoveGame = onRemoveGame
            )

            Spacer(Modifier.height(20.dp))

            BoostButton(
                step = step,
                accent = accent,
                gameName = selected?.profile?.displayName,
                onClick = onBoost
            )

            Spacer(Modifier.height(36.dp))

            StepTracker(current = step, includeStabilizing = vpnEnabled)

            Spacer(Modifier.height(28.dp))

            OptionsModule(
                profile = profile,
                vpnEnabled = vpnEnabled,
                enabled = !busy,
                onProfileChange = onProfileChange,
                onVpnEnabledChange = onVpnEnabledChange
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                InteractiveStatusCard(
                    label = "SHIZUKU",
                    state = shizuku,
                    modifier = Modifier.weight(1f)
                )
                InteractiveStatusCard(
                    label = "STABILIZER",
                    vpn = vpnState,
                    modifier = Modifier.weight(1f),
                    onStop = onStopVpn,
                    showStop = !busy
                )
            }
            
            Spacer(Modifier.height(12.dp))

            // Always reachable: the system changes a boost makes (DND, animation scales,
            // refresh rate) outlive the VPN, so the exit must not be gated on it.
            RestoreButton(enabled = !busy, onClick = onRestore)

            Spacer(Modifier.height(12.dp))
        }

        if (pickerOpen) {
            AppPickerDialog(apps = pickerApps, onPick = onAddGame, onDismiss = onClosePicker)
        }

        if (summary != null) {
            SessionSummary(
                record = summary,
                suggestion = summarySuggestion,
                onDismiss = onDismissSummary,
                onApplySuggestion = onApplySuggestion
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Options module: performance profile + VPN opt-in
// ---------------------------------------------------------------------------

@Composable
private fun OptionsModule(
    profile: BoostProfile,
    vpnEnabled: Boolean,
    enabled: Boolean,
    onProfileChange: (BoostProfile) -> Unit,
    onVpnEnabledChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(18.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "PROFILE",
                color = Tac.TextLo,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BoostProfile.entries.forEach { entry ->
                    ProfileChip(
                        profile = entry,
                        selected = entry == profile,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
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
                        fontFamily = Mono
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
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Tac.Bg,
                        checkedTrackColor = Tac.Cyan,
                        uncheckedThumbColor = Tac.TextLo,
                        uncheckedTrackColor = Tac.SurfaceElevated,
                        uncheckedBorderColor = Tac.Line
                    )
                )
            }
        }
    }
}

@Composable
private fun ProfileChip(
    profile: BoostProfile,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = Tac.Cyan
    val border by animateColorAsState(
        if (selected) accent else Tac.Line,
        label = "chipBorder"
    )
    val bg by animateColorAsState(
        if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
        label = "chipBg"
    )

    Surface(
        // selectable (not clickable) so screen readers announce which profile is chosen (L6).
        modifier = modifier
            .height(40.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics { contentDescription = "${profile.label} profile. ${profile.blurb}" },
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                profile.label,
                color = if (selected) accent else Tac.TextLo,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                fontFamily = Mono
            )
        }
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
    onRemoveGame: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "GAME",
            color = Tac.TextLo,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
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
                        onRemove = { onRemoveGame(pkg) }
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
                Text("+ ADD GAME", color = Tac.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = Mono)
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
            Text("ADD A GAME", color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = Mono)
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
                Text("CANCEL", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = Mono)
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
    onRemove: () -> Unit
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
                    Text("SELECTED", color = Tac.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = Mono)
                }
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text("REMOVE", color = Tac.TextLo, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontFamily = Mono)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}

@Composable
private fun RateChip(rate: Float, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val hz = RefreshRate.display(rate)
    Surface(
        modifier = Modifier
            .height(32.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$hz hertz${if (selected) ", selected" else ""}" },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Tac.Cyan.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Tac.Cyan else Tac.Line)
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                "$hz HZ",
                color = if (selected) Tac.Cyan else Tac.TextLo,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = Mono
            )
        }
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

private fun fpsText(fps: Float?): String = fps?.let { String.format(Locale.US, "%.1f", it) } ?: "unavailable"

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
            shape = RoundedCornerShape(18.dp),
            color = Tac.Surface,
            border = BorderStroke(1.dp, Tac.Line)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("SESSION SUMMARY", color = Tac.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontFamily = Mono)
                Spacer(Modifier.height(4.dp))
                Text(name, color = Tac.TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                SummaryRow("Refresh rate chosen", "$rateHz Hz")
                SummaryRow(
                    "Peak rate the system held",
                    record.observedPeak?.let { peak ->
                        val held = RefreshRate.display(peak)
                        if (held == rateHz) "$held Hz" else "$held Hz (HiOS overrode $rateHz)"
                    } ?: "unavailable"
                )
                SummaryRow("Duration", durationText(record.durationMs))
                SummaryRow("Average FPS", fpsText(record.avgFps))
                SummaryRow("1% low FPS", fpsText(record.onePercentLowFps))
                SummaryRow("Time to thermal status 1+", timeToThermal)
                SummaryRow("Max thermal status", thermalText(record.maxThermal))
                SummaryRow(
                    "Samples",
                    "${record.samples} (FPS in ${record.fpsSamples}, thermal in ${record.thermalSamples})"
                )

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
                            fontFamily = Mono
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("CLOSE", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontFamily = Mono)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Tac.TextLo, fontSize = 12.sp)
        Text(value, color = Tac.TextHi, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
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
            fontFamily = Mono
        )
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "TURBO",
            color = Tac.Cyan,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = Mono,
                fontWeight = FontWeight.Black,
                letterSpacing = 12.sp
            )
        )
        Text(
            "ENGINEERING DASHBOARD",
            color = Tac.TextLo,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 5.sp
        )
    }
}

@Composable
private fun TelemetryDashboard(ram: RamInfo?, reachabilityMs: Int, hz: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TelemetryCard(
            label = "MEMORY",
            value = ram?.used ?: "--",
            subValue = ram?.total ?: "READING",
            progress = ram?.fraction ?: 0f,
            modifier = Modifier.weight(1.1f)
        )
        val reachColor = when {
            reachabilityMs < 0 -> Tac.TextLo
            reachabilityMs < 50 -> Tac.Green
            reachabilityMs < 100 -> Tac.Amber
            else -> Tac.Red
        }
        // Not a ping: this is round-trip reachability to a DNS host, measured outside the
        // tunnel, so it is labelled for what it is (L3).
        TelemetryCard(
            label = "REACH",
            value = if (reachabilityMs >= 0) "$reachabilityMs" else "--",
            subValue = "MS TO DNS",
            statusColor = reachColor,
            modifier = Modifier.weight(0.9f)
        )
        TelemetryCard(
            label = "DISPLAY",
            value = hz?.toString() ?: "--",
            subValue = "HZ RATE",
            modifier = Modifier.weight(0.9f)
        )
    }
}

@Composable
private fun TelemetryCard(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    statusColor: Color = Tac.Cyan
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Tac.TextLo, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = Tac.TextHi, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = Mono)
                if (progress == null) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).background(statusColor, CircleShape).align(Alignment.CenterVertically))
                }
            }
            Text(subValue, color = Tac.TextLo, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = statusColor,
                    trackColor = Tac.Line
                )
            }
        }
    }
}

@Composable
private fun BoostButton(step: BoostStep, accent: Color, gameName: String?, onClick: () -> Unit) {
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
            .size(250.dp)
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
                Text(step.label, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
            } else {
                Text("BOOST", color = Tac.TextHi, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 38.sp, letterSpacing = 2.sp)
                Text(
                    if (gameName != null) "& PLAY" else "PICK A GAME",
                    color = accent,
                    fontFamily = Mono,
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
        shape = RoundedCornerShape(18.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Tac.TextLo, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(color)
                Spacer(Modifier.width(10.dp))
                Text(title, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
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
