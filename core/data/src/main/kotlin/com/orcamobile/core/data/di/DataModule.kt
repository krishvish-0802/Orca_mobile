package com.orcamobile.core.data.di

import com.orcamobile.core.data.AssetSampleModelRepository
import com.orcamobile.core.data.FileWorkspaceRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds
    fun sampleModels(impl: AssetSampleModelRepository): SampleModelRepository

    @Binds
    fun workspace(impl: FileWorkspaceRepository): WorkspaceRepository
}
