package xendroid.compose.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xendroid.compose.R
import xendroid.compose.settings.GameSettingsViewModel
import xendroid.compose.settings.SettingsCategory
import xendroid.compose.ui.theme.BladeTile

/**
 * The per-game override editor: the same two-level INDEX -> DETAIL shape as
 * [SettingsScreen], but each detail row is an [OverrideRow] (a leading switch that
 * overrides/inherits the key), the index "changed" count is the overridden count, and
 * the header shows the game name. The override config is SPARSE — only toggled-on keys
 * are written, and the file is rebuilt/deleted on flush (no native key-erase exists).
 * A title id is keyed PER GAME (not per file), so this applies to every copy of the game.
 */
@Composable
fun PerGameSettingsScreen(
    vm: GameSettingsViewModel,
    gameName: String,
    onBack: () -> Unit,
) {
    val overrides by vm.overrides.collectAsStateWithLifecycle()

    // Durable flush on pause; re-open on resume. Dispose flush = backstop. (Mirrors SettingsScreen.)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> vm.flush()
                Lifecycle.Event.ON_RESUME -> vm.onResume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.flush() }
    }

    var selected by remember { mutableStateOf<SettingsCategory?>(null) }
    val section = selected
    if (section == null) {
        PerGameIndex(
            gameName = gameName,
            categories = vm.categories,
            overriddenCountOf = { cat -> cat.settings.count { overrides.containsKey(it.key) } },
            onOpen = { selected = it },
            onBack = { vm.flush(); onBack() },
        )
    } else {
        BackHandler { selected = null }
        PerGameCategoryDetail(
            category = section,
            overrides = overrides,
            vm = vm,
            onBack = { selected = null },
        )
    }
}

@Composable
private fun PerGameIndex(
    gameName: String,
    categories: List<SettingsCategory>,
    overriddenCountOf: (SettingsCategory) -> Int,
    onOpen: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.library_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(BladeTile.ScreenTint.copy(alpha = 0.55f)))

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    if (gameName.isNotEmpty()) "$gameName settings" else "Per-game settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = BladeTile.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                // Title id is keyed per game (not per file), so this applies to every copy.
                Text(
                    "Overrides apply to all copies of this game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BladeTile.TextSecondary,
                )
            }

            var highlighted by remember { mutableStateOf<SettingsCategory?>(null) }

            LazyColumn(
                Modifier.weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(BladeTile.ListWidthFraction)
                    .widthIn(max = BladeTile.ListMaxWidth),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(categories, key = { it.title }) { cat ->
                    val overridden = overriddenCountOf(cat)
                    SettingsCategoryRow(
                        title = cat.title,
                        subtitle = buildString {
                            append("${cat.settings.size} settings")
                            if (overridden > 0) append("  ·  $overridden overridden")
                        },
                        iconRes = categoryIcon(cat.title),
                        selected = highlighted?.title == cat.title,
                        onClick = {
                            if (highlighted?.title == cat.title) onOpen(cat) else highlighted = cat
                        },
                    )
                }
            }

            SettingsLegend(
                labelA = "Select",
                labelB = "Back",
                onA = highlighted?.let { cat -> { onOpen(cat) } },
                onB = onBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerGameCategoryDetail(
    category: SettingsCategory,
    overrides: Map<String, String>,
    vm: GameSettingsViewModel,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.library_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(BladeTile.ScreenTint.copy(alpha = 0.55f)))

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(category.title, color = BladeTile.TextPrimary,
                        fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to sections", tint = BladeTile.TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.fillMaxHeight().fillMaxWidth(BladeTile.ListWidthFraction)
                        .widthIn(max = BladeTile.ListMaxWidth),
                ) {
                    items(category.settings, key = { it.key }) { setting ->
                        OverrideRow(
                            host = vm,
                            s = setting,
                            overrideValue = overrides[setting.key],
                            onOverrideToggle = { vm.setOverride(setting, it) },
                        )
                        HorizontalDivider(color = BladeTile.Border)
                    }
                }
            }
        }
    }
}
