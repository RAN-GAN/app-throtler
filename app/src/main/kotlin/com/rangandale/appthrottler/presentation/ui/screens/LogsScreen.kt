package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.presentation.viewmodel.LogsViewModel

@Composable
fun LogsScreen(
    contentPadding: PaddingValues,
    viewModel: LogsViewModel,
) {
    val entries by viewModel.entries.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Logs", style = MaterialTheme.typography.headlineLarge)
            OutlinedButton(onClick = viewModel::clear) {
                Text("Clear")
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No logs yet. Start the VPN to see activity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A)),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: ThrottleLogger.LogEntry) {
    val isCrash = entry.tag == "CRASH"
    val levelColor = when {
        isCrash -> Color(0xFFFF1744)
        else -> when (entry.level) {
            ThrottleLogger.Level.DEBUG -> Color(0xFF888888)
            ThrottleLogger.Level.INFO -> Color(0xFF4FC3F7)
            ThrottleLogger.Level.WARN -> Color(0xFFFFB74D)
            ThrottleLogger.Level.ERROR -> Color(0xFFEF5350)
        }
    }
    val levelLabel = when (entry.level) {
        ThrottleLogger.Level.DEBUG -> "D"
        ThrottleLogger.Level.INFO -> "I"
        ThrottleLogger.Level.WARN -> "W"
        ThrottleLogger.Level.ERROR -> "E"
    }
    val rowBg = if (isCrash) Color(0xFF2A0A0A) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = entry.formattedTime,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF666666),
            modifier = Modifier.padding(top = 1.dp),
        )
        Box(
            modifier = Modifier
                .background(levelColor.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = if (isCrash) "!" else levelLabel,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = levelColor,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "${entry.tag}: ${entry.message}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (isCrash) levelColor else Color(0xFFDDDDDD),
            fontWeight = if (isCrash) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
