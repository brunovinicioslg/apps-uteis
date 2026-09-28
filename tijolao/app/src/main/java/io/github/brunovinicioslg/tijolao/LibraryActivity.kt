package io.github.brunovinicioslg.tijolao

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.playsoftware.j2meloader.MainActivity
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.config.Config
import ru.playsoftware.j2meloader.settings.SettingsActivity

/** The home screen: every embedded game, the best rated first. */
class LibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val games = Catalog.load(this)
        val store = StatsStore(this)
        setContent {
            TijolaoTheme {
                LibraryScreen(games, store)
            }
        }
    }
}

private val Brick = Color(0xFFC2410C)

@Composable
private fun TijolaoTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFB923C), onPrimary = Color(0xFF431407), primaryContainer = Color(0xFF7C2D12), onPrimaryContainer = Color(0xFFFFEDD5))
    } else {
        lightColorScheme(primary = Brick, onPrimary = Color.White, primaryContainer = Color(0xFFFFEDD5), onPrimaryContainer = Color(0xFF431407))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(games: List<Game>, store: StatsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(store.load()) }
    var order by rememberSaveable { mutableStateOf(SortOrder.RATING) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Game?>(null) }
    var preparing by remember { mutableStateOf<Game?>(null) }
    var failed by remember { mutableStateOf<Game?>(null) }
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    val shown = remember(games, stats, order, query) { Library.arrange(games, stats, order, query, Locale.getDefault()) }
    val recent = remember(games, stats) { Library.recent(games, stats) }

    fun play(game: Game) {
        if (preparing != null) return
        selected = null
        preparing = game
        scope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { GameInstaller.prepare(context, game) } }
            preparing = null
            path.onSuccess {
                store.markPlayed(game.id)
                stats = store.load()
                Config.startApp(context, game.title, it)
            }.onFailure { failed = game }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tijolao_title), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_tijolao_more), stringResource(R.string.tijolao_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tijolao_emulator_settings)) }, onClick = {
                            menu = false
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tijolao_imported_games)) }, onClick = {
                            menu = false
                            context.startActivity(Intent(context, MainActivity::class.java))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tijolao_about)) }, onClick = {
                            menu = false
                            about = true
                        })
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(pluralStringResource(R.plurals.tijolao_search_hint, games.size, games.size)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortChip(SortOrder.RATING, R.string.tijolao_sort_rating, order) { order = it }
                    SortChip(SortOrder.NAME, R.string.tijolao_sort_name, order) { order = it }
                    SortChip(SortOrder.RECENT, R.string.tijolao_sort_recent, order) { order = it }
                }
            }
            if (recent.isNotEmpty() && query.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.tijolao_recent), style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent, key = { "r" + it.id }) { game ->
                                GameCard(game, stats.starsOf(game.id), Modifier.width(96.dp)) { selected = game }
                            }
                        }
                    }
                }
            }
            if (games.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.tijolao_no_games), modifier = Modifier.padding(32.dp), textAlign = TextAlign.Center)
                }
            }
            items(shown, key = { it.id }) { game ->
                GameCard(game, stats.starsOf(game.id), Modifier) { selected = game }
            }
        }
    }

    selected?.let { game ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            GameSheet(
                game = game,
                stars = stats.starsOf(game.id),
                onRate = { value ->
                    store.rate(game.id, if (value == stats.starsOf(game.id)) 0 else value)
                    stats = store.load()
                },
                onPlay = { play(game) },
                onSettings = {
                    selected = null
                    scope.launch {
                        // The settings screen edits the game's config, so it must exist first.
                        val path = withContext(Dispatchers.IO) { runCatching { GameInstaller.prepare(context, game) } }
                        path.onSuccess { Config.openSettings(context, game.title, it) }.onFailure { failed = game }
                    }
                },
                onDeleteProgress = {
                    GameInstaller.deleteProgress(game)
                    selected = null
                },
            )
        }
    }
    preparing?.let { game ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            icon = { CircularProgressIndicator() },
            title = { Text(game.title, textAlign = TextAlign.Center) },
            text = { Text(stringResource(R.string.tijolao_preparing)) },
        )
    }
    failed?.let { game ->
        AlertDialog(
            onDismissRequest = { failed = null },
            confirmButton = { TextButton(onClick = { failed = null }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(game.title) },
            text = { Text(stringResource(R.string.tijolao_open_failed)) },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.tijolao_title)) },
            text = { Text(pluralStringResource(R.plurals.tijolao_about_text, games.size, versionName(context), games.size)) },
        )
    }
}

private fun versionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

@Composable
private fun SortChip(value: SortOrder, label: Int, current: SortOrder, onSelect: (SortOrder) -> Unit) {
    FilterChip(selected = value == current, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
}

/** Game icons are tiny pixel art: decoded once, drawn without smoothing. */
private val iconCache = LruCache<String, ImageBitmap>(256)

@Composable
private fun GameIcon(game: Game, size: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(iconCache.get(game.id), game.id) {
        if (value == null && game.hasIcon) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(game.iconAsset).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
            }?.also { iconCache.put(game.id, it) }
        }
    }
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().padding(size.dp / 8))
        } else {
            Text(game.title.take(1).uppercase(), fontSize = (size / 2.5).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun GameCard(game: Game, stars: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GameIcon(game, 72)
        Spacer(Modifier.height(6.dp))
        Text(
            game.title, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(34.dp),
        )
        if (stars > 0) StarsLine(stars, small = true)
    }
}

@Composable
private fun StarsLine(stars: Int, small: Boolean, onRate: ((Int) -> Unit)? = null) {
    val description = stringResource(R.string.tijolao_stars_description, stars)
    Row(Modifier.semantics { contentDescription = description }) {
        for (i in 1..5) {
            val filled = i <= stars
            Text(
                if (filled) "★" else "☆",
                color = if (filled) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (small) 12.sp else 36.sp,
                modifier = if (onRate != null) Modifier.clickable { onRate(i) }.padding(horizontal = 4.dp) else Modifier,
            )
        }
    }
}

@Composable
private fun GameSheet(game: Game, stars: Int, onRate: (Int) -> Unit, onPlay: () -> Unit, onSettings: () -> Unit, onDeleteProgress: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameIcon(game, 96)
        Text(game.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        val details = buildList {
            game.vendor?.takeIf { it.isNotBlank() }?.let(::add)
            add(if (game.resolution == "multi") stringResource(R.string.tijolao_screen_any) else stringResource(R.string.tijolao_screen, game.resolution))
            if (game.landscape) add(stringResource(R.string.tijolao_landscape))
            add(stringResource(R.string.tijolao_size, String.format(Locale.getDefault(), "%.1f", game.size / 1_000_000.0)))
        }
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(stringResource(if (stars > 0) R.string.tijolao_your_rating else R.string.tijolao_rate_it), style = MaterialTheme.typography.labelLarge)
        StarsLine(stars, small = false, onRate = onRate)
        Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.tijolao_play), fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.tijolao_game_settings)) }
            OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.tijolao_delete_progress)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tijolao_delete_progress)) },
            text = { Text(stringResource(R.string.tijolao_delete_progress_confirm, game.title)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteProgress() }) { Text(stringResource(R.string.tijolao_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
