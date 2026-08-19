package xendroid.compose.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import xendroid.compose.settings.SettingValue
import xendroid.compose.settings.SettingsCategory
import xendroid.compose.settings.SettingsViewModel
import xendroid.compose.ui.ImmersiveSystemBars
import xendroid.compose.ui.theme.BladeTile

/**
 * Two-level settings: an INDEX of sections (the 124-entry schema is too long for one list), and
 * a per-section DETAIL with that section's rows. Navigation between the two is internal state so
 * the single SettingsViewModel (and its config handle / flush lifecycle) is shared; the system
 * back button goes detail->index->exit via [BackHandler].
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    ImmersiveSystemBars()
    val values by vm.values.collectAsStateWithLifecycle()

    // Durable flush on pause; re-open on resume. Dispose flush = backstop.
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
        SettingsIndex(
            categories = vm.categories,
            modifiedCountOf = { cat -> cat.settings.count { values[it.key]?.modified == true } },
            onOpen = { selected = it },
            onBack = { vm.flush(); onBack() },
        )
    } else {
        BackHandler { selected = null }
        SettingsCategoryDetail(
            category = section,
            values = values,
            vm = vm,
            onBack = { selected = null },
        )
    }
}

/**
 * Best-available icon per category. Only four category glyphs exist so far (settings/gear,
 * video/monitor, UI/grid, storage/folder) — everything else falls back to the gear rather than
 * going iconless, until matching chip/gamepad/list glyphs are drawn for the rest.
 */
private fun categoryIcon(title: String): Int = when (title) {
    "Video", "Display" -> R.drawable.ic_cat_monitor
    "UI" -> R.drawable.ic_cat_grid
    "Storage", "Content" -> R.drawable.ic_cat_folder
    else -> R.drawable.ic_cat_gear
}

@Composable
private fun SettingsIndex(
    categories: List<SettingsCategory>,
    modifiedCountOf: (SettingsCategory) -> Int,
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = BladeTile.TextPrimary,
                    )
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = BladeTile.TextPrimary,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(categories, key = { it.title }) { cat ->
                    val modified = modifiedCountOf(cat)
                    SettingsCategoryRow(
                        title = cat.title,
                        subtitle = buildString {
                            append("${cat.settings.size} settings")
                            if (modified > 0) append("  ·  $modified changed")
                        },
                        iconRes = categoryIcon(cat.title),
                        onClick = { onOpen(cat) },
                    )
                }
            }

            SettingsLegend(labelA = "Select", labelB = "Back", onB = onBack)
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    title: String,
    subtitle: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(150),
        label = "rowGlow",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BladeTile.TileCorner))
            .background(BladeTile.Surface)
            .border(
                width = 2.dp,
                color = BladeTile.Glow.copy(alpha = glowAlpha),
                shape = RoundedCornerShape(BladeTile.TileCorner),
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BladeTile.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = BladeTile.TextPrimary,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = BladeTile.TextSecondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = BladeTile.Glow,
        )
    }
}

@Composable
private fun SettingsLegend(labelA: String, labelB: String, onB: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendBadge("A", Color(0xFF6FBE44), labelA, onClick = {})
        LegendBadge("B", Color(0xFFC0392B), labelB, onClick = onB)
    }
}

@Composable
private fun LegendBadge(letter: String, color: Color, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = BladeTile.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCategoryDetail(
    category: SettingsCategory,
    values: Map<String, SettingValue>,
    vm: SettingsViewModel,
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
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(category.settings, key = { it.key }) { setting ->
                    val sv = values[setting.key]
                    SettingRow(vm, setting, modified = sv?.modified == true, raw = sv?.raw)
                    HorizontalDivider(color = BladeTile.Border)
                }
            }
        }
    }
}
