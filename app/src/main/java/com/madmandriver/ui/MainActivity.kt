package com.madmandriver.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.madmandriver.ui.screens.HistoryScreen
import com.madmandriver.ui.screens.RecordScreen
import com.madmandriver.ui.screens.RideDetailScreen
import com.madmandriver.ui.screens.SettingsScreen
import com.madmandriver.ui.theme.MadmanDriverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MadmanDriverTheme {
                AppRoot()
            }
        }
    }
}

private data class TopLevelDestination(val route: String, val label: String, val icon: ImageVector)

private val topLevelDestinations = listOf(
    TopLevelDestination("record", "Record", Icons.Default.Sensors),
    TopLevelDestination("history", "History", Icons.Default.History),
    TopLevelDestination("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo("record") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "record",
            modifier = Modifier.padding(padding),
        ) {
            composable("record") { RecordScreen() }
            composable("history") { HistoryScreen(onOpenRide = { navController.navigate("ride/$it") }) }
            composable(
                "ride/{rideId}",
                arguments = listOf(navArgument("rideId") { type = NavType.LongType }),
            ) { entry ->
                RideDetailScreen(
                    rideId = entry.arguments!!.getLong("rideId"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
