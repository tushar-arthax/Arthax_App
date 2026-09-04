package com.example.arthax.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Decides which of the three top-level destinations the app should be showing.
 *
 * Driven by observable state rather than a one-time check at startup, so a 401 from any
 * background call — including one inside an upload worker — pulls the rep back to Login
 * without anything having to navigate explicitly.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
    settings: AppSettings,
) : ViewModel() {

    sealed interface Destination {
        data object Loading : Destination
        data object Login : Destination
        data object Onboarding : Destination
        data object Main : Destination
    }

    val destination: StateFlow<Destination> = combine(
        authRepository.authState,
        settings.snapshot,
    ) { authState, snapshot ->
        when {
            authState == SecureTokenStore.AuthState.UNKNOWN -> Destination.Loading
            authState != SecureTokenStore.AuthState.AUTHENTICATED -> Destination.Login
            // Onboarding is not just a first-run tour: without the folder grant the app
            // cannot capture anything, so a rep who cleared app data is sent back through it.
            !snapshot.onboardingComplete || !snapshot.hasRecordingsFolder -> Destination.Onboarding
            else -> Destination.Main
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Destination.Loading,
    )
}
