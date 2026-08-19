package xendroid.compose.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status/nav bars for as long as this is composed, letting the dashboard read
 * edge to edge like the real console UI. A swipe from either edge reveals them temporarily
 * (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) — the user brings them back manually, the app
 * never forces them to stay visible.
 *
 * Called ONCE at the app root (MainActivity, alongside AppNavHost), not per-screen. All
 * screens share one Activity/View, so a per-screen call only restored the bars when THAT
 * screen left composition — any other screen without its own call inherited whatever the
 * previous screen last left the bars as, and once shown they'd stay shown for the rest of
 * the session. Hoisting it here applies immersive mode uniformly regardless of which
 * screen is on-screen, so don't re-add per-screen calls.
 *
 * No WindowCompat.setDecorFitsSystemWindow() call: targetSdk 35 (Android 15) makes
 * edge-to-edge the default for the whole app, and that method was removed from current
 * androidx.core because of it — the insets controller alone is enough to hide/show the bars.
 */
@Composable
fun ImmersiveSystemBars() {
    val context = LocalContext.current
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
}
