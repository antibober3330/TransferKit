package com.transferkit.client.shizuku

import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

enum class ShizukuState {
    UNSUPPORTED_API,
    SERVICE_NOT_RUNNING,
    PERMISSION_REQUIRED,
    AVAILABLE,
}

data class ShizukuProbe(
    val state: ShizukuState,
    val reasonCode: String,
    val serviceRunning: Boolean,
    val permissionGranted: Boolean,
    /** A Shizuku grant is not proof that a specific operation is supported or permitted. */
    val elevatedOperationsImplemented: Boolean = false,
)

enum class ShizukuPermissionRequestResult {
    REQUEST_SENT,
    UNSUPPORTED_API,
    SERVICE_NOT_RUNNING,
    PERMISSION_ALREADY_GRANTED,
    LEGACY_SERVER_UNSUPPORTED,
    REQUEST_FAILED,
}

/** Narrow adapter keeps state/permission decisions testable without a running Shizuku service. */
internal interface ShizukuApi {
    fun pingBinder(): Boolean
    fun checkSelfPermission(): Int
    fun isPreV11(): Boolean
    fun requestPermission(requestCode: Int)
}

private object PlatformShizukuApi : ShizukuApi {
    override fun pingBinder(): Boolean = Shizuku.pingBinder()
    override fun checkSelfPermission(): Int = Shizuku.checkSelfPermission()
    override fun isPreV11(): Boolean = Shizuku.isPreV11()
    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)
}

internal fun shizukuPermissionRequestMessage(result: ShizukuPermissionRequestResult): String = when (result) {
    ShizukuPermissionRequestResult.REQUEST_SENT ->
        "Підтверди запит у Shizuku. TransferKit не виконує privileged-команд."
    ShizukuPermissionRequestResult.UNSUPPORTED_API ->
        "Підтримка Shizuku недоступна на цьому Android API."
    ShizukuPermissionRequestResult.SERVICE_NOT_RUNNING ->
        "Shizuku не запущено. Запусти його окремо; TransferKit не стартує сервіс сам."
    ShizukuPermissionRequestResult.PERMISSION_ALREADY_GRANTED ->
        "Дозвіл Shizuku вже надано, але elevated settings операції ще не реалізовані."
    ShizukuPermissionRequestResult.LEGACY_SERVER_UNSUPPORTED ->
        "Версія сервера Shizuku застаріла для permission API. Онови Shizuku."
    ShizukuPermissionRequestResult.REQUEST_FAILED ->
        "Не вдалося надіслати запит Shizuku; перевір стан його сервісу."
}

/** Read-only availability probe. This bridge does not start a shell or execute privileged work. */
internal class ShizukuBridge(
    private val api: ShizukuApi = PlatformShizukuApi,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    fun probe(): ShizukuProbe {
        if (sdkInt < Build.VERSION_CODES.R) {
            return ShizukuProbe(
                state = ShizukuState.UNSUPPORTED_API,
                reasonCode = "API_UNAVAILABLE",
                serviceRunning = false,
                permissionGranted = false,
            )
        }
        val serviceRunning = runCatching { api.pingBinder() }.getOrDefault(false)
        if (!serviceRunning) {
            return ShizukuProbe(
                state = ShizukuState.SERVICE_NOT_RUNNING,
                reasonCode = "SHIZUKU_REQUIRED",
                serviceRunning = false,
                permissionGranted = false,
            )
        }
        val permissionGranted = runCatching {
            api.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (permissionGranted) {
            ShizukuProbe(
                state = ShizukuState.AVAILABLE,
                reasonCode = "OK",
                serviceRunning = true,
                permissionGranted = true,
            )
        } else {
            ShizukuProbe(
                state = ShizukuState.PERMISSION_REQUIRED,
                reasonCode = "USER_ACTION_REQUIRED",
                serviceRunning = true,
                permissionGranted = false,
            )
        }
    }

    /** Requests only Shizuku app authorization; it does not execute any privileged operation. */
    fun requestPermission(requestCode: Int): ShizukuPermissionRequestResult {
        val current = probe()
        return when (current.state) {
            ShizukuState.UNSUPPORTED_API -> ShizukuPermissionRequestResult.UNSUPPORTED_API
            ShizukuState.SERVICE_NOT_RUNNING -> ShizukuPermissionRequestResult.SERVICE_NOT_RUNNING
            ShizukuState.AVAILABLE -> ShizukuPermissionRequestResult.PERMISSION_ALREADY_GRANTED
            ShizukuState.PERMISSION_REQUIRED -> {
                if (runCatching { api.isPreV11() }.getOrDefault(true)) {
                    ShizukuPermissionRequestResult.LEGACY_SERVER_UNSUPPORTED
                } else if (runCatching { api.requestPermission(requestCode) }.isSuccess) {
                    ShizukuPermissionRequestResult.REQUEST_SENT
                } else {
                    ShizukuPermissionRequestResult.REQUEST_FAILED
                }
            }
        }
    }

    fun userWarning(): String =
        "Shizuku є опційним. Його потрібно окремо запустити й авторизувати; це не надає root " +
            "і не гарантує доступ до кожного API. TransferKit поки не виконує elevated operations."
}
