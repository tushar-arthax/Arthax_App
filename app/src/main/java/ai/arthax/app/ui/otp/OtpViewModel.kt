package ai.arthax.app.ui.otp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OtpViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val phone: String = savedStateHandle.get<String>(ARG_PHONE).orEmpty()

    data class UiState(
        val code: String = "",
        val isSubmitting: Boolean = false,
        val isResending: Boolean = false,
        val error: String? = null,
        val errorDetail: String? = null,
        val resendCountdown: Int = 0,
        val verified: Boolean = false,
        val info: String? = null,
    ) {
        val canSubmit: Boolean get() = !isSubmitting && code.length == OTP_LENGTH
        val canResend: Boolean get() = resendCountdown <= 0 && !isResending && !isSubmitting
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        startResendCountdown(INITIAL_RESEND_SECONDS)
    }

    fun onCodeChanged(value: String) {
        val digits = value.filter(Char::isDigit).take(OTP_LENGTH)
        _state.update { it.copy(code = digits, error = null, errorDetail = null) }

        // Submit as soon as the last digit lands. Saves a tap on every single login,
        // and the button stays for anyone who prefers it.
        if (digits.length == OTP_LENGTH) verify()
    }

    fun verify() {
        val current = _state.value
        if (current.isSubmitting || current.code.length != OTP_LENGTH) return

        _state.update { it.copy(isSubmitting = true, error = null, errorDetail = null, info = null) }

        viewModelScope.launch {
            when (val result = authRepository.verifyOtp(phone, current.code)) {
                is ApiResult.Success -> _state.update { it.copy(isSubmitting = false, verified = true) }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isSubmitting = false,
                        // Clear the field so the rep can retype without deleting six digits.
                        code = "",
                        error = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    fun resend() {
        if (!_state.value.canResend) return

        _state.update { it.copy(isResending = true, error = null, errorDetail = null, info = null) }

        viewModelScope.launch {
            when (val result = authRepository.sendOtp(phone)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isResending = false, info = result.data) }
                    startResendCountdown(INITIAL_RESEND_SECONDS)
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isResending = false,
                        error = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    private fun startResendCountdown(seconds: Int) {
        viewModelScope.launch {
            var remaining = seconds.coerceAtLeast(0)
            _state.update { it.copy(resendCountdown = remaining) }
            while (remaining > 0) {
                delay(1_000)
                remaining--
                _state.update { it.copy(resendCountdown = remaining) }
            }
        }
    }

    companion object {
        const val ARG_PHONE = "phone"
        const val OTP_LENGTH = 6
        const val INITIAL_RESEND_SECONDS = 30
    }
}
