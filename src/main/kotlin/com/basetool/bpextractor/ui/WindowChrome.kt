package com.basetool.bpextractor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.MouseInfo
import java.awt.Point

/**
 * Custom KRT window chrome that replaces the white OS title bar (which clashes with
 * the dark HUD). Dark surface, the Basetool Extractor app mark + title in Lato Bold
 * orange, an orange accent hairline, and hand-drawn minimize / maximize / close
 * controls. The title region is draggable; window edges are sharp (fits the brand).
 */
@Composable
fun FrameWindowScope.KrtTitleBar(
    state: WindowState,
    icon: Painter,
    title: String,
    onClose: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).background(Krt.Gray4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Draggable title region — move the OS window by tracking absolute mouse
            // position (smooth even though the composable moves with the window).
            val win = window
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        var startMouse = Point()
                        var startWin = Point()
                        detectDragGestures(
                            onDragStart = {
                                startMouse = MouseInfo.getPointerInfo().location
                                startWin = win.location
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val now = MouseInfo.getPointerInfo().location
                                win.setLocation(startWin.x + (now.x - startMouse.x), startWin.y + (now.y - startMouse.y))
                            },
                        )
                    }
                    .padding(start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // App-specific title-bar actions (e.g. the DE/EN language toggle) sit between
            // the draggable title region and the window controls.
            actions()

            val maximized = state.placement == WindowPlacement.Maximized
            WindowControlButton(onClick = { state.isMinimized = true }) { c ->
                drawLine(c, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5.dp.toPx())
            }
            WindowControlButton(onClick = {
                state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }) { c ->
                val s = 1.5.dp.toPx()
                if (maximized) {
                    val w = size.width * 0.72f
                    val h = size.height * 0.72f
                    drawRect(c, topLeft = Offset(size.width - w, 0f), size = Size(w, h), style = Stroke(s))      // back
                    drawRect(c, topLeft = Offset(0f, size.height - h), size = Size(w, h), style = Stroke(s))     // front
                } else {
                    drawRect(c, style = Stroke(s))
                }
            }
            WindowControlButton(onClick = onClose, danger = true) { c ->
                val s = 1.5.dp.toPx()
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), s)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), s)
            }
        }
        // Orange accent hairline under the title bar.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Krt.Orange))
    }
}

/** A 46×40 window-control hit area with a hand-drawn icon; hover lights it orange (or red for close). */
@Composable
private fun WindowControlButton(
    onClick: () -> Unit,
    danger: Boolean = false,
    icon: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        hovered && danger -> Krt.Danger
        hovered -> Krt.Gray3
        else -> Color.Transparent
    }
    val fg = when {
        hovered && danger -> Krt.White
        hovered || focused -> Krt.Orange
        else -> Krt.Gray1
    }
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 40.dp)
            // Inset ring (negative offset) keeps the focus outline inside the 40dp bar.
            .focusRing(focused, offset = (-7).dp)
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp)) { icon(fg) }
    }
}

/**
 * Bottom-right resize grip for the undecorated window (which has no OS resize border).
 * Drag to resize; a few diagonal ticks hint at the affordance.
 */
@Composable
fun BoxScope.ResizeCorner(state: WindowState) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(18.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    with(density) {
                        val w = (state.size.width + drag.x.toDp()).coerceAtLeast(640.dp)
                        val h = (state.size.height + drag.y.toDp()).coerceAtLeast(520.dp)
                        state.size = DpSize(w, h)
                    }
                }
            }
            .drawBehind {
                val s = 1.5.dp.toPx()
                for (i in 1..3) {
                    val o = i * 4.dp.toPx()
                    drawLine(Krt.Gray2, Offset(size.width - o, size.height), Offset(size.width, size.height - o), s)
                }
            },
    )
}
