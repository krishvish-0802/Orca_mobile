package com.orcamobile.core.engine.di

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.engine.NativePresetCatalog
import com.orcamobile.core.engine.NativeSlicingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface EngineModule {
    @Binds
    fun slicingEngine(impl: NativeSlicingEngine): SlicingEngine

    @Binds
    fun presetCatalog(impl: NativePresetCatalog): PresetCatalog
}
