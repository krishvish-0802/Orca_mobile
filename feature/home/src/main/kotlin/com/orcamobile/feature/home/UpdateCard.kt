package com.orcamobile.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton

/** A newer build on GitHub: what changed, then download and install in place. */
@Composable
internal fun UpdateCard(update: UpdateUiState, onIntent: (HomeIntent) -> Unit) {
    if (update is UpdateUiState.None) return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val build = when (update) {
                is UpdateUiState.Available -> update.update
                is UpdateUiState.Downloading -> update.update
                is UpdateUiState.Ready -> update.update
                is UpdateUiState.Failed -> update.update
                UpdateUiState.None -> return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_update_title, build.versionCode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (update !is UpdateUiState.Downloading) {
                    TextButton(onClick = { onIntent(HomeIntent.DismissUpdate) }) { Text(stringResource(R.string.home_update_later)) }
                }
            }
            for (change in build.changes.take(MAX_CHANGES)) {
                Text(
                    "• $change",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (update) {
                is UpdateUiState.Available -> PrimaryPillButton(
                    stringResource(R.string.home_update_download),
                    onClick = { onIntent(HomeIntent.DownloadUpdate) },
                    modifier = Modifier.fillMaxWidth(),
                )
                is UpdateUiState.Downloading -> {
                    val percent = update.percent
                    if (percent == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        if (percent == null) stringResource(R.string.home_update_downloading) else stringResource(R.string.home_update_downloading_percent, percent),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is UpdateUiState.Ready -> {
                    if (update.needsPermission) {
                        Text(stringResource(R.string.home_update_permission), style = MaterialTheme.typography.bodySmall)
                    }
                    PrimaryPillButton(
                        stringResource(R.string.home_update_install),
                        onClick = { onIntent(HomeIntent.InstallUpdate) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UpdateUiState.Failed -> {
                    Text(update.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    PrimaryPillButton(
                        stringResource(R.string.home_update_retry),
                        onClick = { onIntent(HomeIntent.DownloadUpdate) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                UpdateUiState.None -> Unit
            }
        }
    }
}

private const val MAX_CHANGES = 5
