package com.cj.turboboost.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Layout building blocks shared by the screens. Colours and type come from Theme.kt.

/** A rounded card on the surface colour. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** The small letter-spaced uppercase label that opens a card. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Tac.TextLo) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = 10.sp,
        letterSpacing = LabelTracking,
        fontWeight = FontWeight.Bold,
        fontFamily = UiFont
    )
}

/** One track holding mutually exclusive [Segment]s. */
@Composable
fun SegmentedBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(Tac.SurfaceElevated)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        content = content
    )
}

/**
 * A segment of a [SegmentedBar]. Selectable rather than clickable so screen readers announce
 * which one is chosen.
 */
@Composable
fun RowScope.Segment(
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.(tint: Color) -> Unit
) {
    val tint = if (selected) Tac.Accent else Tac.TextLo
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Tac.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content(tint) }
    }
}

/** A large figure with its label. [value] null reads as "N/A" and is never replaced by a guess. */
@Composable
fun StatTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    progress: Float? = null
) {
    SectionCard(modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value ?: "N/A",
                color = if (value != null) Tac.TextHi else Tac.TextLo,
                fontSize = 30.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = UiFont,
                maxLines = 1
            )
            if (value != null && unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Tac.TextLo, fontSize = 12.sp, fontFamily = UiFont, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
            }
        }
        if (progress != null && value != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Tac.Accent,
                trackColor = Tac.Line
            )
        }
    }
}

/** A small rounded status chip: a dot and a short uppercase word. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 11.sp, letterSpacing = LabelTracking, fontWeight = FontWeight.Bold, fontFamily = UiFont, maxLines = 1)
    }
}

/**
 * The app's four pages. One enum and one saved state drive both the portrait bottom bar and the
 * landscape rail, so they always show the same pages.
 */
enum class Page(val label: String) {
    BOOST("BOOST"),
    APPS("APPS"),
    TUNE("TUNE"),
    REPORTS("REPORTS")
}

/** One page's icon and name, selectable like a tab so screen readers announce which page is open. */
@Composable
private fun PageItem(page: Page, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (active) Tac.Accent else Tac.TextLo
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Tac.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "${page.label.lowercase().replaceFirstChar { it.uppercase() }} page${if (active) ", selected" else ""}" }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(22.dp)) { drawPageIcon(page, tint) }
        Spacer(Modifier.height(4.dp))
        Text(page.label, color = tint, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont, maxLines = 1)
    }
}

/** The narrow icon rail on the left of the landscape layout. */
@Composable
fun IconRail(selected: Page, onSelect: (Page) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(Tac.Surface)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Page.entries.forEach { page ->
            PageItem(page, page == selected, { onSelect(page) }, Modifier.width(60.dp))
        }
    }
}

/** The bar along the bottom of the portrait layout. */
@Composable
fun PageBar(selected: Page, onSelect: (Page) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Tac.Surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Page.entries.forEach { page ->
            PageItem(page, page == selected, { onSelect(page) }, Modifier.weight(1f))
        }
    }
}

/**
 * A page's content. Narrow: [left] then [right], one scrolling column. Wide: two columns, each
 * scrolling on its own.
 */
@Composable
fun PageColumns(
    wide: Boolean,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit
) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = left
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = right
            )
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
            left()
            right()
        }
    }
}

/**
 * A card that is closed until opened, for the options a page does not need on first sight.
 * [state] is a short word shown beside the title while it is closed, such as ON or OFF. Whether
 * it is open survives rotation and the process being killed.
 */
@Composable
fun CollapsibleCard(
    title: String,
    modifier: Modifier = Modifier,
    state: String? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        color = Tac.Surface,
        border = BorderStroke(1.dp, Tac.Line)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide $title" else "Show $title") { expanded = !expanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel(title, Modifier.weight(1f))
                if (state != null) {
                    Text(state, color = Tac.TextHi, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
                    Spacer(Modifier.width(12.dp))
                }
                Text(if (expanded) "HIDE" else "SHOW", color = Tac.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

private fun DrawScope.drawPageIcon(page: Page, tint: Color) {
    val w = size.width
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    when (page) {
        // A play triangle.
        Page.BOOST -> {
            val path = Path().apply {
                moveTo(w * 0.22f, w * 0.1f)
                lineTo(w * 0.9f, w * 0.5f)
                lineTo(w * 0.22f, w * 0.9f)
                close()
            }
            drawPath(path, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Four tiles.
        Page.APPS -> {
            val cell = w * 0.42f
            for (x in 0..1) for (y in 0..1) {
                drawRoundRect(
                    tint,
                    topLeft = Offset(x * (w - cell), y * (w - cell)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = stroke
                )
            }
        }
        // Two sliders.
        Page.TUNE -> {
            val ys = listOf(w * 0.3f, w * 0.7f)
            val knobs = listOf(w * 0.35f, w * 0.7f)
            ys.forEachIndexed { i, y ->
                drawLine(tint, Offset(0f, y), Offset(w, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(Tac.Surface, radius = 3.5.dp.toPx(), center = Offset(knobs[i], y))
                drawCircle(tint, radius = 3.5.dp.toPx(), center = Offset(knobs[i], y), style = stroke)
            }
        }
        // Three bars.
        Page.REPORTS -> {
            val heights = listOf(0.45f, 0.95f, 0.7f)
            heights.forEachIndexed { i, h ->
                val x = w * (0.1f + i * 0.35f)
                drawLine(tint, Offset(x, w), Offset(x, w * (1f - h)), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
