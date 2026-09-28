package io.github.brunovinicioslg.alumia.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import io.github.brunovinicioslg.alumia.R
import io.github.brunovinicioslg.alumia.appContainer
import io.github.brunovinicioslg.alumia.service.TorchService
import io.github.brunovinicioslg.alumia.service.TorchServiceLauncher
import io.github.brunovinicioslg.alumia.torch.TorchState

/** Home screen button that toggles the light. */
class TorchWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val isOn = context.appContainer.torch.state.value is TorchState.On
        appWidgetManager.updateAppWidget(appWidgetIds, TorchWidgets.views(context, isOn))
    }
}

object TorchWidgets {
    private const val REQUEST_TOGGLE = 20

    fun update(context: Context, isOn: Boolean) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, TorchWidgetProvider::class.java))
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, isOn))
    }

    fun views(context: Context, isOn: Boolean) = RemoteViews(context.packageName, R.layout.widget_torch).apply {
        setImageViewResource(R.id.widget_button, if (isOn) R.drawable.widget_torch_on else R.drawable.widget_torch_off)
        setContentDescription(
            R.id.widget_button,
            context.getString(if (isOn) R.string.torch_turn_off else R.string.torch_turn_on),
        )
        setOnClickPendingIntent(
            R.id.widget_button,
            TorchServiceLauncher.pendingIntent(context, TorchService.ACTION_TOGGLE_TORCH, REQUEST_TOGGLE),
        )
    }
}
