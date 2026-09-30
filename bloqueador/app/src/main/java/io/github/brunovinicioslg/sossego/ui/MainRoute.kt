package io.github.brunovinicioslg.sossego.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.NotifyMode
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.data.ListRepository
import io.github.brunovinicioslg.sossego.device.ContactLookup
import io.github.brunovinicioslg.sossego.device.DeviceStatus
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SOURCE_CODE_URL = "https://github.com/brunovinicioslg/apps-uteis"

enum class Tab { HOME, LISTS, HISTORY }

fun readDevice(context: Context) = DeviceState(
    screeningAvailable = DeviceStatus.screeningAvailable(context),
    screeningEnabled = DeviceStatus.screeningEnabled(context),
    canDeclineContacts = DeviceStatus.canDeclineContacts(context),
    contactsGranted = DeviceStatus.granted(context, Manifest.permission.READ_CONTACTS),
    batteryUnrestricted = DeviceStatus.batteryUnrestricted(context),
    manufacturer = DeviceStatus.manufacturer(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainRoute(openHistoryRequests: Int, viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf(readDevice(context)) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var contactWarning by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val contacts = remember { ContactLookup(context.applicationContext) }

    LaunchedEffect(openHistoryRequests) {
        if (openHistoryRequests > 0) tab = Tab.HISTORY
    }
    // The user may change permissions and roles in the system settings meanwhile.
    LifecycleResumeEffect(Unit) {
        device = readDevice(context)
        now = LocalDateTime.now()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }

    fun message(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            val result = snackbar.showSnackbar(text, actionLabel = action, duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /** After a refusal: if Android will not ask again, offer its settings page. */
    fun refused(text: Int, permissions: Array<String>) {
        val canAskAgain = activity != null && permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (canAskAgain) {
            message(resources.getString(text))
        } else {
            message(resources.getString(text), resources.getString(R.string.open_settings)) { DeviceStatus.openAppSettings(context) }
        }
    }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        device = readDevice(context)
        if (!device.screeningEnabled) message(resources.getString(R.string.setup_denied))
    }
    val declinePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        device = readDevice(context)
        if (!device.canDeclineContacts) refused(R.string.decline_denied, DeviceStatus.BLOCK_ALL_PERMISSIONS)
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        device = readDevice(context)
        if (!granted) refused(R.string.contacts_denied, arrayOf(Manifest.permission.READ_CONTACTS))
    }
    val notificationsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message(resources.getString(R.string.notifications_denied))
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportLists(uri) { ok -> message(resources.getString(if (ok) R.string.export_done else R.string.export_failed)) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importLists(uri) { result ->
                message(
                    when (result) {
                        ImportResult.Failed -> resources.getString(R.string.import_failed)
                        is ImportResult.Done -> importText(resources, result)
                    },
                )
            }
        }
    }

    fun requestScreening() {
        val intent = DeviceStatus.screeningRequest(context)
        if (intent == null) message(resources.getString(R.string.no_telephony_text)) else roleRequest.launch(intent)
    }

    fun added(entry: ListEntry, result: ListRepository.Added) {
        val text = when (result) {
            ListRepository.Added.NEW -> if (entry.list == ListKind.BLOCK) R.string.added_block else R.string.added_allow
            ListRepository.Added.MOVED -> if (entry.list == ListKind.BLOCK) R.string.moved_block else R.string.moved_allow
            ListRepository.Added.UPDATED -> R.string.already_listed
        }
        message(resources.getString(text))
    }

    /** Contacts' calls never reach the screening: blocking one needs something else. */
    fun warnIfContact(entry: ListEntry, fromContacts: Boolean) {
        if (entry.list != ListKind.BLOCK || entry.match != Match.EXACT) return
        if (fromContacts) {
            contactWarning = true
        } else {
            viewModel.isContact(entry.pattern) { if (it) contactWarning = true }
        }
    }

    fun openSystemBlocked() {
        if (!DeviceStatus.openSystemBlockedNumbers(context)) message(resources.getString(R.string.system_blocked_unavailable))
    }

    val current = settings ?: return

    val homeActions = HomeActions(
        onEnableScreening = ::requestScreening,
        onSettingsChange = viewModel::updateSettings,
        onChooseMode = { mode ->
            viewModel.updateSettings { if (mode == Mode.OFF) it.copy(enabled = false) else it.copy(enabled = true, mode = mode) }
            if (mode != Mode.OFF && device.screeningAvailable && !device.screeningEnabled) {
                requestScreening()
            } else if (mode == Mode.BLOCK_ALL && !device.canDeclineContacts) {
                declinePermissions.launch(DeviceStatus.BLOCK_ALL_PERMISSIONS)
            }
        },
        onChooseNotifications = { mode ->
            viewModel.updateSettings { it.copy(notifications = mode) }
            if (mode != NotifyMode.NONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !DeviceStatus.notificationsGranted(context)
            ) {
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onAllowDecline = { declinePermissions.launch(DeviceStatus.BLOCK_ALL_PERMISSIONS) },
        onAllowContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
        onOpenBattery = { DeviceStatus.openBatterySettings(context, device.manufacturer) },
        onOpenSystemBlocked = ::openSystemBlocked,
        onOpenSource = { DeviceStatus.openUrl(context, SOURCE_CODE_URL) },
    )
    val listsActions = ListsActions(
        onSave = { entry, replacing, fromContacts ->
            if (replacing == null) {
                viewModel.addEntry(entry) { result ->
                    added(entry, result)
                    warnIfContact(entry, fromContacts)
                }
            } else {
                viewModel.replaceEntry(replacing.id, entry.copy(createdAt = replacing.createdAt)) { added(entry, it) }
            }
        },
        onDelete = { entry ->
            viewModel.deleteEntry(entry.id)
            message(resources.getString(R.string.deleted), resources.getString(R.string.undo)) {
                viewModel.addEntry(entry.copy(id = 0)) {}
            }
        },
        readPickedContact = contacts::picked,
        onOpenSystemBlocked = ::openSystemBlocked,
    )
    val historyActions = HistoryActions(
        onAllow = { call -> addFromHistory(viewModel, call, ListKind.ALLOW, ::added) },
        onBlock = { call -> addFromHistory(viewModel, call, ListKind.BLOCK, ::added) },
        onCopy = { call ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.app_name), PhoneNumbers.format(call.key)))
            // Android 13 and later confirm copies on their own.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message(resources.getString(R.string.number_copied))
        },
        onDelete = { call -> viewModel.deleteHistory(call.id) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tabTitle(tab))) },
                actions = {
                    when (tab) {
                        Tab.LISTS -> Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_export)) }, onClick = {
                                    menu = false
                                    exportFile.launch(resources.getString(R.string.export_file_name))
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_import)) }, onClick = {
                                    menu = false
                                    importFile.launch(IMPORT_TYPES)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.system_blocked_title)) }, onClick = {
                                    menu = false
                                    openSystemBlocked()
                                })
                            }
                        }
                        Tab.HISTORY -> if (!history.isNullOrEmpty()) {
                            TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
                        }
                        Tab.HOME -> Unit
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(painterResource(tabIcon(item)), contentDescription = null) },
                        label = { Text(stringResource(tabLabel(item))) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            Tab.HOME -> HomeScreen(current, device, now, homeActions, padding)
            Tab.LISTS -> ListsScreen(entries.orEmpty(), listsActions, padding)
            Tab.HISTORY -> HistoryScreen(history.orEmpty(), historyActions, padding)
        }
    }

    if (contactWarning) {
        AlertDialog(
            onDismissRequest = { contactWarning = false },
            title = { Text(stringResource(R.string.contact_warning_title)) },
            text = { Text(stringResource(R.string.contact_warning_text)) },
            confirmButton = {
                TextButton(onClick = {
                    contactWarning = false
                    openSystemBlocked()
                }) { Text(stringResource(R.string.contact_warning_open)) }
            },
            dismissButton = { TextButton(onClick = { contactWarning = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text(stringResource(R.string.history_clear_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun addFromHistory(
    viewModel: MainViewModel,
    call: BlockedCall,
    list: ListKind,
    onDone: (ListEntry, ListRepository.Added) -> Unit,
) {
    if (call.key.isEmpty()) return
    val entry = ListEntry(id = 0, list = list, match = Match.EXACT, pattern = call.key)
    viewModel.addEntry(entry) { onDone(entry, it) }
}

private fun importText(resources: Resources, result: ImportResult.Done): String {
    val added = if (result.added == 0) {
        resources.getString(R.string.import_nothing)
    } else {
        resources.getQuantityString(R.plurals.import_done, result.added, result.added)
    }
    if (result.skipped == 0) return added
    return added + " " + resources.getQuantityString(R.plurals.import_skipped, result.skipped, result.skipped)
}

/** Spreadsheets save CSV files under many names; plain text covers the rest. */
private val IMPORT_TYPES = arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")

private fun tabTitle(tab: Tab): Int = when (tab) {
    Tab.HOME -> R.string.app_name
    Tab.LISTS -> R.string.tab_lists
    Tab.HISTORY -> R.string.tab_history
}

private fun tabLabel(tab: Tab): Int = when (tab) {
    Tab.HOME -> R.string.tab_home
    Tab.LISTS -> R.string.tab_lists
    Tab.HISTORY -> R.string.tab_history
}

private fun tabIcon(tab: Tab): Int = when (tab) {
    Tab.HOME -> R.drawable.ic_shield
    Tab.LISTS -> R.drawable.ic_list
    Tab.HISTORY -> R.drawable.ic_history
}
