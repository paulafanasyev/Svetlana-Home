package com.svetlana.home.ui.history

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryEntry
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.SvetlanaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * История Светланы (ТЗ §60).
 */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SvetlanaTheme { HistoryScreen(onBack = { finish() }) } }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit = {}) {
    val history = remember { ServiceLocator.historyManager }
    var entries by remember { mutableStateOf(history.all()) }
    var filter by remember { mutableStateOf<HistoryCategory?>(null) }

    val shown = if (filter == null) entries else entries.filter { it.category == filter }

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row {
                Text(stringResource(R.string.title_history),
                    style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                text = "${entries.size} записей",
                style = MaterialTheme.typography.bodySmall
            )
            HistoryFilterRow(filter) { filter = it; entries = history.all() }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (shown.isEmpty()) {
                    item { Text(stringResource(R.string.history_empty)) }
                }
                items(shown.size) { i ->
                    HistoryRow(shown[i])
                }
                item {
                    Button(
                        onClick = {
                            history.clear()
                            entries = history.all()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                    ) { Text(stringResource(R.string.history_clear), color = AlmostBlack) }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterRow(current: HistoryCategory?, onChange: (HistoryCategory?) -> Unit) {
    LazyColumn(horizontalAlignment = androidx.compose.ui.Alignment.Start) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Все", current == null) { onChange(null) }
                HistoryCategory.entries.forEach { cat ->
                    Chip(cat.label, current == cat) { onChange(cat) }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .padding(top = 6.dp)
            .background(
                if (selected) MintPrimary.copy(alpha = 0.18f) else MintSoft.copy(alpha = 0.06f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Row {
                Text(
                    text = entry.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MintSoft,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            if (entry.detail.isNotBlank()) {
                Text(entry.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
