package buddy.android.automation

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import buddy.android.BuddyApp
import buddy.android.capture.BuddyContentCaptureService
import buddy.automation.Driver
import buddy.automation.NodeRef
import buddy.perception.capture.CaptureSnapshot

/**
 * Input injection through the framework (docs/01-architecture.md, "Actuation", path 3).
 * INJECT_EVENTS is a signature permission the platform-signed app holds; the method is
 * a platform API, available because the app compiles with platform_apis. No
 * Accessibility service sits in the loop.
 */
class InputInjector(context: Context) {
    private val im = context.getSystemService(InputManager::class.java)

    fun tap(x: Int, y: Int): Boolean {
        val down = SystemClock.uptimeMillis()
        val a = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val b = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return inject(a) && inject(b)
    }

    fun key(keyCode: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        return inject(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0)) && inject(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** Types text as key events. Adequate for short fields; longer text goes through a paste in a later phase. */
    fun type(text: String): Boolean {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray()) ?: return false
        return events.all { inject(it) }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })) return false
        val steps = 10
        for (i in 1..steps) {
            val t = down + durationMs * i / steps
            val x = x1 + (x2 - x1) * i / steps
            val y = y1 + (y2 - y1) * i / steps
            if (!inject(MotionEvent.obtain(down, t, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })) return false
        }
        return inject(MotionEvent.obtain(down, down + durationMs, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })
    }

    private fun inject(e: android.view.InputEvent): Boolean = try {
        // Platform API: InputManager.injectInputEvent(event, mode). Mode 2 waits for the event to finish.
        val m = InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        m.invoke(im, e, 2) as Boolean
    } catch (t: Throwable) {
        Log.w(BuddyApp.TAG, "inject failed", t)
        false
    }
}

/**
 * The automation driver on the phone: content capture for the screen, input injection
 * for the fingers. Screen reads are the latest snapshot the capture service has for
 * the package, which is fresh within the capture settle window.
 */
class CaptureDriver(private val context: Context, private val screenWidth: Int, private val screenHeight: Int) : Driver {
    private val input = InputInjector(context)
    private var currentPackage: String? = null

    override fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        currentPackage = packageName
        return true
    }

    override fun screen(): CaptureSnapshot? = currentPackage?.let { BuddyContentCaptureService.latest[it] }

    override fun tap(ref: NodeRef): Boolean {
        val b = ref.node.bounds ?: return false
        return input.tap(b.centerX, b.centerY)
    }

    override fun type(ref: NodeRef, text: String): Boolean = tap(ref) && run { sleep(200); input.type(text) }

    override fun scroll(down: Boolean): Boolean {
        val x = screenWidth / 2
        return if (down) input.swipe(x, screenHeight * 3 / 4, x, screenHeight / 4) else input.swipe(x, screenHeight / 4, x, screenHeight * 3 / 4)
    }

    override fun back(): Boolean = input.key(KeyEvent.KEYCODE_BACK)
    override fun sleep(ms: Long) = Thread.sleep(ms)
    override fun now(): Long = SystemClock.uptimeMillis()
}
