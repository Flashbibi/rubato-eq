package ch.sommermeyer.rubato.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * Slider that keeps its own value while dragging and reports it continuously through [onChange]
 * and once more through [onCommit] when the finger is lifted.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: @Composable (Float) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit = {},
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    // Keep showing the local value after release until the committed value comes back.
    var overriding by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!dragging) overriding = false }
    val shown = if (overriding) local else value
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueText(shown), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                overriding = true
                local = it
                onChange(it)
            },
            onValueChangeFinished = {
                dragging = false
                onCommit(local)
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
fun StatusRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.45f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.55f),
        )
    }
}
