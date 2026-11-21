package com.orcamobile.feature.settings.di

import com.orcamobile.feature.settings.AssetSettingsLayoutSource
import com.orcamobile.feature.settings.SettingsLayoutSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsModule {
    @Binds
    fun layoutSource(impl: AssetSettingsLayoutSource): SettingsLayoutSource
}
