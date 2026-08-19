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
 * Hides the status/nav bars for as long as the calling screen is on-screen, letting the
 * dashboard read edge to edge like the real console UI. A swipe from either edge reveals
 * them temporarily (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) — the user brings them back
 * manually, the app never forces them to stay visible. Restored on leaving the screen so
 * the next screen isn't silently left in immersive mode if it hasn't opted in itself.
 *
 * Call once near the top of any full-screen composable (Library, Settings, etc.) that
 * should go edge to edge. No WindowCompat.setDecorFitsSystemWindow() call: targetSdk 35
 * (Android 15) makes edge-to-edge the default for the whole app, and that method was
 * removed from current androidx.core because of it — the insets controller alone is
 * enough to hide/show the bars.
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
