package xendroid.compose.ui.library

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xendroid.compose.R
import xendroid.compose.ui.theme.BladeTile
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xendroid.compose.core.AllFilesAccess
import xendroid.compose.core.EmuProcessLink
import xendroid.compose.data.Game
import xendroid.compose.data.GameFormat
import xendroid.compose.ui.compress.GameCompressViewModel
import xendroid.compose.ui.compress.GameCompressViewModel.CompressState
import xendroid.compose.ui.userdata.openUserData
import xendroid.compose.updater.CooldownDialog
import xendroid.compose.updater.getRemainingCooldown
import xendroid.compose.updater.LatestVersionDialog
import xendroid.compose.updater.UpdateDialog
import xendroid.compose.updater.UpdateResult
import xendroid.compose.updater.checkForUpdates
import xendroid.compose.updater.shouldCheckForUpdates
import xendroid.compose.updater.saveLastCheck


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    viewModel: GameLibraryViewModel,
    onOpenSettings: () -> Unit,
    onOpenKeymap: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenTouchControls: () -> Unit,
    onOpenPerGameSettings: (titleId: String, gameName: String, format: GameFormat, launchUri: String) -> Unit,
    onOpenGamePatches: (titleId: String, gameName: String) -> Unit,
    onOpenContentManager: (titleId: String, gameName: String) -> Unit,
    onOpenInstallContent: () -> Unit,
    onInstallFromDisc: (String) -> Unit,
    compressVm: GameCompressViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateResult by remember { mutableStateOf<UpdateResult?>(null) }

    var pendingGame by remember { mutableStateOf<Game?>(null) }
    // A disc whose content is not installed yet; the launch waits on the answer.
    var pendingDiscInstall by remember { mutableStateOf<Pair<Game, Int>?>(null) }
    var compressConfirmFor by remember { mutableStateOf<Game?>(null) }
    val compressState by compressVm.state.collectAsStateWithLifecycle()
    val titleIdState by viewModel.titleIdState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    var showBrowser by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(AllFilesAccess.isGranted()) }
    var menuOpen by remember { mutableStateOf(false) }
    // Tracks which single game the Blades-style pager currently has focused, so the
    // header "N of M" line and the A/X/Y legend stay in sync with the visible card.
    var currentPage by remember { mutableStateOf(0) }
    val launchWithDiscCheck: (Game) -> Unit = { game ->
        scope.launch {
            // A mandatory-install disc is still bootable, so this asks rather than
            // diverting the launch on its own.
            val pending = viewModel.uninstalledDiscContent(game)
            if (pending.isNotEmpty()) {
                pendingDiscInstall = game to pending.size
            } else {
                launchGame(context, viewModel, game)
            }
        }
    }
    // Not-yet-granted sends the user to Settings; the grant returns no result, so it is
    // observed on the next ON_START.
    val startRealPathMode: () -> Unit = {
        if (AllFilesAccess.isGranted()) showBrowser = true
        else AllFilesAccess.requestAccess(context)
    }

    // Re-scan on return to foreground to pick up games added while backgrounded. The
    // ViewModel's init does the first cold-start load, so the first ON_START is skipped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstStart = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                allFilesGranted = AllFilesAccess.isGranted()
                if (firstStart) firstStart = false else viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive by default: status/nav bars stay hidden so the dashboard reads edge to
    // edge like the real console UI. A swipe from either edge reveals them temporarily
    // (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) — the user brings them back manually,
    // the app never forces them to stay visible. Restored on leaving this screen so
    // other screens (dialogs, settings) aren't silently left in immersive mode too.
    //
    // No WindowCompat.setDecorFitsSystemWindow() call here: targetSdk 35 (Android 15)
    // makes edge-to-edge the default for the whole app, so there's nothing left to
    // "opt out" of — that method was removed from current androidx.core because of it.
    // The insets controller alone is enough to hide/show the bars.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose {}
        }
    }

    if (showBrowser) {
        FolderBrowserScreen(
            onFolderChosen = { path ->
                showBrowser = false
                viewModel.onRealPathFolderPicked(path)
            },
            onCancel = { showBrowser = false },
        )
        return
    }

    // BoxWithConstraints instead of Box: the card is sized as a FRACTION of the real
    // screen (matched against the Xbox 360 dashboard reference image, where the tile
    // is ~23% of screen width) rather than a fixed dp, which was the actual bug behind
    // "too big" — a fixed 260.dp card is a small sliver on a wide landscape screen but
    // balloons to ~40%+ of the width on a narrower one. Fraction-based sizing looks
    // right regardless of the device or which way it's rotated.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Width alone isn't enough: a landscape screen is wide but short, so a
        // width-only card could be taller than the screen has room for once the
        // header and BladeBottomBar are accounted for — which was exactly why the
        // A/X/Y legend was going off the bottom edge in landscape. Reserve that
        // fixed chrome first, then size the card against whichever dimension is
        // tighter.
        val reservedChromeHeight = 160.dp
        val cardWidthByWidth = maxWidth * 0.24f
        val cardWidthByHeight = (maxHeight - reservedChromeHeight).coerceAtLeast(0.dp) / BladeCardAspect
        val cardWidth = minOf(cardWidthByWidth, cardWidthByHeight).coerceIn(150.dp, 300.dp)

        // Ambient blade backdrop, full bleed behind the status bar too, matching the
        // Xbox 360 dashboard's edge-to-edge glow instead of sitting under a flat app bar.
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
            val loaded = state as? LibraryUiState.Loaded
            val pageCount = loaded?.games?.size ?: 0
            val currentGame = loaded?.games?.getOrNull(currentPage)

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = BladeTile.TextPrimary,
                )
                if (pageCount > 0) {
                    Text(
                        "${currentPage + 1} of $pageCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BladeTile.TextSecondary,
                    )
                }
            }
            // No header divider and no top-right gear/menu icons here: the reference
            // Xbox 360 dashboard has neither. Settings/More live in the bottom-right
            // pill instead (BladeBottomBar, below).

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    val setFolderLabel =
                        if (allFilesGranted) "Set game folder" else "Grant All Files Access"
                    when (val s = state) {
                        LibraryUiState.NoVulkan ->
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                NoVulkanDialog(onQuit = { (context as? Activity)?.finish() })
                            }
                        LibraryUiState.Loading ->
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        // All Files Access is API 30+; on API 29 there is no games path at all.
                        LibraryUiState.NoFolder ->
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (AllFilesAccess.isSupported)
                                    EmptyMessage("No game folder set", setFolderLabel,
                                        onAction = startRealPathMode)
                                else
                                    EmptyMessage(
                                        "Setting a game folder requires Android 11 or newer.",
                                        "OK", onAction = {})
                            }
                        LibraryUiState.PermissionLost ->
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyMessage("Folder access lost", setFolderLabel,
                                    onAction = startRealPathMode)
                            }
                        is LibraryUiState.Error ->
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyMessage(s.message, "Retry", onAction = { viewModel.refresh() })
                            }
                        is LibraryUiState.Loaded ->
                            if (s.games.isEmpty())
                                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                    EmptyMessage("No games in this folder", "Choose another",
                                        onAction = startRealPathMode)
                                }
                            else {
                                // Sized off cardWidth (a screen fraction, see above) and the
                                // card's own aspect ratio, rather than a fixed height or
                                // weight(1f) — the pager should hug the card like the real
                                // dashboard's tile does, not stretch to fill the screen and
                                // shove the A/X/Y legend down to the very bottom.
                                BladeLibraryPager(
                                    games = s.games,
                                    viewModel = viewModel,
                                    cardWidth = cardWidth,
                                    onPageChanged = { currentPage = it },
                                    onLaunch = launchWithDiscCheck,
                                    onDetails = { pendingGame = it },
                                    modifier = Modifier.height(cardWidth * BladeCardAspect + 16.dp),
                                )
                            }
                    }
                }
            }

            // Fixed bottom chrome, sibling to the weight(1f) pager area above rather than
            // flowing inside it — this is what actually guarantees it stays on-screen in
            // landscape instead of being pushed past the bottom edge once the pager grew
            // taller than available height. Mirrors the reference dashboard: A/X/Y legend
            // bottom-left, Settings/More Options pill bottom-right.
            BladeBottomBar(
                currentGame = currentGame,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onLaunch = { g -> launchWithDiscCheck(g) },
                onDetails = { g -> pendingGame = g },
                onOpenSettings = onOpenSettings,
                onSetGameFolder = { menuOpen = false; startRealPathMode() },
                onInstallContent = { menuOpen = false; onOpenInstallContent() },
                onProfiles = { menuOpen = false; onOpenProfiles() },
                onKeymap = { menuOpen = false; onOpenKeymap() },
                onTouchControls = { menuOpen = false; onOpenTouchControls() },
                onOpenUserData = { menuOpen = false; openUserData(context) },
                onAbout = { menuOpen = false; onOpenAbout() },
                onCheckForUpdates = {
                    menuOpen = false
                    checkForUpdatesClicked(
                        context = context,
                        scope = scope,
                        onResult = { updateResult = it },
                    )
                },
            )
        }
    }

    when (val result = updateResult) {
        is UpdateResult.Available -> {
            UpdateDialog(
                release = result.release,
                onDismiss = { updateResult = null }
            )
        }

        is UpdateResult.Latest -> {
            LatestVersionDialog(
                commitHash = result.commitHash,
                onDismiss = { updateResult = null }
            )
        }

        is UpdateResult.Cooldown -> {
            CooldownDialog(
                remainingMillis = result.remainingMillis,
                onDismiss = { updateResult = null }
            )
        }

        null -> {}
    }

    pendingDiscInstall?.let { (game, count) ->
        AlertDialog(
            onDismissRequest = { pendingDiscInstall = null },
            title = { Text("Install disc") },
            text = {
                Text("This disc carries $count content package(s) the game installs before " +
                     "it will run. Install them now, or boot the disc anyway?")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiscInstall = null
                    onInstallFromDisc(game.launchUri)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDiscInstall = null
                    launchGame(context, viewModel, game)
                }) { Text("Boot anyway") }
            },
        )
    }

    pendingGame?.let { game ->
        val dismiss = { pendingGame = null; viewModel.clearTitleIdRequest() }
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = dismiss,
            sheetState = sheetState,
        ) {
            Column {
                // The title-id status line shows ONLY while resolving or on error.
                val statusContent: (@Composable () -> Unit)? = when (val st = titleIdState) {
                    is TitleIdState.Loading -> ({
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reading title id…")
                        }
                    })
                    is TitleIdState.Error -> ({ Text(st.message) })
                    else -> null
                }
                ListItem(
                    headlineContent = {
                        Text(game.name, style = MaterialTheme.typography.titleLarge)
                        if (game.isMultiDisc) {
                            Text(
                                "Disc ${game.discNumber} of ${game.discCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    supportingContent = if (game.titleId != null || game.mediaId != null || statusContent != null) {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                game.titleId?.let { Text("Title ID: $it") }
                                game.mediaId?.let { Text("Media ID: $it") }
                                statusContent?.invoke()
                            }
                        }
                    } else {
                        null
                    },
                )

                val perGameEnabled = titleIdState !is TitleIdState.Loading
                ListItem(
                    headlineContent = { Text("Per-game settings") },
                    colors = if (perGameEnabled) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor =
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = perGameEnabled) {
                        viewModel.requestPerGameSettings(game)
                    },
                )

                ListItem(
                    headlineContent = { Text("Game patches") },
                    colors = if (perGameEnabled) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor =
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = perGameEnabled) {
                        viewModel.requestGamePatches(game)
                    },
                )

                ListItem(
                    headlineContent = { Text("Manage content") },
                    colors = if (perGameEnabled) {
                        ListItemDefaults.colors()
                    } else {
                        ListItemDefaults.colors(
                            headlineColor =
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    modifier = Modifier.clickable(enabled = perGameEnabled) {
                        viewModel.requestContentManager(game)
                    },
                )

                if (game.format == GameFormat.ISO) {
                    ListItem(
                        headlineContent = { Text("Compress to .zar") },
                        modifier = Modifier.clickable {
                            compressConfirmFor = game
                            pendingGame = null
                            viewModel.clearTitleIdRequest()
                        },
                    )
                }

                if (viewModel.canLaunchGames && viewModel.isPinShortcutSupported) {
                    ListItem(
                        headlineContent = { Text("Create shortcut") },
                        modifier = Modifier.clickable {
                            viewModel.createShortcut(game)
                            dismiss()
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(titleIdState) {
        (titleIdState as? TitleIdState.Resolved)?.let { r ->
            when (r.action) {
                GameAction.PER_GAME_SETTINGS ->
                    onOpenPerGameSettings(r.titleId, r.game.name, r.game.format, r.game.launchUri)
                GameAction.GAME_PATCHES ->
                    onOpenGamePatches(r.titleId, r.game.name)
                GameAction.MANAGE_CONTENT ->
                    onOpenContentManager(r.titleId, r.game.name)
            }
            pendingGame = null
            viewModel.clearTitleIdRequest()
        }
    }

    compressConfirmFor?.let { game ->
        AlertDialog(
            onDismissRequest = { compressConfirmFor = null },
            title = { Text("Compress to .zar?") },
            text = {
                Text(
                    "This packs the disc into a smaller .zar. The original .iso is left alone " +
                        "until the .zar is created and verified, and you are asked before it is " +
                        "deleted. The game stays in your library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    compressConfirmFor = null
                    compressVm.compress(game.launchUri)
                }) { Text("Compress") }
            },
            dismissButton = {
                TextButton(onClick = { compressConfirmFor = null }) { Text("Cancel") }
            },
        )
    }

    when (val s = compressState) {
        is CompressState.Busy -> AlertDialog(
            onDismissRequest = {},   // not cancelable while running
            title = { Text(s.message) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(s.progress * 100).toInt()}%  ·  this may take a while.")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("This may take a while.")
                    }
                }
            },
            confirmButton = {},
        )
        is CompressState.ConfirmDelete -> AlertDialog(
            // Dismissing keeps it: a stray tap outside must never delete the .iso.
            onDismissRequest = compressVm::keepIso,
            title = { Text("Delete the original .iso?") },
            text = {
                Text(
                    "“${s.zarName}” was created and verified. Deleting “${s.isoName}” " +
                        "frees ${formatBytes(s.isoBytes)}.")
            },
            confirmButton = {
                TextButton(onClick = compressVm::deleteIso) { Text("Delete .iso") }
            },
            dismissButton = { TextButton(onClick = compressVm::keepIso) { Text("Keep it") } },
        )
        is CompressState.Done -> AlertDialog(
            onDismissRequest = { compressVm.dismiss(); viewModel.refresh() },
            title = { Text("Done") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { compressVm.dismiss(); viewModel.refresh() }) { Text("OK") }
            },
        )
        is CompressState.Failed -> AlertDialog(
            onDismissRequest = compressVm::dismiss,
            title = { Text("Failed") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = compressVm::dismiss) { Text("OK") } },
        )
        else -> {}
    }
}

// Card proportions taken from the Xbox 360 dashboard reference: header strip, icon
// panel, and text footer all scale together off cardWidth so the tile keeps the same
// silhouette at any size instead of a fixed-height header/icon fighting a resized card.
private const val BladeHeaderFrac = 42f / 260f
private const val BladeIconBoxFrac = 220f / 260f
private const val BladeIconSizeFrac = 160f / 260f
private val BladeTextFooterHeight = 76.dp // title (up to 2 lines) + subtitle; not scaled — type size doesn't shrink with the card
val BladeCardAspect = BladeIconBoxFrac + BladeHeaderFrac + (BladeTextFooterHeight / 260.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BladeLibraryPager(
    games: List<Game>,
    viewModel: GameLibraryViewModel,
    cardWidth: Dp,
    onPageChanged: (Int) -> Unit,
    onLaunch: (Game) -> Unit,
    onDetails: (Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { games.size })
    LaunchedEffect(pagerState.currentPage) { onPageChanged(pagerState.currentPage) }

    HorizontalPager(
        state = pagerState,
        pageSpacing = 16.dp,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp),
        modifier = modifier.fillMaxWidth(),
    ) { page ->
        val game = games[page]
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            BladeGameCard(
                game = game,
                viewModel = viewModel,
                cardWidth = cardWidth,
                onLaunch = { onLaunch(game) },
                onDetails = { onDetails(game) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BladeGameCard(
    game: Game,
    viewModel: GameLibraryViewModel,
    cardWidth: Dp,
    onLaunch: () -> Unit,
    onDetails: () -> Unit,
) {
    val context = LocalContext.current
    // Once per card: the File.exists() stat must not run on every recomposition.
    val iconModel = remember(game.stableId) { viewModel.iconFileOrFallback(game) }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0.85f,
        animationSpec = tween(150),
        label = "cardGlow",
    )

    Column(
        Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(BladeTile.TileCorner))
            .background(BladeTile.Surface)
            .border(
                width = 2.dp,
                color = BladeTile.Glow.copy(alpha = glowAlpha),
                shape = RoundedCornerShape(BladeTile.TileCorner),
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onLaunch,
                onLongClick = onDetails,
            ),
    ) {
        // Header banner: the XboxDroid wordmark on its diagonal green sweep, standing in
        // for the "XBOX 360" title strip on the real dashboard's game tiles.
        Image(
            painter = painterResource(R.drawable.xboxdroid_wordmark),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardWidth * BladeHeaderFrac)
                .clip(RoundedCornerShape(topStart = BladeTile.TileCorner, topEnd = BladeTile.TileCorner)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardWidth * BladeIconBoxFrac)
                .background(BladeTile.Surface),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(iconModel).build(),
                contentDescription = game.name,
                modifier = Modifier.size(cardWidth * BladeIconSizeFrac),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(BladeTile.SurfaceRaised)
                .heightIn(min = BladeTextFooterHeight)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                game.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = BladeTile.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // A set shares one title, so the tiles would otherwise be identical.
                if (game.isMultiDisc) "Disc ${game.discNumber} of ${game.discCount}"
                else "XboxDroid Game",
                style = MaterialTheme.typography.bodySmall,
                color = BladeTile.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BladeBottomBar(
    currentGame: Game?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onLaunch: (Game) -> Unit,
    onDetails: (Game) -> Unit,
    onOpenSettings: () -> Unit,
    onSetGameFolder: () -> Unit,
    onInstallContent: () -> Unit,
    onProfiles: () -> Unit,
    onKeymap: () -> Unit,
    onTouchControls: () -> Unit,
    onOpenUserData: () -> Unit,
    onAbout: () -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentGame != null) {
            BladeButtonLegend(
                onLaunch = { onLaunch(currentGame) },
                onDetails = { onDetails(currentGame) },
                onOptions = { onMenuOpenChange(true) },
            )
        } else {
            Spacer(Modifier)
        }

        Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            SettingsMorePill(
                onSettings = onOpenSettings,
                onMore = { onMenuOpenChange(true) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                // Only offered where All Files Access exists (API 30+); on API 29 the
                // empty state explains why.
                if (AllFilesAccess.isSupported) {
                    DropdownMenuItem(text = { Text("Set game folder") }, onClick = onSetGameFolder)
                }
                DropdownMenuItem(text = { Text("Install content") }, onClick = onInstallContent)
                DropdownMenuItem(text = { Text("Profiles") }, onClick = onProfiles)
                DropdownMenuItem(text = { Text("Key mapping") }, onClick = onKeymap)
                DropdownMenuItem(text = { Text("Touch controls") }, onClick = onTouchControls)
                DropdownMenuItem(text = { Text("Open user data") }, onClick = onOpenUserData)
                DropdownMenuItem(text = { Text("About") }, onClick = onAbout)
                DropdownMenuItem(text = { Text("Check for Updates") }, onClick = onCheckForUpdates)
            }
        }
    }
}

/**
 * Bottom-right "Settings | More Options" pill, matching the reference dashboard's mouse/
 * touch shortcut bar (as distinct from the controller-style A/X/Y legend on the left).
 * Built from stock Material icons rather than the old ic_gear_blade/ic_menu_blade drawables
 * — those were a flat black gear and a malformed half-rendered hamburger glyph, neither of
 * which matched the sprite sheet's clean glowing-outline style this reaches for instead.
 */
@Composable
private fun SettingsMorePill(
    onSettings: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(BladeTile.Surface.copy(alpha = 0.75f))
            .border(1.dp, BladeTile.Glow.copy(alpha = 0.5f), RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillItem(Icons.Filled.Settings, "Settings", onSettings)
        Box(
            Modifier
                .height(20.dp)
                .width(1.dp)
                .background(BladeTile.Border),
        )
        PillItem(Icons.Filled.Menu, "More Options", onMore)
    }
}

@Composable
private fun PillItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = BladeTile.Glow, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = BladeTile.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BladeButtonLegend(
    onLaunch: () -> Unit,
    onDetails: () -> Unit,
    onOptions: () -> Unit,
) {
    // Wrap-content, not fillMaxWidth: the reference dashboard's A/X/Y legend sits left-
    // aligned under the tile at its natural width. Stretching it edge-to-edge on a
    // narrow screen was what forced "Options" to wrap onto two lines.
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendButton("A", Color(0xFF6FBE44), "Launch", onLaunch)
        LegendButton("X", Color(0xFF2C7FC1), "Game Details", onDetails)
        LegendButton("Y", Color(0xFFC79A1E), "Options", onOptions)
    }
}

@Composable
private fun LegendButton(letter: String, color: Color, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = BladeTile.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyMessage(
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun NoVulkanDialog(onQuit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onQuit,
        confirmButton = { TextButton(onClick = onQuit) { Text("Quit") } },
        title = { Text("Unsupported device") },
        text = { Text("This device has no Vulkan GPU; the emulator cannot run.") },
    )
}

fun checkForUpdatesClicked(
    context: Context,
    scope: CoroutineScope,
    onResult: (UpdateResult) -> Unit
) {
    scope.launch {
        if (!shouldCheckForUpdates(context)) {
            Log.d("Updater", "Skipping update check")
            onResult(UpdateResult.Cooldown(getRemainingCooldown(context)))
            return@launch
        }

        try {
            val result = checkForUpdates()
            saveLastCheck(context)
            onResult(result)
        } catch (e: Exception) {
            Log.e("Updater", "Failed to check updates", e)
        }
    }
}

/** Same shape as the content-install formatter, which is private to that file. */
private fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    do { v /= 1024.0; i++ } while (v >= 1024.0 && i < u.lastIndex)
    return "%.1f %s".format(v, u[i])
}

/** Reap any stale/orphaned :emu first (single-shot core). The new :emu links itself to the
 *  launcher by binding MainAliveService, so nothing rides on the Intent. */
private fun launchGame(context: Context, viewModel: GameLibraryViewModel, game: Game) {
    runCatching {
        EmuProcessLink.killStaleEmu(context)
        context.startActivity(viewModel.buildLaunchIntent(game))
    }
}
