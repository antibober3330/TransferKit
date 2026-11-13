package com.transferkit.client.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Lets the user choose one verified APK; each choice still requires its own confirmation. */
@Composable
fun ApkRestoreReviewDialog(
    items: List<VerifiedRestoreItem>,
    onInstall: (VerifiedRestoreItem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перевірені APK") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Кожне встановлення окремо підтверджується тобою та Android.")
                items.forEach { item ->
                    val packageName = item.metadata["package_name"] as? String ?: "невідомий пакет"
                    val versionCode = (item.metadata["version_code"] as? Number)?.toLong()?.toString()
                        ?: "невідома версія"
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onInstall(item) },
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(packageName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Версія $versionCode · ${formatApkBytes(item.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

private fun formatApkBytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024.0))
