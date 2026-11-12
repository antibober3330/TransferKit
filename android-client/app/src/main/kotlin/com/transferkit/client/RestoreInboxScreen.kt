package com.transferkit.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transferkit.client.receiver.ReceivedTransferSummary
import java.util.Locale

@Composable
fun RestoreInboxScreen(
    transfers: List<ReceivedTransferSummary>,
    status: String,
    loading: Boolean,
    restoring: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReview: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Отримані передачі", style = MaterialTheme.typography.headlineMedium)
            Text("Відновлення починається лише після перегляду та твого підтвердження.")
            Text(status, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onBack, enabled = !restoring) { Text("Назад") }
            Button(onClick = onRefresh, enabled = !loading && !restoring) {
                Text(if (loading) "Оновлюю…" else "Оновити список")
            }
            if (transfers.isEmpty() && !loading) {
                Text("Завершених отриманих передач поки немає.")
            }
            transfers.forEach { transfer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Передача ${transfer.transferId.take(8)}", style = MaterialTheme.typography.titleMedium)
                        Text("Перевірено елементів: ${transfer.verifiedItemCount}")
                        if (transfer.restoredItemCount > 0) {
                            Text("Вже відновлено й прибрано з тимчасового сховища: ${transfer.restoredItemCount}")
                        }
                        Text("Медіафайлів для відновлення: ${transfer.restorableItemCount}")
                        Text("Обсяг: ${formatRestoreBytes(transfer.restorableBytes)}")
                        if (transfer.unavailableVerifiedItemCount > 0) {
                            Text("Інші перевірені категорії поки не підтримуються: ${transfer.unavailableVerifiedItemCount}")
                        }
                        if (transfer.failedItemCount > 0) {
                            Text("Не пройшли перевірку під час приймання: ${transfer.failedItemCount}")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            enabled = transfer.restorableItemCount > 0 && !restoring && !loading,
                            onClick = { onReview(transfer.transferId) },
                        ) {
                            Text("Переглянути й відновити медіафайли")
                        }
                    }
                }
            }
        }
    }
}

private fun formatRestoreBytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024.0))
