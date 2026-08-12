package xendroid.compose.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import xendroid.compose.core.EmulatorSession

/**
 * Maps Android input devices onto emulator device slots, and keeps edge-detection
 * state per pad: one shared set let two pads cancel each other's presses.
 *
 * Main thread only: input events and InputManager callbacks both arrive there.
 */
class ControllerRegistry(private val session: EmulatorSession) {

    class PadState(val deviceSlot: Int) {
        val axisPressed = BooleanArray(24)
        val axisValue = IntArray(24) { Int.MIN_VALUE }
        var lTriggerDown = false
        var rTriggerDown = false
        var hatLeft = false
        var hatUp = false
        var hatRight = false
        var hatDown = false
    }

    private val pads = HashMap<Int, PadState>()
    // Keyboards/remotes raise key events too; remembered so every keystroke does
    // not re-query InputDevice.
    private val nonPads = HashSet<Int>()
    private var touchSlot = -1
    private var listener: InputManager.InputDeviceListener? = null
    private var inputManager: InputManager? = null

    /** Attached up front so touch works with no pad. */
    fun touchSlot(): Int {
        if (touchSlot < 0) {
            touchSlot = session.attachInputDevice(TOUCH_ID, "On-screen controls", SUBTYPE_GAMEPAD, 0)
        }
        return touchSlot
    }

    fun start(context: Context) {
        touchSlot()
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        inputManager = manager
        refresh()
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { padFor(deviceId) }
            override fun onInputDeviceRemoved(deviceId: Int) { remove(deviceId) }
            override fun onInputDeviceChanged(deviceId: Int) {}
        }
        listener = l
        manager.registerInputDeviceListener(l, null)
    }

    fun stop() {
        listener?.let { inputManager?.unregisterInputDeviceListener(it) }
        listener = null
        inputManager = null
    }

    /** Attach is a no-op once a device is known, and retried while boot is pending. */
    fun refresh() {
        InputDevice.getDeviceIds().forEach { padFor(it) }
    }

    fun padFor(deviceId: Int): PadState? {
        pads[deviceId]?.let { return it }
        if (deviceId in nonPads) return null
        val device = InputDevice.getDevice(deviceId) ?: return null
        if (device.isVirtual || !isGamepad(device)) {
            nonPads.add(deviceId)
            return null
        }
        val stableId = device.descriptor ?: "android-pad-$deviceId"
        val slot = session.attachInputDevice(stableId, device.name ?: "Controller", SUBTYPE_GAMEPAD, -1)
        if (slot < 0) return null
        val state = PadState(slot)
        pads[deviceId] = state
        // First physical pad takes player 1 from the overlay, which would
        // otherwise hold slot 0 and push the pad to player 2.
        if (pads.size == 1) {
            session.bindInputSlot(0, slot)
        }
        return state
    }

    private fun remove(deviceId: Int) {
        nonPads.remove(deviceId)
        val state = pads.remove(deviceId) ?: return
        session.detachInputDevice(state.deviceSlot)
        if (pads.isEmpty() && touchSlot >= 0) {
            session.bindInputSlot(0, touchSlot)
        }
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private companion object {
        const val TOUCH_ID = "android-touch"
        const val SUBTYPE_GAMEPAD = 0x01
    }
}
