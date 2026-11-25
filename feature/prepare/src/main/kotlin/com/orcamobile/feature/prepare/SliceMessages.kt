package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.SliceMessage

/**
 * What the slicer said about the print, each with what can be done about it:
 * supports for an object that needs them, laying it flat, showing it, or the
 * setting the message is about.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SliceMessagesView(
    messages: List<SliceMessage>,
    objects: List<ModelObjectInfo>,
    onFix: (SliceFix) -> Unit,
) {
    if (messages.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (message in messages) {
            val error = message.level == SliceMessage.Level.ERROR
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            if (error) R.string.prepare_slice_message_error else R.string.prepare_slice_message_warning,
                        ) + (message.objectName?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    val fixes = fixesFor(message, objects)
                    if (fixes.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (fix in fixes) {
                                AssistChip(onClick = { onFix(fix) }, label = { Text(fixLabel(fix)) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What can be done about [message], most useful first. */
internal fun fixesFor(message: SliceMessage, objects: List<ModelObjectInfo>): List<SliceFix> {
    val objectIndex = message.objectName?.let { name -> objects.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
    val fixes = mutableListOf<SliceFix>()
    when (message.code) {
        // Nothing holds it up: supports under it, or another side down.
        "need_support", "floating", "empty_first_layer" -> {
            fixes += SliceFix.TurnOnSupports
            objectIndex?.let { fixes += SliceFix.LayFlat(it) }
        }
        "support_enforcers" -> fixes += SliceFix.TurnOnSupports
    }
    message.setting?.let { fixes += SliceFix.OpenSetting(it) }
    if (objectIndex != null && fixes.none { it is SliceFix.LayFlat }) fixes += SliceFix.Show(objectIndex)
    return fixes
}

@Composable
private fun fixLabel(fix: SliceFix): String = when (fix) {
    SliceFix.TurnOnSupports -> stringResource(R.string.prepare_slice_fix_supports)
    is SliceFix.LayFlat -> stringResource(R.string.prepare_slice_fix_lay_flat)
    is SliceFix.Show -> stringResource(R.string.prepare_slice_fix_show)
    is SliceFix.OpenSetting -> stringResource(R.string.prepare_slice_fix_setting)
}
