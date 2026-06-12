package com.synclisten.app.data

import com.synclisten.app.util.AppLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.synclisten.app.transfer.OkHttpUploadTransport
import com.synclisten.app.transfer.UploadTransport

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    abstract fun bindSettingsStore(store: PreferenceSettingsStore): SettingsStore

    @Binds
    abstract fun bindRoomRemoteDataSource(source: RetrofitRoomRemoteDataSource): RoomRemoteDataSource

    @Binds
    abstract fun bindUploadTransport(transport: OkHttpUploadTransport): UploadTransport
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideIdentityManager(settingsStore: SettingsStore): IdentityManager =
        IdentityManager(settingsStore) { UUID.randomUUID().toString() }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor { AppLogger.debug("HTTP", it) }
            .apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .build()
    }
}
