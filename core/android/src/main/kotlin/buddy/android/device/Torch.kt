package buddy.android.device

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import buddy.android.BuddyApp

/**
 * The torch, which buddy owns because nobody else does any more: the quick settings
 * panel is gone with the rest of the viewer's chrome (see
 * [buddy.android.surface.lockscreen.SystemChrome]), and the two switches it was still
 * carrying are switches buddy can throw himself.
 *
 * The framework keeps the real state, and it can change without us (a thermal cut-out,
 * another app), so the callback is the source of truth and [on] only mirrors it.
 */
object Torch {
    @Volatile
    private var manager: CameraManager? = null

    @Volatile
    private var id: String? = null

    @Volatile
    var on: Boolean = false
        private set

    fun init(context: Context) {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        manager = cm
        id = runCatching {
            cm.cameraIdList.firstOrNull { camera ->
                cm.getCameraCharacteristics(camera)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (id == null) {
            Log.i(BuddyApp.TAG, "no camera with a flash; the torch is not available")
            return
        }
        runCatching {
            cm.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == id) on = enabled
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId == id) on = false
                    }
                },
                null,
            )
        }.onFailure { Log.w(BuddyApp.TAG, "could not follow the torch", it) }
    }

    val available: Boolean get() = id != null

    /** Returns what the torch is now, which is unchanged if the phone refused. */
    fun set(enabled: Boolean): Boolean {
        val cm = manager
        val camera = id
        if (cm == null || camera == null) return false
        runCatching { cm.setTorchMode(camera, enabled) }
            .onSuccess { on = enabled }
            .onFailure { Log.w(BuddyApp.TAG, "could not set the torch", it) }
        return on
    }

    fun toggle(): Boolean = set(!on)
}
