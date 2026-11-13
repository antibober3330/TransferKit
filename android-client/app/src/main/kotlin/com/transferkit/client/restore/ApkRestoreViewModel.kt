package com.transferkit.client.restore

import androidx.lifecycle.ViewModel

/** Retains the exact item confirmation across Activity recreation while Android Settings is open. */
class ApkRestoreViewModel : ViewModel() {
    val approvalGate = ApkInstallApprovalGate()
    var pendingItem: VerifiedRestoreItem? = null
    var pendingApproval: ApkInstallApproval? = null

    override fun onCleared() {
        approvalGate.revoke(pendingApproval)
        pendingApproval = null
        pendingItem = null
        super.onCleared()
    }
}
