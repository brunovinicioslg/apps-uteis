package io.github.brunovinicioslg.alumia.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.brunovinicioslg.alumia.appContainer
import io.github.brunovinicioslg.alumia.service.TorchService
import io.github.brunovinicioslg.alumia.service.TorchServiceLauncher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts detection after a reboot or an app update, if the user left it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = context.appContainer
        container.applicationScope.launch {
            try {
                if (container.settingsRepository.settings.first().detectionEnabled) {
                    TorchServiceLauncher.send(context, TorchService.ACTION_START)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
