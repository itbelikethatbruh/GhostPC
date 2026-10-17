package com.radiant.pcbrowser

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Normalises every Bluetooth/USB controller (Xbox, DualShock/DualSense, Backbone, 8BitDo, generic HID)
 * plus the on-screen touch gamepad into the W3C "standard" layout: 17 buttons, 4 axes.
 *
 * Android's key layout files already map known controllers to KEYCODE_BUTTON_* codes; the remaining
 * OEM differences are in the axes (right stick on Z/RZ or RX/RY, triggers on LTRIGGER/RTRIGGER,
 * BRAKE/GAS, or signed RX/RY on older DualShock mappings, D-pad as keys or as a HAT), detected per device.
 *
 * Global combos: Select+Start or Guide opens/closes the command drawer, L3+R3 toggles the virtual cursor.
 */
class ControllerManager(context: Context, private val listener: Listener) :
    InputManager.InputDeviceListener, Choreographer.FrameCallback {

    interface Listener {
        val isDrawerOpen: Boolean
        val isCursorActive: Boolean
        fun onToggleDrawer()
        fun onToggleCursor()
        fun onControllersChanged()
        fun onPadState(json: String)
        fun onCursorFrame(dtSeconds: Float, state: State)
        fun onCursorButton(index: Int, down: Boolean)
        fun onDrawerButton(index: Int, down: Boolean)
    }

    /** Merged state of all controllers, standard-mapping order. */
    class State {
        val buttons = FloatArray(BUTTONS)
        val axes = FloatArray(AXES)
        fun pressed(i: Int): Boolean = buttons[i] > 0.5f
    }

    private class Trigger(val axis: Int, val signed: Boolean)

    private class AxisMap(
        val rx: Int,
        val ry: Int,
        val lt: Array<Trigger>,
        val rt: Array<Trigger>,
        val hat: Boolean
    )

    private class Pad(val deviceId: Int, val name: String, val map: AxisMap?) {
        var index = -1
        val keyButtons = FloatArray(BUTTONS)
        val axisButtons = FloatArray(BUTTONS)
        val axes = FloatArray(AXES)
        fun button(i: Int): Float = max(keyButtons[i], axisButtons[i])
        fun clear() {
            keyButtons.fill(0f)
            axisButtons.fill(0f)
            axes.fill(0f)
        }
    }

    private val inputManager: InputManager = context.getSystemService(InputManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pads = LinkedHashMap<Int, Pad>()
    private val connected = LinkedHashMap<Int, String>()
    private val routedDown = BooleanArray(BUTTONS)

    val state = State()

    private var suppressMask = 0
    private var dirty = true
    private var lastSentNeutral = false
    private var running = false
    private var started = false
    private var lastFrameNanos = 0L

    val controllerNames: List<String> get() = connected.values.toList()

    fun hasPhysicalController(): Boolean = connected.isNotEmpty()

    fun start() {
        if (started) return
        started = true
        inputManager.registerInputDeviceListener(this, mainHandler)
        rescan()
        ensureLoop()
    }

    fun stop() {
        if (!started) return
        resetStates()
        listener.onPadState(buildJson(true))
        started = false
        running = false
        inputManager.unregisterInputDeviceListener(this)
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Forget everything currently held (used when the drawer opens/closes or the app pauses). */
    fun resetStates() {
        for (p in pads.values) p.clear()
        suppressMask = 0
        routedDown.fill(false)
        recompute()
        markDirty()
    }

    fun markDirty() {
        dirty = true
        ensureLoop()
    }

    // ------------------------------------------------------------------------ device tracking

    override fun onInputDeviceAdded(deviceId: Int) = rescan()

    override fun onInputDeviceRemoved(deviceId: Int) {
        pads.remove(deviceId)
        rescan()
    }

    override fun onInputDeviceChanged(deviceId: Int) = rescan()

    private fun rescan() {
        connected.clear()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (isController(d)) connected[id] = d.name ?: "Controller"
        }
        val it = pads.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != TOUCH_DEVICE_ID && !connected.containsKey(e.key)) it.remove()
        }
        assignIndices()
        recompute()
        listener.onControllersChanged()
        markDirty()
    }

    private fun isController(d: InputDevice): Boolean {
        if (d.isVirtual) return false
        val s = d.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun padFor(device: InputDevice): Pad {
        pads[device.id]?.let { return it }
        val pad = Pad(device.id, device.name ?: "Controller", detectAxes(device))
        pads[device.id] = pad
        if (!connected.containsKey(device.id)) connected[device.id] = pad.name
        assignIndices()
        listener.onControllersChanged()
        ensureLoop()
        return pad
    }

    /**
     * Physical controllers keep their slot; the on-screen pad always takes the first slot after them,
     * so XInput games that only read getGamepads()[0] get the real controller.
     */
    private fun assignIndices() {
        val touch = pads[TOUCH_DEVICE_ID]
        touch?.index = -1
        val used = HashSet<Int>()
        for (p in pads.values) if (p !== touch && p.index in 0..3) used.add(p.index)
        for (p in pads.values) {
            if (p === touch || p.index in 0..3) continue
            val free = (0..3).firstOrNull { it !in used } ?: continue
            p.index = free
            used.add(free)
        }
        if (touch != null) {
            // Compact physical slots so the touch pad never sits in front of a real controller.
            val physical = pads.values.filter { it !== touch && it.index in 0..3 }.sortedBy { it.index }
            physical.forEachIndexed { i, p -> p.index = i }
            touch.index = if (physical.size < 4) physical.size else -1
        }
    }

    private fun detectAxes(d: InputDevice): AxisMap? {
        fun range(axis: Int): InputDevice.MotionRange? = d.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
        if (range(MotionEvent.AXIS_X) == null && range(MotionEvent.AXIS_HAT_X) == null) return null

        var rx = -1
        var ry = -1
        val z = range(MotionEvent.AXIS_Z)
        val rz = range(MotionEvent.AXIS_RZ)
        // Z/RZ is the right stick only when it is a signed (centred) axis; generic HID pads put the
        // analog triggers there instead (range 0..1).
        val zIsStick = z != null && rz != null && z.min < -0.5f
        val zIsTriggers = z != null && rz != null && !zIsStick
        if (zIsStick) {
            rx = MotionEvent.AXIS_Z
            ry = MotionEvent.AXIS_RZ
        } else if (range(MotionEvent.AXIS_RX) != null && range(MotionEvent.AXIS_RY) != null) {
            rx = MotionEvent.AXIS_RX
            ry = MotionEvent.AXIS_RY
        }

        val lt = ArrayList<Trigger>()
        val rt = ArrayList<Trigger>()
        fun addTrigger(list: MutableList<Trigger>, axis: Int) {
            val r = range(axis) ?: return
            list.add(Trigger(axis, r.min < -0.5f))
        }
        addTrigger(lt, MotionEvent.AXIS_LTRIGGER)
        addTrigger(lt, MotionEvent.AXIS_BRAKE)
        addTrigger(rt, MotionEvent.AXIS_RTRIGGER)
        addTrigger(rt, MotionEvent.AXIS_GAS)
        if (lt.isEmpty() && rt.isEmpty() && zIsTriggers) {
            addTrigger(lt, MotionEvent.AXIS_Z)
            addTrigger(rt, MotionEvent.AXIS_RZ)
        }
        if (lt.isEmpty() && rt.isEmpty() && rx == MotionEvent.AXIS_Z) {
            // Legacy DualShock mapping: right stick on Z/RZ, analog triggers on signed RX/RY.
            addTrigger(lt, MotionEvent.AXIS_RX)
            addTrigger(rt, MotionEvent.AXIS_RY)
        }
        val hat = range(MotionEvent.AXIS_HAT_X) != null && range(MotionEvent.AXIS_HAT_Y) != null
        return AxisMap(rx, ry, lt.toTypedArray(), rt.toTypedArray(), hat)
    }

    // ------------------------------------------------------------------------ input events

    /** @return true when the event was a controller event that has been consumed. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!isController(device)) return false
        val idx = keyToButton(event.keyCode)
        if (idx < 0) return false
        // In the drawer the D-pad keys reach the view system so Android's focus navigation works.
        if (listener.isDrawerOpen && idx in 12..15) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) return true
        val pad = padFor(device)
        pad.keyButtons[idx] = if (down) 1f else 0f
        onInputChanged()
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val device = event.device ?: return false
        if (!isController(device)) return false
        // In the drawer, sticks and HAT fall through and Android turns them into focus navigation.
        if (listener.isDrawerOpen) return false
        val pad = padFor(device)
        val map = pad.map ?: return true
        pad.axes[0] = event.getAxisValue(MotionEvent.AXIS_X)
        pad.axes[1] = event.getAxisValue(MotionEvent.AXIS_Y)
        pad.axes[2] = if (map.rx >= 0) event.getAxisValue(map.rx) else 0f
        pad.axes[3] = if (map.ry >= 0) event.getAxisValue(map.ry) else 0f
        pad.axisButtons[6] = triggerValue(event, map.lt)
        pad.axisButtons[7] = triggerValue(event, map.rt)
        if (map.hat) {
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            pad.axisButtons[12] = if (hy < -0.5f) 1f else 0f
            pad.axisButtons[13] = if (hy > 0.5f) 1f else 0f
            pad.axisButtons[14] = if (hx < -0.5f) 1f else 0f
            pad.axisButtons[15] = if (hx > 0.5f) 1f else 0f
        }
        onInputChanged()
        return true
    }

    private fun triggerValue(e: MotionEvent, triggers: Array<Trigger>): Float {
        var v = 0f
        for (t in triggers) {
            var x = e.getAxisValue(t.axis)
            if (t.signed) x = (x + 1f) * 0.5f
            if (x > v) v = x
        }
        return v.coerceIn(0f, 1f)
    }

    private fun keyToButton(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 6
        KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> 8
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12
        KeyEvent.KEYCODE_DPAD_DOWN -> 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 14
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_MODE -> 16
        else -> -1
    }

    // ------------------------------------------------------------------------ on-screen gamepad

    fun setVirtualPadEnabled(enabled: Boolean) {
        if (enabled) {
            if (!pads.containsKey(TOUCH_DEVICE_ID)) {
                pads[TOUCH_DEVICE_ID] = Pad(TOUCH_DEVICE_ID, "On-screen gamepad", null)
                assignIndices()
            }
        } else {
            pads.remove(TOUCH_DEVICE_ID)
        }
        recompute()
        listener.onControllersChanged()
        markDirty()
    }

    fun updateVirtualPad(buttons: FloatArray, axes: FloatArray) {
        val p = pads[TOUCH_DEVICE_ID] ?: return
        System.arraycopy(buttons, 0, p.keyButtons, 0, BUTTONS)
        System.arraycopy(axes, 0, p.axes, 0, AXES)
        onInputChanged()
        ensureLoop()
    }

    // ------------------------------------------------------------------------ combos and routing

    private fun onInputChanged() {
        dirty = true
        ensureLoop()
        recompute()
        val b = state.buttons
        for (i in 0 until BUTTONS) {
            val bit = 1 shl i
            if ((suppressMask and bit) != 0 && b[i] <= 0.5f) suppressMask = suppressMask and bit.inv()
        }
        if (b[8] > 0.5f && b[9] > 0.5f && (suppressMask and COMBO_DRAWER) != COMBO_DRAWER) {
            suppressMask = suppressMask or COMBO_DRAWER
            releaseRouted()
            listener.onToggleDrawer()
            return
        }
        if (b[10] > 0.5f && b[11] > 0.5f && (suppressMask and COMBO_CURSOR) != COMBO_CURSOR) {
            suppressMask = suppressMask or COMBO_CURSOR
            releaseRouted()
            listener.onToggleCursor()
            return
        }
        if (b[16] > 0.5f && (suppressMask and GUIDE_BIT) == 0) {
            suppressMask = suppressMask or GUIDE_BIT
            releaseRouted()
            listener.onToggleDrawer()
            return
        }
        route()
    }

    /** Delivers button edges to the drawer or the virtual cursor (the game gets the full state per frame). */
    private fun route() {
        val drawer = listener.isDrawerOpen
        val cursor = !drawer && listener.isCursorActive
        for (i in 0 until BUTTONS) {
            val down = state.buttons[i] > 0.5f && (suppressMask and (1 shl i)) == 0
            if (down == routedDown[i]) continue
            routedDown[i] = down
            if (drawer) listener.onDrawerButton(i, down) else if (cursor) listener.onCursorButton(i, down)
        }
    }

    private fun releaseRouted() {
        val drawer = listener.isDrawerOpen
        val cursor = !drawer && listener.isCursorActive
        for (i in 0 until BUTTONS) {
            if (!routedDown[i]) continue
            routedDown[i] = false
            if (drawer) listener.onDrawerButton(i, false) else if (cursor) listener.onCursorButton(i, false)
        }
    }

    private fun recompute() {
        state.buttons.fill(0f)
        state.axes.fill(0f)
        var lMag = 0f
        var rMag = 0f
        for (p in pads.values) {
            for (i in 0 until BUTTONS) {
                val v = p.button(i)
                if (v > state.buttons[i]) state.buttons[i] = v
            }
            val l = hypot(p.axes[0], p.axes[1])
            if (l > lMag) {
                lMag = l
                state.axes[0] = p.axes[0]
                state.axes[1] = p.axes[1]
            }
            val r = hypot(p.axes[2], p.axes[3])
            if (r > rMag) {
                rMag = r
                state.axes[2] = p.axes[2]
                state.axes[3] = p.axes[3]
            }
        }
    }

    // ------------------------------------------------------------------------ frame loop

    private fun ensureLoop() {
        if (!started || running) return
        if (pads.isEmpty() && !dirty) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos

        val drawer = listener.isDrawerOpen
        val cursor = listener.isCursorActive
        if (cursor && !drawer) listener.onCursorFrame(dt, state)

        val neutral = drawer || cursor
        if (dirty || neutral != lastSentNeutral) {
            dirty = false
            lastSentNeutral = neutral
            listener.onPadState(buildJson(neutral))
        }
        // Idle pads cost nothing: stop until the next input event (markDirty / onInputChanged restart it).
        if (pads.isEmpty() || !(cursor && !drawer)) {
            running = false
            return
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Compact message for the JS engine; values are integers in thousandths. */
    private fun buildJson(neutral: Boolean): String {
        val sb = StringBuilder(320)
        sb.append("{\"t\":\"pad\",\"c\":").append(if (listener.isCursorActive) 1 else 0)
        sb.append(",\"s\":").append(suppressMask)
        sb.append(",\"p\":[")
        var first = true
        for (p in pads.values) {
            if (p.index !in 0..3) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"i\":").append(p.index)
            sb.append(",\"n\":").append(JSONObject.quote(p.name))
            sb.append(",\"b\":[")
            for (i in 0 until BUTTONS) {
                if (i > 0) sb.append(',')
                val suppressed = (suppressMask and (1 shl i)) != 0
                val v = if (neutral || suppressed) 0 else (p.button(i) * 1000f).roundToInt().coerceIn(0, 1000)
                sb.append(v)
            }
            sb.append("],\"a\":[")
            for (i in 0 until AXES) {
                if (i > 0) sb.append(',')
                val v = if (neutral) 0 else (p.axes[i] * 1000f).roundToInt().coerceIn(-1000, 1000)
                sb.append(v)
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    // ------------------------------------------------------------------------ rumble

    fun rumble(index: Int, durationMs: Long, strong: Float, weak: Float) {
        val pad = pads.values.firstOrNull { it.index == index && it.deviceId != TOUCH_DEVICE_ID } ?: return
        val device = InputDevice.getDevice(pad.deviceId) ?: return
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            device.vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            device.vibrator
        }
        if (!vibrator.hasVibrator()) return
        val s = if (strong.isFinite()) strong.coerceIn(0f, 1f) else 0f
        val w = if (weak.isFinite()) weak.coerceIn(0f, 1f) else 0f
        val amplitude = (max(s, w) * 255f).roundToInt().coerceIn(0, 255)
        if (durationMs <= 0L || amplitude == 0) {
            vibrator.cancel()
            return
        }
        val amp = if (vibrator.hasAmplitudeControl()) amplitude.coerceAtLeast(1) else VibrationEffect.DEFAULT_AMPLITUDE
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(1L, 5000L), amp))
        } catch (_: RuntimeException) {
            // Some controller drivers reject effects; rumble is best-effort.
        }
    }

    companion object {
        const val BUTTONS = 17
        const val AXES = 4
        const val TOUCH_DEVICE_ID = -100
        private const val COMBO_DRAWER = (1 shl 8) or (1 shl 9)
        private const val COMBO_CURSOR = (1 shl 10) or (1 shl 11)
        private const val GUIDE_BIT = 1 shl 16
    }
}
