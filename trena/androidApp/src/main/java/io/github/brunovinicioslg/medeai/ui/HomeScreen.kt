package io.github.brunovinicioslg.medeai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.medeai.BuildConfig
import io.github.brunovinicioslg.medeai.R
import io.github.brunovinicioslg.medeai.settings.AppSettings
import io.github.brunovinicioslg.medeai.units.UnitSystem
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri

const val SOURCE_CODE_URL = "https://github.com/brunovinicioslg/apps-uteis"
const val HOME_LIST_TAG = "home_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(settings: AppSettings, onOpen: (Tool) -> Unit, onUnitSystemChange: (UnitSystem) -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(HOME_LIST_TAG),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ToolCard(stringResource(R.string.tool_ar_title), stringResource(R.string.tool_ar_summary)) { onOpen(Tool.AR) }
            }
            item {
                ToolCard(stringResource(R.string.tool_photo_title), stringResource(R.string.tool_photo_summary)) { onOpen(Tool.PHOTO) }
            }
            item {
                ToolCard(stringResource(R.string.tool_tilt_title), stringResource(R.string.tool_tilt_summary)) { onOpen(Tool.TILT) }
            }
            item {
                ToolCard(stringResource(R.string.tool_level_title), stringResource(R.string.tool_level_summary)) { onOpen(Tool.LEVEL) }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.units_title), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.unitSystem == UnitSystem.METRIC,
                                onClick = { onUnitSystemChange(UnitSystem.METRIC) },
                                label = { Text(stringResource(R.string.units_metric)) },
                            )
                            FilterChip(
                                selected = settings.unitSystem == UnitSystem.IMPERIAL,
                                onClick = { onUnitSystemChange(UnitSystem.IMPERIAL) },
                                label = { Text(stringResource(R.string.units_imperial)) },
                            )
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri()))
                            } catch (_: ActivityNotFoundException) {
                                // No browser installed: nothing to open.
                            }
                        }) { Text(stringResource(R.string.about_source)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
