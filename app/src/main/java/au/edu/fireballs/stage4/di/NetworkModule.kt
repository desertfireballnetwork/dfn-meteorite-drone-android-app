package au.edu.fireballs.stage4.di

import android.content.Context
import au.edu.fireballs.stage4.BuildConfig
import au.edu.fireballs.stage4.data.remote.AuthInterceptor
import au.edu.fireballs.stage4.data.remote.AuthService
import au.edu.fireballs.stage4.data.remote.EvidenceService
import au.edu.fireballs.stage4.data.remote.PersistentCookieJar
import au.edu.fireballs.stage4.data.remote.Stage4Service
import au.edu.fireballs.stage4.data.remote.TileService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val DEFAULT_SERVER_URL = "https://find.gfo.rocks/"
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 60L

    @Provides
    @Singleton
    fun provideBaseUrl(): HttpUrl {
        val rawUrl = BuildConfig.PRODUCTION_SERVER_URL.ifEmpty { DEFAULT_SERVER_URL }
        val baseUrl = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
        return baseUrl.toHttpUrl()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi =
        Moshi
            .Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    @Provides
    @Singleton
    fun providePersistentCookieJar(
        @ApplicationContext context: Context,
    ): PersistentCookieJar = PersistentCookieJar(context)

    @Provides
    @Singleton
    fun provideCookieJar(persistentCookieJar: PersistentCookieJar): CookieJar = persistentCookieJar

    @Provides
    @Singleton
    fun provideAuthInterceptor(cookieJar: CookieJar): AuthInterceptor {
        val serverUrl = BuildConfig.PRODUCTION_SERVER_URL.ifEmpty { DEFAULT_SERVER_URL }
        return AuthInterceptor(cookieJar, serverUrl)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cookieJar: CookieJar,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .cookieJar(cookieJar)
                .addInterceptor(authInterceptor)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor =
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }

            // Interceptor to downgrade logging for auth/login endpoints
            val selectiveLoggingInterceptor =
                Interceptor { chain ->
                    val request = chain.request()
                    val isAuthEndpoint =
                        request.url.encodedPath.contains(
                            "login",
                            ignoreCase = true,
                        )

                    if (isAuthEndpoint) {
                        val originalLevel = loggingInterceptor.level
                        loggingInterceptor.level = HttpLoggingInterceptor.Level.HEADERS
                        try {
                            chain.proceed(request)
                        } finally {
                            loggingInterceptor.level = originalLevel
                        }
                    } else {
                        chain.proceed(request)
                    }
                }

            builder.addInterceptor(selectiveLoggingInterceptor)
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
        baseUrl: HttpUrl,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAuthService(retrofit: Retrofit): AuthService =
        retrofit.create(AuthService::class.java)

    @Provides
    @Singleton
    fun provideStage4Service(retrofit: Retrofit): Stage4Service =
        retrofit.create(Stage4Service::class.java)

    @Provides
    @Singleton
    fun provideEvidenceService(retrofit: Retrofit): EvidenceService =
        retrofit.create(EvidenceService::class.java)

    @Provides
    @Singleton
    fun provideTileService(retrofit: Retrofit): TileService =
        retrofit.create(TileService::class.java)
}
