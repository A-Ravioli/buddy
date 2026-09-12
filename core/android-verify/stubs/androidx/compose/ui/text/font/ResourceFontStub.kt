package androidx.compose.ui.text.font

import java.io.File

/**
 * Compile-check stub: resource fonts are Android-only. Same signature as the real one.
 * When `buddy.fontDir` points at the app's res/font, the bundled TTF is loaded, so the
 * composables can also be rendered on the JVM.
 */
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font {
    val dir = System.getProperty("buddy.fontDir")
    if (dir != null) {
        val name = runCatching { Class.forName("app.buddy.R\$font").fields.firstOrNull { it.getInt(null) == resId }?.name }.getOrNull()
        val file = name?.let { File(dir, "$it.ttf") }
        if (file != null && file.exists()) return androidx.compose.ui.text.platform.Font(file, weight, style)
    }
    return androidx.compose.ui.text.platform.Font("stub-$resId", weight, style)
}
