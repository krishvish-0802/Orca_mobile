package com.orcamobile.core.designsystem.component

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A number typed in place. The value is committed with the keyboard's Done or
 * when the field loses focus, and only if it parses and differs; otherwise the
 * field shows [value] again. Shown with at most [decimals] decimals.
 */
@Composable
fun NumberField(
    value: Double,
    onCommit: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimals: Int = 2,
    enabled: Boolean = true,
) {
    val shown = remember(value, decimals) { format(value, decimals) }
    var text by remember(shown) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val commit = {
        val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        if (parsed != null && parsed.isFinite() && format(parsed, decimals) != shown) onCommit(parsed) else text = shown
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, maxLines = 1) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.onFocusChanged { state ->
            if (focused && !state.isFocused) commit()
            focused = state.isFocused
        },
    )
}

private fun format(value: Double, decimals: Int): String =
    BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
