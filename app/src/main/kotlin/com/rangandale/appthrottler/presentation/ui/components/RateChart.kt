package com.rangandale.appthrottler.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.presentation.ui.formatRate
import com.rangandale.appthrottler.presentation.viewmodel.RateSample

/** Download and upload rate over the last minute, on a shared scale that fits the peak. */
@Composable
fun RateChart(samples: List<RateSample>, capacity: Int, modifier: Modifier = Modifier) {
    val downloadColor = MaterialTheme.colorScheme.primary
    val uploadColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val peak = samples.maxOfOrNull { maxOf(it.downloadBytesPerSecond, it.uploadBytesPerSecond) } ?: 0L

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Last minute", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "peak ${formatRate(peak)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .semantics { contentDescription = "Transfer rate over the last minute, peak ${formatRate(peak)}" },
            ) {
                drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                if (samples.size >= 2 && peak > 0) {
                    drawSeries(samples.map { it.uploadBytesPerSecond }, capacity, peak, uploadColor)
                    drawSeries(samples.map { it.downloadBytesPerSecond }, capacity, peak, downloadColor)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "— Download", style = MaterialTheme.typography.labelMedium, color = downloadColor)
                Text(text = "— Upload", style = MaterialTheme.typography.labelMedium, color = uploadColor)
            }
        }
    }
}

/** Newest sample sits at the right edge; a partly filled history starts further right. */
private fun DrawScope.drawSeries(values: List<Long>, capacity: Int, peak: Long, color: Color) {
    val step = size.width / (capacity - 1).coerceAtLeast(1)
    val firstX = size.width - step * (values.size - 1)
    val path = Path()
    values.forEachIndexed { index, value ->
        val x = firstX + step * index
        val y = size.height - size.height * (value.toFloat() / peak)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
}
