package com.orcamobile.core.update.di

import android.content.Context
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.update.AppUpdates
import com.orcamobile.core.update.GitHubAppUpdates
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object UpdateModule {
    @Provides
    @Singleton
    fun appUpdates(@ApplicationContext context: Context, dispatchers: CoroutineDispatchers): AppUpdates {
        // Its own client: the printers' one is tuned for the local network.
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return GitHubAppUpdates(context, http, dispatchers)
    }
}
