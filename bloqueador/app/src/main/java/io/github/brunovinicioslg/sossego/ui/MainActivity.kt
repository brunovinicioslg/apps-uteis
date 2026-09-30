package io.github.brunovinicioslg.sossego.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import io.github.brunovinicioslg.sossego.ui.theme.SossegoTheme

class MainActivity : ComponentActivity() {

    /** Bumped when a notification asks to show the history, so the screen switches to it. */
    private val openHistory = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.opensHistory()) openHistory.intValue++
        setContent {
            SossegoTheme {
                MainRoute(openHistoryRequests = openHistory.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.opensHistory()) openHistory.intValue++
    }

    private fun Intent.opensHistory() = getBooleanExtra(EXTRA_OPEN_HISTORY, false)

    companion object {
        const val EXTRA_OPEN_HISTORY = "io.github.brunovinicioslg.sossego.OPEN_HISTORY"
    }
}
