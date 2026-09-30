package ai.arthax.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import ai.arthax.app.ui.calls.CallsScreen
import ai.arthax.app.ui.common.FullScreenLoading
import ai.arthax.app.ui.leads.LeadFocus
import ai.arthax.app.ui.leads.LeadsScreen
import ai.arthax.app.ui.login.LoginScreen
import ai.arthax.app.ui.logs.LogsScreen
import ai.arthax.app.ui.meetings.MeetingsScreen
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
    navRequests: NavRequests,
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

        RootViewModel.Destination.Main -> MainScaffold(navRequests, modifier)
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

/**
 * The bottom bar, in order.
 *
 * Declaration order *is* the order on screen, so moving an entry moves the tab. [extra] is
 * the `open_tab` value a notification intent carries and is part of the contract with the
 * backend's push payloads — the existing three keep theirs exactly, so every notification
 * that already works keeps working.
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector,
    /** The `open_tab` value a notification intent carries. */
    val extra: String,
) {
    CALLS("Calls", Icons.Default.Call, "calls"),
    LEADS("Leads", Icons.Default.Person, "leads"),
    MEETINGS("Meetings", Icons.Default.DateRange, "meetings"),
    LOGS("Activity", Icons.AutoMirrored.Filled.List, "activity"),
    SETTINGS("Settings", Icons.Default.Settings, "settings"),
    ;

    companion object {
        fun fromExtra(raw: String?): MainTab? = entries.firstOrNull { it.extra == raw?.trim()?.lowercase() }
    }
}

@Composable
private fun MainScaffold(navRequests: NavRequests, modifier: Modifier = Modifier) {
    // Opens on the first tab. Change this one value to land somewhere else — Leads is the
    // obvious alternative, since dialling rather than reviewing is what a rep opens the app
    // to do.
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.CALLS) }

    // A notification tap. The tab switch happens here; the lead part rides down to the
    // Leads screen, keyed by nonce so the same request is applied exactly once.
    val navRequest by navRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(navRequest?.nonce) {
        navRequest?.let { selectedTab = it.tab }
    }
    val leadFocus = navRequest
        ?.takeIf { it.tab == MainTab.LEADS && (it.leadId != null || it.search != null) }
        ?.let { LeadFocus(leadId = it.leadId, search = it.search, nonce = it.nonce) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Column {
                // A hairline above the bar. On the near-black ground the bar's surface and
                // the content behind it are close enough in value that without this the
                // two run together and the tabs look like they are floating on the list.
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                // The selected tab is the one place other than CALL where
                                // the brand accent appears, so it is unmistakable which tab
                                // you are on without reading the labels.
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            MainTab.CALLS -> CallsScreen(modifier = Modifier.padding(padding))

            MainTab.LEADS -> LeadsScreen(
                onOpenSettings = { selectedTab = MainTab.SETTINGS },
                focus = leadFocus,
                modifier = Modifier.padding(padding),
            )

            MainTab.MEETINGS -> MeetingsScreen(modifier = Modifier.padding(padding))

            MainTab.LOGS -> LogsScreen(modifier = Modifier.padding(padding))

            MainTab.SETTINGS -> SettingsScreen(
                // Logging out flips the root destination; no navigation needed here.
                onLoggedOut = {},
                modifier = Modifier.padding(padding),
            )
        }
    }
}
