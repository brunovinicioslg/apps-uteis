package io.github.brunovinicioslg.sigilo.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brunovinicioslg.sigilo.app.BuildConfig
import io.github.brunovinicioslg.sigilo.app.LockState
import io.github.brunovinicioslg.sigilo.app.appContainer
import io.github.brunovinicioslg.sigilo.app.ui.theme.SigiloTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Why the app was opened: from a notification, a "write to" link, or text shared from another app. */
sealed interface LaunchRequest {
    data class Open(val conversationId: Long) : LaunchRequest

    data class Compose(val address: String, val body: String?) : LaunchRequest

    data class Share(val text: String) : LaunchRequest
}

sealed interface Route {
    data object List : Route

    data class Conversation(val id: Long, val draft: String? = null) : Route

    data class New(val draft: String? = null) : Route

    data class Safety(val conversationId: Long) : Route

    data object Settings : Route
}

class MainActivity : ComponentActivity() {

    private val launch = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, and a blank card in the recent apps list.
        if (BuildConfig.SECURE_WINDOW) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Nor text for apps that read the screen through accessibility.
        window.decorView.markAccessibilitySensitive()
        if (savedInstanceState == null) handle(intent)
        setContent {
            SigiloTheme {
                SigiloRoot(launch)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        launch.value = when {
            conversationId >= 0 -> LaunchRequest.Open(conversationId)
            intent.data?.scheme in SMS_SCHEMES -> {
                val address = intent.data?.schemeSpecificPart.orEmpty().removePrefix("//").substringBefore('?').substringBefore(',').trim()
                val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (address.isEmpty()) body?.let { LaunchRequest.Share(it) } else LaunchRequest.Compose(address, body)
            }
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { LaunchRequest.Share(it) }
            else -> null
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
    }
}

@Composable
private fun SigiloRoot(launch: MutableStateFlow<LaunchRequest?>) {
    val container = LocalContext.current.appContainer
    val state by container.lock.state.collectAsStateWithLifecycle()
    when (state) {
        LockState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        LockState.NeedsSetup -> SetupScreen()
        LockState.Locked -> LockScreen()
        LockState.Unavailable -> UnavailableScreen()
        LockState.Unlocked -> UnlockedApp(launch)
    }
}

@Composable
private fun UnlockedApp(launch: MutableStateFlow<LaunchRequest?>) {
    val container = LocalContext.current.appContainer
    // Not saved across process death on purpose: after a restart the app reopens on the list.
    val stack = remember { mutableStateListOf<Route>(Route.List) }
    val request by launch.collectAsStateWithLifecycle()

    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        launch.value = null
        val route = when (r) {
            is LaunchRequest.Open -> Route.Conversation(r.conversationId)
            is LaunchRequest.Compose -> container.lock.withEngine { it.conversationFor(r.address).id }?.let { Route.Conversation(it, r.body) }
            is LaunchRequest.Share -> Route.New(r.text)
        } ?: return@LaunchedEffect
        stack.removeAll { it != Route.List }
        stack += route
    }

    fun push(route: Route) {
        stack += route
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replaceTop(route: Route) {
        stack[stack.lastIndex] = route
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    when (val top = stack.last()) {
        Route.List -> ConversationListScreen(
            onOpen = { push(Route.Conversation(it)) },
            onNew = { push(Route.New()) },
            onSettings = { push(Route.Settings) },
        )
        is Route.Conversation -> ConversationScreen(
            conversationId = top.id,
            initialDraft = top.draft,
            onBack = ::pop,
            onSafetyNumber = { push(Route.Safety(top.id)) },
        )
        is Route.New -> NewConversationScreen(
            onBack = ::pop,
            onStart = { id -> replaceTop(Route.Conversation(id, top.draft)) },
        )
        is Route.Safety -> SafetyNumberScreen(conversationId = top.conversationId, onBack = ::pop)
        Route.Settings -> SettingsScreen(onBack = ::pop)
    }
}
