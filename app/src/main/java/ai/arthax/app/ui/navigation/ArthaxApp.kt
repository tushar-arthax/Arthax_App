package ai.arthax.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.leads.LeadsScreen
import ai.arthax.app.ui.login.LoginScreen
import ai.arthax.app.ui.logs.LogsScreen
import ai.arthax.app.ui.onboarding.OnboardingScreen
import ai.arthax.app.ui.otp.OtpScreen
import ai.arthax.app.ui.otp.OtpViewModel
import ai.arthax.app.ui.settings.SettingsScreen

object Routes {
    const val LOGIN = "login"
    const val OTP = "otp/{${OtpViewModel.ARG_PHONE}}"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"

    fun otp(phone: String) = "otp/$phone"
}

@Composable
fun ArthaxApp(
    modifier: Modifier = Modifier,
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val destination by rootViewModel.destination.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    when (destination) {
        RootViewModel.Destination.Loading ->
            FullScreenLoading(label = "Starting Arthax", modifier = modifier)

        // Each top-level destination gets its own NavHost rather than one graph with
        // popUpTo juggling. The transitions between them are driven by observable auth and
        // setup state, so there is no back stack worth preserving across them — and this
        // removes any chance of the rep backing into a screen they are no longer entitled to.
        RootViewModel.Destination.Login -> AuthNavHost(navController, modifier)

        RootViewModel.Destination.Onboarding -> OnboardingScreen(
            // RootViewModel is already observing the same setting, so it flips this
            // destination on its own; nothing to navigate to here.
            onFinished = {},
            modifier = modifier,
        )

        RootViewModel.Destination.Main -> MainScaffold(modifier)
    }
}

@Composable
private fun AuthNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.LOGIN,
        modifier = modifier,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onOtpSent = { phone -> navController.navigate(Routes.otp(phone)) },
            )
        }

        composable(Routes.OTP) {
            OtpScreen(
                // On success the auth state changes and RootViewModel swaps the whole
                // destination, so this screen has nothing left to do.
                onVerified = {},
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    LEADS("Leads", Icons.Default.Person),
    LOGS("Activity", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
private fun MainScaffold(modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.LEADS) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            MainTab.LEADS -> LeadsScreen(
                onOpenSettings = { selectedTab = MainTab.SETTINGS },
                modifier = Modifier.padding(padding),
            )

            MainTab.LOGS -> LogsScreen(modifier = Modifier.padding(padding))

            MainTab.SETTINGS -> SettingsScreen(
                // Logging out flips the root destination; no navigation needed here.
                onLoggedOut = {},
                modifier = Modifier.padding(padding),
            )
        }
    }
}
