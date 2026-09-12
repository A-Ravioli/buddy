package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Compile-check stub: LocalContext is Android-only. */
val LocalContext = staticCompositionLocalOf<Context> { error("stub") }
