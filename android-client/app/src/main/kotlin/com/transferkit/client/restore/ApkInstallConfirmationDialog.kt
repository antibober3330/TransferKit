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

/** Shows one package at a time; each positive action creates consent for that APK only. */
@Composable
fun ApkInstallConfirmationDialog(
    item: VerifiedRestoreItem,
    approvalGate: ApkInstallApprovalGate,
    onApproved: (ApkInstallApproval) -> Unit,
    onDismiss: () -> Unit,
) {
    val rawPackageName = item.metadata["package_name"] as? String
    val packageName = rawPackageName?.takeIf(PACKAGE_NAME::matches) ?: "некоректне ім’я пакета"
    val versionCodeValue = (item.metadata["version_code"] as? Number)?.toLong()
    val versionCode = versionCodeValue?.takeIf { it >= 0L }?.toString() ?: "некоректна версія"
    val validInstallTarget = rawPackageName?.let(PACKAGE_NAME::matches) == true &&
        versionCodeValue != null && versionCodeValue >= 0L && item.sizeBytes >= 0L && SHA256.matches(item.sha256)
    var approvalError by remember(item.transferId, item.itemId, item.sha256) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Підтвердити встановлення APK") },
        text = {
            Text(
                "Застосунок: $packageName\nВерсія: $versionCode\n" +
                    "Розмір: ${String.format(Locale.getDefault(), "%.1f MiB", item.sizeBytes / (1024.0 * 1024.0))}.\n" +
                    "Після цього Android може показати ще одне системне підтвердження." +
                    if (approvalError) "\nНе вдалося видати дозвіл. Спробуй переглянути APK ще раз." else "",
            )
        },
        confirmButton = {
            TextButton(enabled = validInstallTarget, onClick = {
                val approval = runCatching {
                    approvalGate.approveAfterUserConfirmation(item, confirmedByUser = true)
                }.getOrNull()
                if (approval == null) approvalError = true else onApproved(approval)
            }) { Text("Підтвердити цей APK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

private val PACKAGE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
private val SHA256 = Regex("^[a-f0-9]{64}$")
