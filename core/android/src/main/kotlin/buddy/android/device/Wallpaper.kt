package buddy.android.device

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import buddy.android.BuddyApp

/**
 * The ground behind everything, set once when buddy takes the phone over.
 *
 * There is almost nowhere left for a wallpaper to show: no launcher, no recents, and a
 * lock screen that is buddy's face on black. What it must not be is the stock photograph
 * showing through at the edges of a transition, which is the old phone leaking through at
 * exactly the moment the new one is introducing itself. So it is black, which is what the
 * lock screen already is, and the seam disappears.
 */
object Wallpaper {
    /** Small and flat: the framework scales it, and every pixel is the same colour. */
    private const val SIZE = 64

    fun apply(context: Context, color: Int = Color.BLACK) {
        val manager = WallpaperManager.getInstance(context) ?: return
        runCatching {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            bitmap.recycle()
        }.onFailure { Log.w(BuddyApp.TAG, "could not set the wallpaper", it) }
    }
}
