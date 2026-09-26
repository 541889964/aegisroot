package com.example.kernelsustyleuikit.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.example.kernelsustyleuikit.flasher.FlashScreen
import com.example.kernelsustyleuikit.ui.navigation3.Navigator

@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    FlashScreen(bottomInnerPadding)
}
