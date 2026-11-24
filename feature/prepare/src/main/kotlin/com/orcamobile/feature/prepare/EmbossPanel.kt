package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.ChoiceSheet
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.VolumeKind
import java.util.Locale

/**
 * The desktop's Emboss and SVG tools: text in one of the phone's fonts, or an
 * SVG file, tapped onto a part of the selected object (raised, engraved or as
 * a modifier), or added as an object of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmbossPanel(
    state: EmbossUiState,
    canPlaceOnObject: Boolean,
    onIntent: (EmbossIntent) -> Unit,
    onPickSvg: () -> Unit,
    onAddAsObject: () -> Unit,
    onDone: () -> Unit,
) {
    var choosingFont by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prepare_emboss_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.editing != null) {
                    TextButton(onClick = { onIntent(EmbossIntent.NewText) }, enabled = !state.busy) {
                        Text(stringResource(R.string.prepare_emboss_new))
                    }
                    TextButton(onClick = { onIntent(EmbossIntent.Delete) }, enabled = !state.busy) {
                        Text(stringResource(R.string.prepare_emboss_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (state.editing != null) {
                Text(
                    stringResource(R.string.prepare_emboss_editing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.missingFont?.let { (missing, used) ->
                Text(
                    stringResource(R.string.prepare_emboss_font_missing, missing, used),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val modes = EmbossMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { onIntent(EmbossIntent.SetMode(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                    ) { Text(stringResource(if (mode == EmbossMode.TEXT) R.string.prepare_emboss_text else R.string.prepare_emboss_svg)) }
                }
            }
            when (state.mode) {
                EmbossMode.TEXT -> {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = { onIntent(EmbossIntent.SetText(it)) },
                        label = { Text(stringResource(R.string.prepare_emboss_text_field)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                    TonalPillButton(
                        state.font?.name ?: stringResource(R.string.prepare_emboss_no_font),
                        onClick = { choosingFont = true },
                        enabled = state.fonts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(state.letterHeightMm, { onIntent(EmbossIntent.SetLetterHeight(it)) }, stringResource(R.string.prepare_emboss_height), Modifier.weight(1f), "mm")
                        NumberField(state.depthMm, { onIntent(EmbossIntent.SetDepth(it)) }, stringResource(R.string.prepare_cut_depth), Modifier.weight(1f), "mm")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumberField(state.boldnessMm, { onIntent(EmbossIntent.SetBoldness(it)) }, stringResource(R.string.prepare_emboss_boldness), Modifier.weight(1f), "mm")
                        Text(stringResource(R.string.prepare_emboss_italic), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = state.italic, onCheckedChange = { onIntent(EmbossIntent.SetItalic(it)) })
                    }
                }
                EmbossMode.SVG -> {
                    TonalPillButton(
                        state.svgName ?: stringResource(R.string.prepare_emboss_pick_svg),
                        onClick = onPickSvg,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(state.svgWidthMm, { onIntent(EmbossIntent.SetSvgWidth(it)) }, stringResource(R.string.prepare_emboss_svg_width), Modifier.weight(1f), "mm")
                        NumberField(state.depthMm, { onIntent(EmbossIntent.SetDepth(it)) }, stringResource(R.string.prepare_cut_depth), Modifier.weight(1f), "mm")
                    }
                    Text(stringResource(R.string.prepare_emboss_svg_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.size?.let { (w, h) ->
                Text(
                    stringResource(R.string.prepare_emboss_size, String.format(Locale.ROOT, "%.1f", w), String.format(Locale.ROOT, "%.1f", h)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.prepare_emboss_as), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in EmbossUiState.KINDS) {
                    FilterChip(selected = state.kind == kind, onClick = { onIntent(EmbossIntent.SetKind(kind)) }, label = { Text(stringResource(embossKindLabel(kind))) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.prepare_emboss_surface), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.prepare_emboss_surface_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.useSurface, onCheckedChange = { onIntent(EmbossIntent.SetUseSurface(it)) })
            }
            if (state.editing == null) Text(
                stringResource(if (canPlaceOnObject) R.string.prepare_emboss_place_hint else R.string.prepare_emboss_select_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_emboss_as_object),
                    onClick = onAddAsObject,
                    enabled = !state.busy && state.source != null,
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(stringResource(R.string.prepare_measure_done), onClick = onDone, modifier = Modifier.weight(1f))
            }
        }
    }
    if (choosingFont) {
        val names = state.fonts.map { it.name }
        ChoiceSheet(
            title = stringResource(R.string.prepare_emboss_font),
            options = names,
            selected = state.font?.name,
            onSelect = { name ->
                state.fonts.firstOrNull { it.name == name }?.let { onIntent(EmbossIntent.SetFont(it)) }
                choosingFont = false
            },
            onDismiss = { choosingFont = false },
            searchHint = stringResource(R.string.prepare_search),
        )
    }
}

/** Kinds offered for embossing, with the object list's labels. */
private fun embossKindLabel(kind: VolumeKind): Int = when (kind) {
    VolumeKind.PART -> R.string.prepare_emboss_raised
    VolumeKind.NEGATIVE_VOLUME -> R.string.prepare_emboss_engraved
    else -> R.string.prepare_kind_modifier
}
