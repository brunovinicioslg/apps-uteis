package io.github.brunovinicioslg.ladeira.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.app.drive.AlertPhrases
import io.github.brunovinicioslg.ladeira.road.PoiType
import io.github.brunovinicioslg.ladeira.road.UserPoint
import java.text.DateFormat
import java.util.Date

/** What happened after importing or exporting, shown for a moment. */
sealed interface PointsMessage {
    data object Exported : PointsMessage
    data object ExportFailed : PointsMessage
    data class Imported(val count: Int) : PointsMessage
    data object ImportedNothing : PointsMessage
    data object ImportFailed : PointsMessage
}

/**
 * The points the user marked: choosing the type of those marked while driving, fixing the limit
 * of a camera, and sharing the list as a file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsScreen(
    points: List<UserPoint>,
    message: PointsMessage?,
    onSave: (UserPoint) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val text = when (message) {
        null -> null
        PointsMessage.Exported -> stringResource(R.string.points_exported)
        PointsMessage.ExportFailed -> stringResource(R.string.points_export_failed)
        is PointsMessage.Imported -> pluralStringResource(R.plurals.points_imported, message.count, message.count)
        PointsMessage.ImportedNothing -> stringResource(R.string.points_import_nothing)
        PointsMessage.ImportFailed -> stringResource(R.string.points_import_failed)
    }
    LaunchedEffect(text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onMessageShown()
        }
    }
    // Still to classify first, then the newest.
    val ordered = remember(points) { points.sortedWith(compareByDescending<UserPoint> { it.needsType }.thenByDescending { it.createdAtMillis }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.points_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onImport) { Text(stringResource(R.string.points_import)) }
                    TextButton(onClick = onExport, enabled = points.isNotEmpty()) { Text(stringResource(R.string.points_export)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (points.isEmpty()) {
                item { Text(stringResource(R.string.points_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(ordered, key = { it.id }) { point -> PointCard(point, onClick = { editing = point.id }) }
        }
    }
    val point = points.firstOrNull { it.id == editing }
    if (point != null) {
        PointEditor(
            point = point,
            onSave = {
                onSave(it)
                editing = null
            },
            onDelete = {
                onDelete(point.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PointCard(point: UserPoint, onClick: () -> Unit) {
    val context = LocalContext.current
    val phrases = remember(context) { AlertPhrases(context) }
    val colors = if (point.needsType) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = colors) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (point.needsType) stringResource(R.string.points_needs_type) else phrases.poiName(point.poi.type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val details = buildList {
                if (point.createdAtMillis > 0) add(stringResource(R.string.points_marked_at, formatDate(point.createdAtMillis)))
                point.poi.speedLimitKmh?.let { add(stringResource(R.string.poi_limit, it)) }
                add(stringResource(if (point.poi.directionDegrees != null) R.string.points_one_direction_short else R.string.points_both_directions))
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            point.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun PointEditor(point: UserPoint, onSave: (UserPoint) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val phrases = remember(context) { AlertPhrases(context) }
    // An unclassified mark starts with no choice, so "Alerta" is not saved by accident.
    var type by rememberSaveable(point.id) { mutableStateOf(if (point.needsType) null else point.poi.type) }
    var limit by rememberSaveable(point.id) { mutableStateOf(point.poi.speedLimitKmh?.toString().orEmpty()) }
    var oneDirection by rememberSaveable(point.id) { mutableStateOf(point.poi.directionDegrees != null) }
    var note by rememberSaveable(point.id) { mutableStateOf(point.note.orEmpty()) }
    var confirmDelete by rememberSaveable(point.id) { mutableStateOf(false) }
    val chosen = type
    val limitValue = limit.toIntOrNull()?.takeIf { it in 1..250 }
    val limitValid = limit.isBlank() || limitValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (point.needsType) R.string.points_needs_type else R.string.points_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in PoiType.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = option == chosen, onClick = { type = option }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == chosen, onClick = null)
                        Text(phrases.poiName(option), Modifier.padding(start = 8.dp))
                    }
                }
                if (chosen == PoiType.SPEED_CAMERA || chosen == PoiType.RED_LIGHT_CAMERA) {
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { v -> limit = v.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.points_limit_label)) },
                        isError = !limitValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (point.poi.directionDegrees != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.points_one_direction), Modifier.weight(1f))
                        Switch(checked = oneDirection, onCheckedChange = { oneDirection = it })
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text(stringResource(R.string.points_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen != null && limitValid,
                onClick = {
                    val camera = chosen == PoiType.SPEED_CAMERA || chosen == PoiType.RED_LIGHT_CAMERA
                    onSave(
                        point.copy(
                            poi = point.poi.copy(
                                type = chosen!!,
                                speedLimitKmh = if (camera) limitValue else null,
                                directionDegrees = if (oneDirection) point.poi.directionDegrees else null,
                            ),
                            needsType = false,
                            note = note.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text(stringResource(R.string.points_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.points_delete_confirm)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun formatDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
