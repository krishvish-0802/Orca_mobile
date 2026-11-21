package com.orcamobile.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.orcamobile.core.data.AssetSampleModelRepository
import com.orcamobile.core.data.ContentModelImportRepository
import com.orcamobile.core.data.DataStorePrintSettingsRepository
import com.orcamobile.core.data.DataStoreUserSettingsRepository
import com.orcamobile.core.data.FileWorkspaceRepository
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds
    fun sampleModels(impl: AssetSampleModelRepository): SampleModelRepository

    @Binds
    fun workspace(impl: FileWorkspaceRepository): WorkspaceRepository

    @Binds
    fun modelImport(impl: ContentModelImportRepository): ModelImportRepository

    @Binds
    fun userSettings(impl: DataStoreUserSettingsRepository): UserSettingsRepository

    @Binds
    fun printSettings(impl: DataStorePrintSettingsRepository): PrintSettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal object DataStoreModule {
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("user_settings") }
}
