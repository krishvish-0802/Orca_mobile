package com.orcamobile.feature.prepare

import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.FeatureFilter
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.MeasureOutcome
import com.orcamobile.core.model.MeasureRequest
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ProjectId

/** Measure what is on the plate, or assemble two parts by their features. */
enum class MeasureMode { MEASURE, ASSEMBLE_FACES, ASSEMBLE_POINTS }

/** The desktop's Measure and Assembly tools. Produced only by [MeasureViewModel]. */
data class MeasureUiState(
    val project: ProjectId? = null,
    val active: Boolean = false,
    val mode: MeasureMode = MeasureMode.MEASURE,
    /** Up to two taps on parts; a third starts over. */
    val picks: List<FeaturePick> = emptyList(),
    val outcome: MeasureOutcome? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val filter: FeatureFilter
        get() = when (mode) {
            MeasureMode.MEASURE -> FeatureFilter.ANY
            MeasureMode.ASSEMBLE_FACES -> FeatureFilter.PLANES
            MeasureMode.ASSEMBLE_POINTS -> FeatureFilter.POINTS_AND_CIRCLES
        }

    /** What the plate shows, while the tool is open. */
    val request: MeasureRequest? get() = if (active) MeasureRequest(picks, filter) else null
}

sealed interface MeasureIntent {
    data class Open(val project: ProjectId) : MeasureIntent

    data object Close : MeasureIntent

    data class SetMode(val mode: MeasureMode) : MeasureIntent

    /** A tap on a part. */
    data class Pick(val pick: FeaturePick) : MeasureIntent

    data object Clear : MeasureIntent

    /** The plate found these features and this measurement. */
    data class Measured(val outcome: MeasureOutcome) : MeasureIntent

    /** Moves the second part (or turns the first, for its flip); [value] is degrees or mm, [xyz] mm. */
    data class Assemble(val step: AssemblyStep, val value: Double = 0.0, val xyz: List<Double> = listOf(0.0, 0.0, 0.0)) : MeasureIntent
}

sealed interface MeasureEvent {
    /** An assembly step moved a part; the plate holds [objects] now. */
    data class PlateChanged(val objects: List<ModelObjectInfo>) : MeasureEvent
}
