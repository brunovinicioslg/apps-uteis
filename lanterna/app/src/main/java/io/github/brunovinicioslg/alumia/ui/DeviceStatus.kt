package io.github.brunovinicioslg.alumia.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.util.Locale

enum class Manufacturer { XIAOMI, SAMSUNG, OTHER }

/** Device state the main screen shows hints about. */
object DeviceStatus {

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    fun manufacturer(): Manufacturer = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> Manufacturer.XIAOMI
        "samsung" -> Manufacturer.SAMSUNG
        else -> Manufacturer.OTHER
    }

    /** Xiaomi and Samsung keep their own battery switches on the app details page. */
    fun openBatterySettings(context: Context, manufacturer: Manufacturer) {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val optimizationList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val candidates = if (manufacturer == Manufacturer.OTHER) listOf(optimizationList, appDetails) else listOf(appDetails, optimizationList)
        candidates.firstOrNull { tryStart(context, it) }
    }

    fun openUrl(context: Context, url: String) {
        tryStart(context, Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
