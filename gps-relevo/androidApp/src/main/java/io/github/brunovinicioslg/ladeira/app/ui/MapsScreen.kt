package io.github.brunovinicioslg.ladeira.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.ladeira.app.BuildConfig
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.app.region.ImportResult
import io.github.brunovinicioslg.ladeira.app.region.ImportState
import io.github.brunovinicioslg.ladeira.app.region.Region
import io.github.brunovinicioslg.ladeira.app.region.RegionProblem
import java.util.Locale

/** Installed regions, importing new ones, voice alerts and the about section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsScreen(
    regions: List<Region>,
    importState: ImportState,
    voiceEnabled: Boolean,
    voiceAvailable: Boolean?,
    onImport: () -> Unit,
    onDismissResults: () -> Unit,
    onDelete: (String) -> Unit,
    onVoiceChange: (Boolean) -> Unit,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
    pointsCount: Int = 0,
    onOpenPoints: () -> Unit = {},
) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.maps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.maps_regions), style = MaterialTheme.typography.titleMedium) }
            if (regions.isEmpty()) {
                item { Text(stringResource(R.string.maps_none), style = MaterialTheme.typography.bodyMedium) }
            }
            items(regions, key = { it.name }) { region -> RegionCard(region, onDelete = { deleting = region.name }) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport, enabled = !importState.running, modifier = Modifier.fillMaxWidth()) {
                        if (importState.running) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.maps_importing), Modifier.padding(start = 8.dp))
                        } else {
                            Text(stringResource(R.string.maps_import))
                        }
                    }
                    Text(stringResource(R.string.maps_import_help), style = MaterialTheme.typography.bodySmall)
                    ImportResults(importState, onDismissResults)
                }
            }
            item { HorizontalDivider() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.points_summary), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenPoints, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.points_open, pointsCount))
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_voice), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.settings_voice_summary), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = voiceEnabled, onCheckedChange = onVoiceChange)
                }
                if (voiceAvailable == false) {
                    Text(
                        stringResource(R.string.settings_voice_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item { HorizontalDivider() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.map_attribution), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpenLicenses) { Text(stringResource(R.string.about_licenses)) }
                }
            }
        }
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.maps_delete_title, name)) },
            text = { Text(stringResource(R.string.maps_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(name)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RegionCard(region: Region, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(region.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.maps_size, megabytes(region.sizeBytes)), style = MaterialTheme.typography.bodySmall)
                for (problem in region.problems) {
                    Text(stringResource(problemText(problem)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun ImportResults(state: ImportState, onDismiss: () -> Unit) {
    if (state.results.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (result in state.results) {
                when (result) {
                    is ImportResult.Imported -> Text(stringResource(R.string.maps_imported, result.fileName), style = MaterialTheme.typography.bodyMedium)
                    is ImportResult.Failed -> Text(
                        stringResource(R.string.maps_import_failed, result.fileName, stringResource(errorText(result.error))),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!state.running) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

private fun problemText(problem: RegionProblem) = when (problem) {
    RegionProblem.MISSING_ROADS -> R.string.maps_missing_roads
    RegionProblem.MISSING_MAP -> R.string.maps_missing_map
    RegionProblem.INVALID_ROADS -> R.string.maps_invalid_roads
    RegionProblem.INVALID_MAP -> R.string.maps_invalid_map
}

private fun errorText(error: ImportResult.Error) = when (error) {
    ImportResult.Error.UNKNOWN_TYPE -> R.string.maps_error_unknown_type
    ImportResult.Error.INVALID -> R.string.maps_error_invalid
    ImportResult.Error.IO -> R.string.maps_error_io
}

private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1e6)
