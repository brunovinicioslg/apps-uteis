package io.github.brunovinicioslg.sossego.device

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.util.Locale

enum class Manufacturer { XIAOMI, SAMSUNG, OTHER }

/** What the phone lets the app do, and the system pages that change it. */
object DeviceStatus {

    /** The phone has the call screening role at all (phones without calls do not). */
    fun screeningAvailable(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true

    /** The app screens incoming calls. */
    fun screeningEnabled(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    /** The system question "make Sossego your call screening app?", or null without the role. */
    fun screeningRequest(context: Context): Intent? =
        context.getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) }
            ?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** What "block everything" needs to decline contacts' calls. */
    val BLOCK_ALL_PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

    fun canDeclineContacts(context: Context): Boolean = BLOCK_ALL_PERMISSIONS.all { granted(context, it) }

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    fun manufacturer(): Manufacturer = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> Manufacturer.XIAOMI
        "samsung" -> Manufacturer.SAMSUNG
        else -> Manufacturer.OTHER
    }

    /** Xiaomi and Samsung keep their own battery (and auto-start) switches on the app details page. */
    fun openBatterySettings(context: Context, manufacturer: Manufacturer) {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val optimizationList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val candidates = if (manufacturer == Manufacturer.OTHER) listOf(optimizationList, appDetails) else listOf(appDetails, optimizationList)
        candidates.firstOrNull { tryStart(context, it) }
    }

    fun openAppSettings(context: Context) {
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    /**
     * Android's own blocked numbers (kept by the phone app): the only way to block a contact
     * without making this app the phone app.
     */
    fun openSystemBlockedNumbers(context: Context): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        val intent = try {
            telecom.createManageBlockedNumbersIntent()
        } catch (e: RuntimeException) {
            null
        } ?: return false
        return tryStart(context, intent)
    }

    fun openUrl(context: Context, url: String) {
        tryStart(context, Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
