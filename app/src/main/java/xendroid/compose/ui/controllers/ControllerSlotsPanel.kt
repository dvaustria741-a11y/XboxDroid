package xendroid.compose.ui.controllers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import xendroid.compose.ui.panel.GuestPanelOption
import xendroid.compose.ui.panel.GuestPanelOptions

/** One attached pad and the guest slot it feeds; slot -1 = not mapped. */
data class ControllerSlotRow(
    val deviceSlot: Int,
    val displayName: String,
    val guestSlot: Int,
)

fun slotLabel(guestSlot: Int): String =
    if (guestSlot in 0..3) "Player ${guestSlot + 1}" else "Not mapped"

/**
 * Maps attached controllers to guest slots. An in-window Surface, not a Dialog, for the same
 * reason as the disc panel: a Dialog takes window focus and trips the host's focus-loss pause.
 * [selected] is driven by the host because the D-pad arrives as hat axes that never reach a
 * composable; Close is the LAST option, index devices.size.
 */
@Composable
fun ControllerSlotsPanel(
    devices: List<ControllerSlotRow>,
    selected: Int,
    onCycleSlot: (ControllerSlotRow) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow taps meant for the game surface underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < 400.dp
        val outerPadding = if (compact) 8.dp else 24.dp
        val innerPadding = if (compact) 12.dp else 20.dp

        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight - outerPadding * 2)
                .padding(outerPadding),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(innerPadding)) {
                Text("Controllers", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select a controller to move it to the next player slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (devices.isEmpty()) {
                    Text(
                        "No controllers detected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                GuestPanelOptions(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    devices.forEachIndexed { index, device ->
                        GuestPanelOption(
                            label = "${device.displayName}  ·  ${slotLabel(device.guestSlot)}",
                            selected = index == selected,
                            onClick = { onCycleSlot(device) },
                        )
                    }
                }
                GuestPanelOptions(Modifier.padding(top = 12.dp)) {
                    GuestPanelOption(
                        label = "Close",
                        selected = selected == devices.size,
                        onClick = onClose,
                    )
                }
            }
        }
    }
}
