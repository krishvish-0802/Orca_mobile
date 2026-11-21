package com.orcamobile.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val PillPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/** The screen's main action: a filled pill. */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), enabled = enabled, contentPadding = PillPadding) {
        PillContent(text, icon)
    }
}

/** A secondary action next to the main one: a tinted pill. */
@Composable
fun TonalPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), enabled = enabled, contentPadding = PillPadding) {
        PillContent(text, icon)
    }
}

@Composable
private fun PillContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, maxLines = 1)
}
