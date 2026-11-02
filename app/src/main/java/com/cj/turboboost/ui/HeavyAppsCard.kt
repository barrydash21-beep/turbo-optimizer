package com.cj.turboboost.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cj.turboboost.GameRegistry
import com.cj.turboboost.HeavyApps
import com.cj.turboboost.HeavyAppsUi
import com.cj.turboboost.RamInfo

/**
 * The five running third-party apps holding the most memory. STOP is the purge's force-stop for
 * one app; RESTRICT puts it on the background restriction list, which a boost applies and the
 * session end then puts back. [statuses] is what this app's own read-backs say about a row.
 */
@Composable
fun HeavyAppsCard(
    ui: HeavyAppsUi,
    statuses: Map<String, String>,
    ram: RamInfo?,
    shizukuReady: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onStop: (String) -> Unit,
    onRestrict: (String) -> Unit,
    onClearRam: () -> Unit
) {
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val usable = enabled && shizukuReady

    if (confirmClear) {
        ClearRamDialog(
            onConfirm = {
                confirmClear = false
                onClearRam()
            },
            onDismiss = { confirmClear = false }
        )
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("HEAVY BACKGROUND APPS", Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = shizukuReady && !ui.loading, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(
                    if (ui.loading) "READING" else "REFRESH",
                    color = if (shizukuReady && !ui.loading) Tac.Accent else Tac.TextLo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = UiFont
                )
            }
        }
        Text(
            ram?.takeIf { it.used != "--" }?.let { "${it.used} / ${it.total} in use" } ?: "N/A",
            color = Tac.TextHi,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = UiFont
        )
        Spacer(Modifier.height(10.dp))

        val apps = ui.apps
        // Stopped from here and no longer running: still shown, with their result, until a REFRESH.
        val gone = ui.stopped.filter { pkg -> apps?.none { it.packageName == pkg } != false }
        when {
            !shizukuReady -> Note("Connect Shizuku to list running apps")
            apps == null && gone.isEmpty() -> Note(ui.note ?: if (ui.loading) "Reading the running apps..." else "Not read yet")
            apps != null && apps.isEmpty() && gone.isEmpty() -> Note("No other apps are running")
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                apps.orEmpty().forEach { app ->
                    HeavyAppRow(
                        title = GameRegistry.labelFor(context, app.packageName) ?: app.packageName,
                        megabytes = app.megabytes,
                        status = statuses[app.packageName],
                        enabled = usable,
                        onStop = { onStop(app.packageName) },
                        onRestrict = { onRestrict(app.packageName) }
                    )
                }
                gone.forEach { pkg ->
                    HeavyAppRow(
                        title = GameRegistry.labelFor(context, pkg) ?: pkg,
                        megabytes = null,
                        status = statuses[pkg],
                        enabled = usable,
                        onStop = { onStop(pkg) },
                        onRestrict = { onRestrict(pkg) }
                    )
                }
            }
        }
        if (apps != null) ui.note?.let { Note(it) }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { confirmClear = true },
            enabled = usable,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Clear RAM: stop every running third-party app except the protected ones" },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Tac.Line)
        ) {
            Text("CLEAR RAM", color = if (usable) Tac.Accent else Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = UiFont)
        }
        Spacer(Modifier.height(8.dp))
        Text("Games and system apps not shown", color = Tac.TextLo, fontSize = 11.sp)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Tac.TextLo, fontSize = 12.sp, lineHeight = 17.sp)
}

/** Good news in green, anything the system undid in amber, an unapplied entry in grey. */
private fun statusColor(status: String): Color = when {
    status.startsWith(HeavyApps.STATUS_CAME_BACK) || status.contains("bucket ignored") -> Tac.Warn
    status.startsWith(HeavyApps.STATUS_STOPPED) || status.startsWith(HeavyApps.STATUS_RESTRICTED) -> Tac.Ok
    else -> Tac.TextLo
}

@Composable
private fun HeavyAppRow(
    title: String,
    megabytes: Int?,
    status: String?,
    enabled: Boolean,
    onStop: () -> Unit,
    onRestrict: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Tac.TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(megabytes?.let { "$it MB" } ?: "not running", color = Tac.TextLo, fontSize = 11.sp, fontFamily = UiFont)
            }
            TextButton(onClick = onRestrict, enabled = enabled) {
                Text("RESTRICT", color = if (enabled) Tac.Accent else Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = UiFont)
            }
            Button(
                onClick = onStop,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Tac.Warn, disabledContainerColor = Tac.SurfaceElevated),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.semantics { contentDescription = "Stop $title. Not restorable until opened" }
            ) {
                Text("STOP", color = if (enabled) Tac.Bg else Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
        if (status != null) {
            Text(
                status,
                color = statusColor(status),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            "Stop: not restorable until opened. Restrict: delays this app's notifications, and applies when a boost starts.",
            color = Tac.TextLo,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Clear RAM stops many apps at once: asked every time, never done on tap. */
@Composable
private fun ClearRamDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tac.Surface,
        title = {
            Text("CLEAR RAM?", color = Tac.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = UiFont)
        },
        text = {
            Text(
                "Stops every running third-party app except the protected ones, the same as a boost does. " +
                    "Your other games are stopped too. Stopped apps are not restorable until you open them.",
                color = Tac.TextLo,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Tac.Warn), shape = RoundedCornerShape(10.dp)) {
                Text("CLEAR RAM", color = Tac.Bg, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Tac.TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = UiFont)
            }
        }
    )
}
