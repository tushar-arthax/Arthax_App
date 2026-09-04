package com.example.arthax.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arthax.data.remote.api.ApiResult
import com.example.arthax.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val phone: String = "",
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val errorDetail: String? = null,
        /** Set once the OTP is on its way; the screen navigates on this. */
        val otpSentTo: String? = null,
        /** The server's own wording — it tells the rep where the code was actually sent. */
        val deliveryMessage: String? = null,
    ) {
        val canSubmit: Boolean
            get() = !isSubmitting && phone.filter(Char::isDigit).length >= MIN_DIGITS
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onPhoneChanged(value: String) {
        // Accept only digits and a leading +, and cap the length — reps type fast on a
        // numeric keypad and a stray character should not be able to fail the request.
        val cleaned = buildString {
            value.forEachIndexed { index, c ->
                if (c.isDigit()) append(c)
                if (c == '+' && index == 0) append(c)
            }
        }.take(MAX_LENGTH)

        _state.update { it.copy(phone = cleaned, error = null, errorDetail = null) }
    }

    fun requestOtp() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(isSubmitting = true, error = null, errorDetail = null) }

        viewModelScope.launch {
            when (val result = authRepository.sendOtp(current.phone)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isSubmitting = false,
                        otpSentTo = current.phone,
                        deliveryMessage = result.data,
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    /** Called after the screen has acted on [UiState.otpSentTo] so it does not re-navigate. */
    fun onNavigated() {
        _state.update { it.copy(otpSentTo = null) }
    }

    private companion object {
        const val MIN_DIGITS = 10
        const val MAX_LENGTH = 15
    }
}
