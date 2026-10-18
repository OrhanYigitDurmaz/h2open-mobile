package tr.h2open.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tr.h2open.driver.ui.login.LoginScreen
import tr.h2open.driver.ui.stop.StopScreen
import tr.h2open.driver.ui.theme.H2OpenTheme
import tr.h2open.driver.ui.trip.TripScreen
import tr.h2open.driver.ui.trips.TripsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as H2oApplication
        setContent {
            H2OpenTheme {
                val session by app.sessions.session.collectAsStateWithLifecycle()
                // Signing out (or a rejected token) drops the whole back stack.
                if (session == null) LoginScreen() else DriverNavHost()
            }
        }
    }
}

@Composable
private fun DriverNavHost() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "trips") {
        composable("trips") {
            TripsScreen(onOpenTrip = { nav.navigate("trips/$it") })
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
}
