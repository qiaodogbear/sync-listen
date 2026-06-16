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
import android.content.Context
import androidx.room.Room
import com.synclisten.app.cache.CacheDao
import com.synclisten.app.cache.SyncListenDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import com.synclisten.app.playback.Media3PlayerEngine
import com.synclisten.app.playback.PlayerEngine
import com.synclisten.app.playback.LocalClock
import com.synclisten.app.playback.PlaybackSyncManager
import com.synclisten.app.playback.PlayerController
import com.synclisten.app.playback.ServerClock
import com.synclisten.app.nearby.AndroidBleRoomDiscovery
import com.synclisten.app.nearby.BleRoomDiscovery
import com.synclisten.app.nearby.AndroidNfcJoinManager
import com.synclisten.app.nearby.NfcJoinManager
import com.synclisten.app.host.HostServerController
import com.synclisten.app.host.HostRuntimeLauncher
import com.synclisten.app.host.AndroidHostRuntimeLauncher
import com.synclisten.app.host.DefaultHostServerController
import com.synclisten.app.host.HostAddressResolver
import com.synclisten.app.host.persistence.HostDao
import com.synclisten.app.host.persistence.HostPersistenceDatabase

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    abstract fun bindSettingsStore(store: PreferenceSettingsStore): SettingsStore

    @Binds
    abstract fun bindRoomRemoteDataSource(source: RetrofitRoomRemoteDataSource): RoomRemoteDataSource

    @Binds
    abstract fun bindUploadTransport(transport: OkHttpUploadTransport): UploadTransport

    @Binds
    abstract fun bindPlayerEngine(engine: Media3PlayerEngine): PlayerEngine

    @Binds
    abstract fun bindBleRoomDiscovery(discovery: AndroidBleRoomDiscovery): BleRoomDiscovery

    @Binds
    abstract fun bindNfcJoinManager(manager: AndroidNfcJoinManager): NfcJoinManager

    @Binds
    abstract fun bindHostRuntimeLauncher(launcher: AndroidHostRuntimeLauncher): HostRuntimeLauncher
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideHostServerController(
        resolver: HostAddressResolver,
        launcher: HostRuntimeLauncher,
    ): HostServerController = DefaultHostServerController(resolver::resolve, launcher)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SyncListenDatabase =
        Room.databaseBuilder(context, SyncListenDatabase::class.java, "sync-listen.db").build()

    @Provides
    fun provideCacheDao(database: SyncListenDatabase): CacheDao = database.cacheDao()

    @Provides
    @Singleton
    fun provideHostPersistenceDatabase(@ApplicationContext context: Context): HostPersistenceDatabase =
        Room.databaseBuilder(context, HostPersistenceDatabase::class.java, "host-persistence.db").build()

    @Provides
    fun provideHostDao(database: HostPersistenceDatabase): HostDao = database.hostDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideLocalClock(): LocalClock = LocalClock(System::currentTimeMillis)

    @Provides
    @Singleton
    fun provideNetworkAvailable(networkMonitor: com.synclisten.app.util.NetworkMonitor): kotlinx.coroutines.flow.StateFlow<Boolean> =
        networkMonitor.isAvailable

    @Provides
    @Singleton
    fun providePlaybackSyncManager(
        playerController: PlayerController,
        serverClock: ServerClock,
    ): PlaybackSyncManager = PlaybackSyncManager(playerController, serverClock)

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
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .build()
    }

    @Provides
    @Singleton
    @javax.inject.Named("download")
    fun provideDownloadOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()
    }
}
