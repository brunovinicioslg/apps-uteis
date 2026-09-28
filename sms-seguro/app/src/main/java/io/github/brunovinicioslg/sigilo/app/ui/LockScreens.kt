package io.github.brunovinicioslg.sigilo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.UnlockResult
import io.github.brunovinicioslg.sigilo.app.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MIN_PASSWORD_LENGTH = 4

/** A password typed twice; returns the error to show, or null when it is acceptable. */
fun passwordProblem(password: String, confirm: String): Int? = when {
    password.length < MIN_PASSWORD_LENGTH -> R.string.password_too_short
    password != confirm -> R.string.password_mismatch
    else -> null
}

@Composable
private fun CenteredPage(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun PasswordFields(password: String, confirm: String, onPassword: (String) -> Unit, onConfirm: (String) -> Unit, error: Int?) {
    OutlinedTextField(
        value = password, onValueChange = onPassword, label = { Text(stringResource(R.string.password_label)) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = confirm, onValueChange = onConfirm, label = { Text(stringResource(R.string.password_confirm_label)) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** First run: creates the encryption keys, with or without a password. */
@Composable
fun SetupScreen() {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }

    fun finish(withPassword: Boolean) {
        if (withPassword) {
            error = passwordProblem(password, confirm)
            if (error != null) return
        }
        working = true
        scope.launch {
            container.lock.setUp(if (withPassword) password.toCharArray() else null)
            container.afterUnlock()
        }
    }

    CenteredPage {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
        if (working) {
            CircularProgressIndicator()
            Text(stringResource(R.string.setup_working))
            return@CenteredPage
        }
        Text(stringResource(R.string.setup_password_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.setup_password_text), style = MaterialTheme.typography.bodyMedium)
        PasswordFields(password, confirm, { password = it; error = null }, { confirm = it; error = null }, error)
        Button(onClick = { finish(withPassword = true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_with_password)) }
        OutlinedButton(onClick = { finish(withPassword = false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_without_password)) }
    }
}

@Composable
fun LockScreen() {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var working by remember { mutableStateOf(false) }
    val wrong = stringResource(R.string.wrong_password)

    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val waiting = waitUntil > now

    fun unlock() {
        if (password.isEmpty() || working || waiting) return
        working = true
        scope.launch {
            when (val result = container.lock.unlock(password.toCharArray())) {
                UnlockResult.Ok -> container.afterUnlock()
                is UnlockResult.Wrong -> {
                    message = wrong
                    waitUntil = System.currentTimeMillis() + result.waitMillis
                }
                is UnlockResult.Wait -> waitUntil = System.currentTimeMillis() + result.millis
                UnlockResult.Unavailable -> Unit
            }
            password = ""
            working = false
        }
    }

    CenteredPage {
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = password, onValueChange = { password = it; message = null }, label = { Text(stringResource(R.string.password_label)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { unlock() }),
            enabled = !waiting,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            waiting -> Text(
                stringResource(R.string.wait_seconds, ((waitUntil - now + 999) / 1000).toInt()),
                color = MaterialTheme.colorScheme.error,
            )
            message != null -> Text(message!!, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = ::unlock, enabled = !working && !waiting && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.unlock))
        }
    }
}

@Composable
fun UnavailableScreen() {
    CenteredPage {
        Text(stringResource(R.string.unavailable_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.unavailable_text), style = MaterialTheme.typography.bodyLarge)
    }
}
