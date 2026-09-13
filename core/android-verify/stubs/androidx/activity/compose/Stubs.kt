package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/** Compile-check stubs for androidx.activity.compose (Google's Maven only). */
fun ComponentActivity.setContent(content: @Composable () -> Unit) = Unit

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = Unit
