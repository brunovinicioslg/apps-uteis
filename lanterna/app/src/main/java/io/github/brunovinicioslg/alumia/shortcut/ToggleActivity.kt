package io.github.brunovinicioslg.alumia.shortcut

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.brunovinicioslg.alumia.service.TorchService
import io.github.brunovinicioslg.alumia.service.TorchServiceLauncher

/**
 * Invisible activity behind the launcher shortcuts and the optional side-key icon. Starting a
 * foreground service is allowed here because the user just launched this activity.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceAction = when (intent?.action) {
            ACTION_TOGGLE_DETECTION -> TorchService.ACTION_TOGGLE_DETECTION
            else -> TorchService.ACTION_TOGGLE_TORCH // shortcut, or MAIN from the side-key icon
        }
        TorchServiceLauncher.send(this, serviceAction)
        finish()
    }

    companion object {
        const val ACTION_TOGGLE_DETECTION = "io.github.brunovinicioslg.alumia.action.TOGGLE_DETECTION"
    }
}

/** Shows or hides the extra "Toggle flashlight" launcher icon used with the side key. */
object SideKeyShortcut {
    private const val ALIAS = "io.github.brunovinicioslg.alumia.SideKeyToggle"

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(ComponentName(context, ALIAS), state, PackageManager.DONT_KILL_APP)
    }
}
