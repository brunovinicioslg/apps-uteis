package io.github.brunovinicioslg.medeai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.brunovinicioslg.medeai.R
import io.github.brunovinicioslg.medeai.units.MeasureFormatter
import java.text.DecimalFormatSymbols

/** Formatter using the decimal separator of the phone's language (comma in Portuguese). */
@Composable
fun rememberFormatter(): MeasureFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { MeasureFormatter(DecimalFormatSymbols.getInstance(locale).decimalSeparator) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        },
    )
}

fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Shows [content] once the camera permission is granted; otherwise explains and asks for it. */
@Composable
fun CameraPermissionGate(rationale: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // The user may grant it from system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        granted = hasCameraPermission(context)
        onPauseOrDispose { }
    }
    if (granted) {
        content()
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(rationale, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.camera_permission_button)) }
    }
}

/** Live back-camera preview, bound to the screen's lifecycle. */
@Composable
fun CameraPreview(modifier: Modifier = Modifier, onError: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    provider = future.get().also {
                        it.unbindAll()
                        it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    }
                } catch (e: IllegalArgumentException) {
                    Log.w("CameraPreview", "No back camera", e)
                    onError()
                } catch (e: IllegalStateException) {
                    Log.w("CameraPreview", "Camera unavailable", e)
                    onError()
                } catch (e: java.util.concurrent.ExecutionException) {
                    Log.w("CameraPreview", "Camera provider failed", e)
                    onError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { provider?.unbind(preview) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
