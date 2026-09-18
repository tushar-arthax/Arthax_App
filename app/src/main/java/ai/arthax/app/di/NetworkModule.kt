package ai.arthax.app.di

import ai.arthax.app.BuildConfig
import ai.arthax.app.core.ApiConfig
import ai.arthax.app.data.local.prefs.SecureTokenStore
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.AuthInterceptor
import ai.arthax.app.data.repository.EventLogger
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenStore: SecureTokenStore,
        logger: EventLogger,
    ): OkHttpClient {
        val httpLogging = HttpLoggingInterceptor().apply {
            // BODY in debug so request/response JSON is visible in logcat while wiring the
            // API up. Never in release: the bodies carry the bearer token and the customer's
            // entire lead list.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

        return OkHttpClient.Builder()
            // Order matters: AuthInterceptor first so the logger below sees the final
            // request, including the Authorization header it added.
            .addInterceptor(AuthInterceptor(tokenStore, logger))
            .addInterceptor(httpLogging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Generous: a long recording over weak rural signal is the normal case here, and
            // a premature timeout turns into a pointless retry cycle.
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            // The single definition of where the backend lives.
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideArthaxApi(retrofit: Retrofit): ArthaxApi = retrofit.create(ArthaxApi::class.java)
}
