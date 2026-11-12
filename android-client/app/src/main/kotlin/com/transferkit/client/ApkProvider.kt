package com.transferkit.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A single-file, non-system APK that can be copied without extracting or installing it. */
class ApkFileItem internal constructor(
    val id: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    internal val sourceFile: File,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun manifestMetadata(): Map<String, Any> = buildMap {
        put("format", "apk")
        put("package_name", packageName)
        put("version_code", versionCode)
        versionName?.let { put("version_name", it) }
        put("split", false)
    }

    fun openStream(): InputStream = sourceFile.inputStream()

    /** Copies the current source file while rechecking it against the preflight digest. */
    fun copyAndVerify(output: OutputStream): ApkDigest = openStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            size = checkedAdd(size, read.toLong())
        }
        val copiedHash = digest.digest().toHex()
        if (size != sizeBytes || copiedHash != sha256) {
            throw IOException("APK source changed after manifest preflight")
        }
        ApkDigest(size, copiedHash)
    }
}

data class ApkDigest(val sizeBytes: Long, val sha256: String)

data class ApkScanSummary(
    val availableCount: Int,
    val systemPackagesSkipped: Int,
    val splitPackagesSkipped: Int,
    val unavailablePackagesSkipped: Int,
    val totalBytes: Long,
)

/**
 * Enumerates packages visible to this app, excluding system and split packages from the
 * transferable set. Hashing is streamed and only occurs after the user explicitly starts a scan.
 */
class ApkProvider(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    fun forEachAvailableApk(onApk: (ApkFileItem) -> Unit): ApkScanSummary {
        val packages = packageManager.getInstalledPackages(0).sortedBy { it.packageName }
        var available = 0
        var systemSkipped = 0
        var splitSkipped = 0
        var unavailableSkipped = 0
        var totalBytes = 0L

        packages.forEach { info ->
            val appInfo = info.applicationInfo
            if (appInfo == null) {
                unavailableSkipped += 1
                return@forEach
            }
            if (appInfo.isSystemPackage()) {
                systemSkipped += 1
                return@forEach
            }
            if (!appInfo.splitSourceDirs.isNullOrEmpty()) {
                splitSkipped += 1
                return@forEach
            }

            val source = appInfo.sourceDir?.let(::File)
            if (source == null || !source.isFile || !source.canRead()) {
                unavailableSkipped += 1
                return@forEach
            }

            try {
                val digest = hashFile(source)
                val item = ApkFileItem(
                    id = apkItemId(info.packageName),
                    packageName = info.packageName,
                    versionCode = info.longVersionCodeCompat(),
                    versionName = info.versionName,
                    sourceFile = source,
                    sizeBytes = digest.sizeBytes,
                    sha256 = digest.sha256,
                )
                onApk(item)
                totalBytes = checkedAdd(totalBytes, item.sizeBytes)
                available += 1
            } catch (_: IOException) {
                unavailableSkipped += 1
            }
        }

        return ApkScanSummary(available, systemSkipped, splitSkipped, unavailableSkipped, totalBytes)
    }

    private fun hashFile(file: File): ApkDigest = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            size = checkedAdd(size, read.toLong())
        }
        ApkDigest(size, digest.digest().toHex())
    }
}

private fun ApplicationInfo.isSystemPackage(): Boolean =
    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

@Suppress("DEPRECATION")
private fun PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

private fun apkItemId(packageName: String): String =
    "apk_${MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray()).toHex()}"

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun checkedAdd(total: Long, increment: Long): Long = try {
    Math.addExact(total, increment)
} catch (_: ArithmeticException) {
    throw IOException("APK export exceeds supported byte count")
}
