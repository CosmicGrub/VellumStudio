package com.vellum.studio.ui.editor

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A saturation/value square + hue strip HSV picker. Deliberately hand-rolled (no external color
 * picker library) so it matches the app's own visual language and needs no extra dependency.
 */
@Composable
fun ColorPicker(initialColorArgb: Int, onColorChanged: (Int) -> Unit, modifier: Modifier = Modifier) {
    val initialHsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(initialColorArgb, it) } }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    // Tracks the last color WE emitted, so we can tell "the color changed because something else
    // picked a new one (hex field, a palette swatch, a Recent color)" apart from "the color changed
    // because we just emitted it ourselves." Without that distinction, resyncing the puck/hue-slider
    // position on every initialColorArgb change would fight our own onDrag emissions - every drag
    // frame flows back down as a new initialColorArgb - which is especially unstable right around
    // the grayscale column, where hue is undefined at saturation=0 and an RGB->HSV round-trip can
    // make it flicker unpredictably mid-drag instead of tracking the finger/pen smoothly.
    var lastEmittedColorArgb by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(initialColorArgb) {
        if (initialColorArgb != lastEmittedColorArgb) {
            AndroidColor.colorToHSV(initialColorArgb, initialHsv)
            hue = initialHsv[0]
            sat = initialHsv[1]
            value = initialHsv[2]
        }
    }

    fun emit() {
        val argb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
        lastEmittedColorArgb = argb
        onColorChanged(argb)
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(hue) {
                    val boxSize = size
                    detectDragGestures(
                        onDragStart = { offset ->
                            sat = (offset.x / boxSize.width).coerceIn(0f, 1f)
                            value = 1f - (offset.y / boxSize.height).coerceIn(0f, 1f)
                            emit()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            sat = (change.position.x / boxSize.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / boxSize.height).coerceIn(0f, 1f)
                            emit()
                        },
                    )
                },
        ) {
            val hueColor = Color.hsv(hue, 1f, 1f)
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = sat * size.width
            val cy = (1f - value) * size.height
            drawCircle(color = Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
        }

        Spacer(Modifier.height(12.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    val stripWidth = size.width
                    detectDragGestures(
                        onDragStart = { offset ->
                            hue = (offset.x / stripWidth).coerceIn(0f, 1f) * 360f
                            emit()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            hue = (change.position.x / stripWidth).coerceIn(0f, 1f) * 360f
                            emit()
                        },
                    )
                },
        ) {
            val hueColors = (0..12).map { Color.hsv(it * 30f, 1f, 1f) }
            drawRect(Brush.horizontalGradient(hueColors))
            val x = (hue / 360f) * size.width
            drawCircle(color = Color.White, radius = 10.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(width = 2.dp.toPx()))
        }
    }
}
