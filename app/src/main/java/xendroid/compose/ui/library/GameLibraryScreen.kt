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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xendroid.compose.R
import xendroid.compose.ui.theme.BladeTile
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

    Box(Modifier.fillMaxSize()) {
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

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenSettings) {
                        Image(
                            painter = painterResource(R.drawable.ic_gear_blade),
                            contentDescription = "Settings",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Image(
                                painter = painterResource(R.drawable.ic_menu_blade),
                                contentDescription = "More",
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Only offered where All Files Access exists (API 30+); on API 29 the
                            // empty state explains why.
                            if (AllFilesAccess.isSupported) {
                                DropdownMenuItem(
                                    text = { Text("Set game folder") },
                                    onClick = { menuOpen = false; startRealPathMode() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Install content") },
                                onClick = { menuOpen = false; onOpenInstallContent() },
                            )
                            DropdownMenuItem(
                                text = { Text("Profiles") },
                                onClick = { menuOpen = false; onOpenProfiles() },
                            )
                            DropdownMenuItem(
                                text = { Text("Key mapping") },
                                onClick = { menuOpen = false; onOpenKeymap() },
                            )
                            DropdownMenuItem(
                                text = { Text("Touch controls") },
                                onClick = { menuOpen = false; onOpenTouchControls() },
                            )
                            DropdownMenuItem(
                                text = { Text("Open user data") },
                                onClick = {
                                    menuOpen = false
                                    openUserData(context)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { menuOpen = false; onOpenAbout() },
                            )
                            DropdownMenuItem(
                                text = { Text("Check for Updates") },
                                onClick = {
                                    menuOpen = false
                                    checkForUpdatesClicked(
                                        context = context,
                                        scope = scope,
                                        onResult = { updateResult = it }
                                    )
                                }
                            )
                        }
                    }
                }
            }
            // Blade-edge seam: a thin glowing line separating the header from the pager
            // below, echoing the tile borders instead of a flat Material app bar shadow.
            HorizontalDivider(thickness = 2.dp, color = BladeTile.Glow.copy(alpha = 0.6f))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val setFolderLabel =
                        if (allFilesGranted) "Set game folder" else "Grant All Files Access"
                    when (val s = state) {
                        LibraryUiState.NoVulkan ->
                            NoVulkanDialog(onQuit = { (context as? Activity)?.finish() })
                        LibraryUiState.Loading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        // All Files Access is API 30+; on API 29 there is no games path at all.
                        LibraryUiState.NoFolder ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (AllFilesAccess.isSupported)
                                    EmptyMessage("No game folder set", setFolderLabel,
                                        onAction = startRealPathMode)
                                else
                                    EmptyMessage(
                                        "Setting a game folder requires Android 11 or newer.",
                                        "OK", onAction = {})
                            }
                        LibraryUiState.PermissionLost ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyMessage("Folder access lost", setFolderLabel,
                                    onAction = startRealPathMode)
                            }
                        is LibraryUiState.Error ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyMessage(s.message, "Retry", onAction = { viewModel.refresh() })
                            }
                        is LibraryUiState.Loaded ->
                            if (s.games.isEmpty())
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    EmptyMessage("No games in this folder", "Choose another",
                                        onAction = startRealPathMode)
                                }
                            else BladeLibraryPager(
                                games = s.games,
                                viewModel = viewModel,
                                onPageChanged = { currentPage = it },
                                onLaunch = launchWithDiscCheck,
                                onDetails = { pendingGame = it },
                            )
                    }
                }
            }

            // Bottom action legend, mirroring the Xbox 360 A/X/Y control hints — tappable
            // here since touch has no physical controller to read them off of.
            val currentGame = loaded?.games?.getOrNull(currentPage)
            if (currentGame != null) {
                BladeButtonLegend(
                    onLaunch = { launchWithDiscCheck(currentGame) },
                    onDetails = { pendingGame = currentGame },
                    onOptions = { menuOpen = true },
                )
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BladeLibraryPager(
    games: List<Game>,
    viewModel: GameLibraryViewModel,
    onPageChanged: (Int) -> Unit,
    onLaunch: (Game) -> Unit,
    onDetails: (Game) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { games.size })
    LaunchedEffect(pagerState.currentPage) { onPageChanged(pagerState.currentPage) }

    HorizontalPager(
        state = pagerState,
        pageSpacing = 16.dp,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val game = games[page]
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            BladeGameCard(
                game = game,
                viewModel = viewModel,
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
            .width(260.dp)
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
                .height(42.dp)
                .clip(RoundedCornerShape(topStart = BladeTile.TileCorner, topEnd = BladeTile.TileCorner)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(BladeTile.Surface),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(iconModel).build(),
                contentDescription = game.name,
                modifier = Modifier.size(160.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(BladeTile.SurfaceRaised)
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
private fun BladeButtonLegend(
    onLaunch: () -> Unit,
    onDetails: () -> Unit,
    onOptions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
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
