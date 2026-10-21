package com.filmyab.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filmyab.app.MainViewModel
import com.filmyab.app.ui.theme.Accent
import com.filmyab.app.ui.theme.BgDark
import com.filmyab.app.ui.theme.DimText
import com.filmyab.app.ui.theme.NavIndicator
import com.filmyab.app.ui.theme.SurfaceDark

private data class TabItem(val icon: ImageVector, val label: String)

/** v2: only two tabs — no settings, no server, nothing to configure. */
@Composable
fun FilmYabApp() {
    val viewModel: MainViewModel = viewModel()
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        TabItem(Icons.Filled.Movie, "شناسایی"),
        TabItem(Icons.Filled.History, "تاریخچه")
    )

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            unselectedIconColor = DimText,
                            unselectedTextColor = DimText,
                            indicatorColor = NavIndicator
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> IdentifyScreen(viewModel)
                else -> HistoryScreen(viewModel)
            }
        }
    }
}
