package com.transferkit.client.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CalendarDestinationDialog(
    destinations: List<CalendarDestination>,
    onSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember(destinations) { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вибрати календар призначення") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("TransferKit запише події лише у вибраний календар. Якщо він синхронізується з акаунтом, події можуть синхронізуватися за його налаштуваннями.")
                destinations.forEach { destination ->
                    val account = listOf(destination.accountName, destination.accountType)
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                    TextButton(
                        onClick = { selectedId = destination.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(selected = selectedId == destination.id, onClick = { selectedId = destination.id })
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(destination.displayName.ifBlank { "Календар без назви" })
                            if (account.isNotBlank()) Text(account)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selectedId != null, onClick = { selectedId?.let(onSelected) }) {
                Text("Вибрати")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
