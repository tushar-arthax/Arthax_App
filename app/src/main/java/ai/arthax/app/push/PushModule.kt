package ai.arthax.app.push

import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.FcmTokenRequest
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.LogStage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Wires the pure push classes to the app: the registrar's store is the settings DataStore
 * and its upload is the Retrofit call; the router's handler is the Android one.
 */
@Module
@InstallIn(SingletonComponent::class)
object PushModule {

    @Provides
    @Singleton
    fun provideFcmTokenRegistrar(
        settings: AppSettings,
        api: ArthaxApi,
        tokenStore: SecureTokenStore,
        logger: EventLogger,
    ): FcmTokenRegistrar = FcmTokenRegistrar(
        store = object : FcmTokenRegistrar.Store {
            override suspend fun read(): FcmTokenRegistrar.State {
                val saved = settings.fcmTokens.first()
                return FcmTokenRegistrar.State(saved.token, saved.registeredToken)
            }

            override suspend fun write(state: FcmTokenRegistrar.State) {
                settings.setFcmTokens(AppSettings.FcmTokens(state.token, state.registeredToken))
            }
        },
        upload = { token ->
            when (val result = safeApiCall { api.updateFcmToken(FcmTokenRequest(token)) }) {
                is ApiResult.Success -> true
                is ApiResult.Failure -> {
                    logger.warn(
                        LogStage.NETWORK,
                        "Push token not accepted by the server: ${result.message}",
                        detail = "It will be retried at the next start or sign-in. ${result.detail.orEmpty()}",
                    )
                    false
                }
            }
        },
        isSignedIn = { tokenStore.isLoggedIn },
    )

    @Provides
    @Singleton
    fun providePushRouter(handler: AndroidPushHandler, settings: AppSettings): PushRouter =
        PushRouter(
            handler = handler,
            preferences = { settings.notificationPreferences.first() },
        )
}
