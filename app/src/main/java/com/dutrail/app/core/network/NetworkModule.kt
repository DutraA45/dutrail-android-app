package com.dutrail.app.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.dutrail.app.BuildConfig
import com.dutrail.app.activities.data.ActivitiesApi
import com.dutrail.app.auth.data.AccountApi
import com.dutrail.app.auth.data.AuthApi
import com.dutrail.app.auth.data.EncryptedSessionStore
import com.dutrail.app.auth.data.KeystoreTokenCipher
import com.dutrail.app.auth.data.SessionFile
import com.dutrail.app.auth.data.SessionFileSerializer
import com.dutrail.app.auth.data.SessionStore
import com.dutrail.app.auth.data.TokenRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.create

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Unauthenticated

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Authenticated

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun baseUrl(): HttpUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    @Provides
    @Singleton
    fun sessionDataStore(@ApplicationContext context: Context): DataStore<SessionFile> = DataStoreFactory.create(
        serializer = SessionFileSerializer(KeystoreTokenCipher()),
        corruptionHandler = ReplaceFileCorruptionHandler { SessionFile() },
        produceFile = { context.dataStoreFile(SESSION_FILE) },
    )

    @Provides
    @Singleton
    fun sessionStore(dataStore: DataStore<SessionFile>): SessionStore = EncryptedSessionStore(dataStore)

    @Provides
    @Singleton
    @Unauthenticated
    fun baseClient(baseUrl: HttpUrl): OkHttpClient = HttpClients.baseClient(baseUrl, BuildConfig.DEBUG)

    @Provides
    @Singleton
    @Unauthenticated
    fun baseRetrofit(baseUrl: HttpUrl, @Unauthenticated client: OkHttpClient): Retrofit =
        HttpClients.retrofit(baseUrl, client)

    @Provides
    @Singleton
    fun authApi(@Unauthenticated retrofit: Retrofit): AuthApi = retrofit.create()

    @Provides
    @Singleton
    fun tokenRefresher(authApi: AuthApi, sessionStore: SessionStore) = TokenRefresher(authApi, sessionStore)

    @Provides
    @Singleton
    @Authenticated
    fun authenticatedRetrofit(
        baseUrl: HttpUrl,
        @Unauthenticated base: OkHttpClient,
        sessionStore: SessionStore,
        refresher: TokenRefresher,
    ): Retrofit = HttpClients.retrofit(baseUrl, HttpClients.authenticatedClient(base, sessionStore, refresher))

    @Provides
    @Singleton
    fun accountApi(@Authenticated retrofit: Retrofit): AccountApi = retrofit.create()

    @Provides
    @Singleton
    fun activitiesApi(@Authenticated retrofit: Retrofit): ActivitiesApi = retrofit.create()

    /** Também excluído do backup (ver res/xml): a chave do Keystore não vai junto para outro aparelho. */
    const val SESSION_FILE = "session.pb"
}
