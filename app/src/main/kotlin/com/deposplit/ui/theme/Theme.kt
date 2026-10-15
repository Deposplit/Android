package com.deposplit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

// No dynamic (wallpaper) colour: primary carries meaning here — the High verification badge and
// retrieval requests are drawn in it — so it must not turn red or green with somebody's
// wallpaper and be mistaken for an error or for Very High.
@Composable
fun DeposplitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
