package tr.h2open.driver

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tr.h2open.driver.ui.call.callPermissions
import tr.h2open.driver.ui.login.LoginScreen
import tr.h2open.driver.ui.orders.OrdersScreen
import tr.h2open.driver.ui.stop.StopScreen
import tr.h2open.driver.ui.theme.H2OpenTheme
import tr.h2open.driver.ui.trip.TripScreen
import tr.h2open.driver.ui.trips.TripsScreen

class MainActivity : ComponentActivity() {
    /** Tab a notification asked for, until the navigation has shown it. */
    private val requestedTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedTab.value = intent.getStringExtra(EXTRA_TAB)
        val app = application as H2oApplication
        setContent {
            H2OpenTheme {
                val session by app.sessions.session.collectAsStateWithLifecycle()
                // Signing out (or a rejected token) drops the whole back stack.
                if (session == null) {
                    LoginScreen()
                } else {
                    DriverNavHost(requestedTab.value, onTabShown = { requestedTab.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { requestedTab.value = it }
    }

    companion object {
        const val EXTRA_TAB = "tr.h2open.driver.TAB"
        const val TAB_TRIPS = "trips"
        const val TAB_ORDERS = "orders"
    }
}

private data class Tab(val route: String, val label: Int, val icon: @Composable () -> Unit)

private val TABS = listOf(
    Tab(MainActivity.TAB_TRIPS, R.string.tab_trips) { Icon(painterResource(R.drawable.ic_stat_order), contentDescription = null) },
    Tab(MainActivity.TAB_ORDERS, R.string.tab_orders) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
)

private fun NavHostController.openTab(route: String) = navigate(route) {
    // One copy of each tab; switching keeps the state of the other one.
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun DriverNavHost(requestedTab: String?, onTabShown: () -> Unit) {
    val nav = rememberNavController()
    // Ask once up front: without the notification permission an incoming call can't ring.
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val missing = callPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route

    Scaffold(
        // The screens draw their own top bars and insets; this one only adds the tabs.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.openTab(tab.route) },
                            icon = tab.icon,
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = MainActivity.TAB_TRIPS,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(MainActivity.TAB_TRIPS) {
                TripsScreen(onOpenTrip = { nav.navigate("trips/$it") })
            }
            composable(MainActivity.TAB_ORDERS) {
                OrdersScreen(onOpenTrip = { nav.navigate("trips/$it") })
            }
            composable("trips/{tripId}", arguments = listOf(navArgument("tripId") { type = NavType.LongType })) {
                TripScreen(
                    onBack = { nav.popBackStack() },
                    onOpenStop = { tripId, stopId -> nav.navigate("trips/$tripId/stops/$stopId") },
                )
            }
            composable(
                "trips/{tripId}/stops/{stopId}",
                arguments = listOf(
                    navArgument("tripId") { type = NavType.LongType },
                    navArgument("stopId") { type = NavType.LongType },
                ),
            ) {
                StopScreen(onBack = { nav.popBackStack() })
            }
        }
        // After the NavHost, so its graph is set when a notification asks for a tab.
        LaunchedEffect(requestedTab) {
            if (requestedTab != null && TABS.any { it.route == requestedTab }) nav.openTab(requestedTab)
            if (requestedTab != null) onTabShown()
        }
    }
}
