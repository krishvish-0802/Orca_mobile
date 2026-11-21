package com.orcamobile.app.di

import com.orcamobile.core.common.CoroutineDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @Singleton
    fun dispatchers(): CoroutineDispatchers =
        CoroutineDispatchers(default = Dispatchers.Default, io = Dispatchers.IO, main = Dispatchers.Main)
}
