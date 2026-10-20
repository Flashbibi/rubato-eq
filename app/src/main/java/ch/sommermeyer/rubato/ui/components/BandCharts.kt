package ch.sommermeyer.rubato.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Live spectrum (bars), target curve (ticks) and the song's long-term average (dots), all
 * normalised to the mean band level (0 dB line).
 */
@Composable
fun SpectrumChart(
    labels: List<String>,
    live: List<Double>?,
    target: List<Double>?,
    songAverage: List<Double>?,
    liveLabel: String,
    targetLabel: String,
    songLabel: String,
    modifier: Modifier = Modifier,
    rangeDb: Double = 15.0,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.tertiary
    val songColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            val slot = size.width / labels.size
            fun y(db: Double): Float {
                val clamped = db.coerceIn(-rangeDb, rangeDb)
                return (size.height / 2 - clamped / rangeDb * size.height / 2).toFloat()
            }
            drawGrid(gridColor, listOf(-10.0, 0.0, 10.0).map(::y))
            live?.forEachIndexed { i, v ->
                val top = min(y(v), y(0.0))
                val bottom = max(y(v), y(0.0))
                drawRect(
                    color = barColor.copy(alpha = 0.75f),
                    topLeft = Offset(slot * i + slot * 0.2f, top),
                    size = Size(slot * 0.6f, max(bottom - top, 1f)),
                )
            }
            target?.forEachIndexed { i, v ->
                drawLine(
                    color = targetColor,
                    start = Offset(slot * i + slot * 0.08f, y(v)),
                    end = Offset(slot * (i + 1) - slot * 0.08f, y(v)),
                    strokeWidth = 3.dp.toPx(),
                )
            }
            songAverage?.forEachIndexed { i, v ->
                drawCircle(color = songColor, radius = 4.dp.toPx(), center = Offset(slot * i + slot / 2, y(v)))
            }
        }
        BandLabels(labels)
        Legend(listOf(barColor to liveLabel, targetColor to targetLabel, songColor to songLabel))
    }
}

/** Applied correction per band in dB, with guide lines at ±[limitDb]. */
@Composable
fun CorrectionChart(
    labels: List<String>,
    gainsDb: List<Double>,
    valueLabels: List<String>,
    limitDb: Double,
    modifier: Modifier = Modifier,
) {
    val boostColor = MaterialTheme.colorScheme.primary
    val cutColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val range = max(max(limitDb, gainsDb.maxOfOrNull { abs(it) } ?: 0.0), 1.0) * 1.15
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            val slot = size.width / labels.size
            fun y(db: Double) = (size.height / 2 - db / range * size.height / 2).toFloat()
            drawGrid(gridColor, listOf(y(0.0)))
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            listOf(limitDb, -limitDb).forEach { limit ->
                drawLine(gridColor, Offset(0f, y(limit)), Offset(size.width, y(limit)), 1.dp.toPx(), pathEffect = dash)
            }
            gainsDb.forEachIndexed { i, g ->
                val top = min(y(g), y(0.0))
                val bottom = max(y(g), y(0.0))
                drawRect(
                    color = if (g >= 0) boostColor else cutColor,
                    topLeft = Offset(slot * i + slot * 0.25f, top),
                    size = Size(slot * 0.5f, max(bottom - top, 1f)),
                )
            }
        }
        BandLabels(valueLabels)
        BandLabels(labels)
    }
}

@Composable
private fun BandLabels(labels: List<String>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach {
            Text(
                it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Legend(entries: List<Pair<Color, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        entries.forEach { (color, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun DrawScope.drawGrid(color: Color, ys: List<Float>) {
    ys.forEach { y -> drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
}
