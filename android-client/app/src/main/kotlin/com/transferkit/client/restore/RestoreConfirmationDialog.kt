package com.transferkit.client.restore

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

/** Confirmation displays aggregate scope and creates the one-time approval only after a positive tap. */
@Composable
fun RestoreConfirmationDialog(
    transferId: String,
    manifestSha256: String,
    items: List<VerifiedRestoreItem>,
    calendarDestination: CalendarDestination? = null,
    approvalGate: RestoreApprovalGate,
    onApproved: (RestoreApproval) -> Unit,
    onDismiss: () -> Unit,
) {
    val categories = remember(items) { items.map { it.categoryId }.distinct().sorted() }
    val totalBytes = remember(items) {
        items.fold(0L) { total, item ->
            if (item.sizeBytes < 0L || item.sizeBytes > Long.MAX_VALUE - total) Long.MAX_VALUE
            else total + item.sizeBytes
        }
    }
    var approvalError by remember(transferId, manifestSha256, items) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Підтвердити відновлення") },
        text = {
            Text(
                buildString {
                    append("Буде записано ")
                    append(items.size)
                    append(" елементів (приблизно ")
                    append(String.format(Locale.getDefault(), "%.1f МБ", totalBytes / (1024.0 * 1024.0)))
                    append(").\nКатегорії: ")
                    append(categories.joinToString().ifBlank { "не вибрано" })
                    append(".")
                    if (items.any { it.categoryId == "contacts" }) {
                        append("\nКонтакти будуть створені в локальному сховищі цього телефона й не синхронізуватимуться з акаунтом.")
                    }
                    if (items.any { it.categoryId == "calendar" }) {
                        val destination = calendarDestination
                        append("\nКалендар призначення: ")
                        append(destination?.displayName?.ifBlank { "без назви" } ?: "не вибрано")
                        if (destination != null && destination.accountName.isNotBlank()) {
                            append(" · ")
                            append(destination.accountName)
                        }
                        append(". Події можуть синхронізуватися за налаштуваннями цього календаря.")
                    }
                    append("\nПісля успішної post-перевірки TransferKit видалить власні тимчасові копії саме цих елементів; якщо відновлення не пройде перевірку, копія залишиться для повтору.")
                    append("\nПродовжити?")
                    if (approvalError) append("\nНе вдалося створити підтвердження. Перевір маніфест і вибір.")
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = items.isNotEmpty(),
                onClick = {
                    val approval = runCatching {
                        approvalGate.approveAfterUserConfirmation(
                            transferId = transferId,
                            manifestSha256 = manifestSha256,
                            items = items,
                            confirmedByUser = true,
                        )
                    }.getOrNull()
                    if (approval == null) {
                        approvalError = true
                    } else {
                        approvalError = false
                        onApproved(approval)
                    }
                },
            ) { Text("Відновити") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        },
    )
}
