package dev.cxclear.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion
import dev.cxclear.ui.theme.appOutlinedTextFieldColors

/**
 * 向导的通用分段原语：定宽列、分段选项列表、返回格，以及自选数值/文本输入格。
 * 只负责外观与回调，不含任何条件语义。
 */
@Composable
internal fun WizColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.width(ColumnWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

internal data class WizOption(
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal enum class WizWeight { ACTIVE, DIMMED }

@Composable
internal fun WizSegmented(
    options: List<WizOption>,
    weight: WizWeight = WizWeight.ACTIVE,
    onSegmentHeight: ((Float) -> Unit)? = null,
    onSelectedCoords: ((LayoutCoordinates) -> Unit)? = null,
) {
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(AppDimensions.Radius.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, AppColors.OutlineVariant), shape)
            .background(AppColors.Surface3),
    ) {
        options.forEachIndexed { index, option ->
            // 别按下标 key，选项换位会串颜色动画。
            key(option.label) {
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AppColors.OutlineVariant),
                    )
                }
                WizSegment(
                    option = option,
                    weight = weight,
                    onSegmentHeight = if (index == 0) onSegmentHeight else null,
                    onSelectedCoords = onSelectedCoords,
                )
            }
        }
    }
}

@Composable
internal fun WizSegment(
    option: WizOption,
    weight: WizWeight = WizWeight.ACTIVE,
    onSegmentHeight: ((Float) -> Unit)? = null,
    onSelectedCoords: ((LayoutCoordinates) -> Unit)? = null,
) {
    val active = option.selected
    val dimmed = weight == WizWeight.DIMMED
    val bg by animateColorAsState(
        targetValue = when {
            active && dimmed -> AppColors.PrimaryContainer
            active -> AppColors.Primary
            else -> AppColors.Surface3
        },
        animationSpec = Motion.normal(),
        label = "wizSegBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            active && dimmed -> AppColors.TextSecondary
            active -> AppColors.OnPrimary
            !option.enabled -> AppColors.TextTertiary
            else -> AppColors.TextSecondary
        },
        animationSpec = Motion.normal(),
        label = "wizSegFg",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onSegmentHeight?.invoke(coords.size.height.toFloat())
                if (option.selected) onSelectedCoords?.invoke(coords)
            }
            .background(bg)
            .clickable(enabled = option.enabled, onClick = option.onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            option.label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = fg,
        )
    }
}

@Composable
internal fun BackCell(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = AppColors.TextOnScrim,
            modifier = Modifier.size(15.dp),
        )
        Text("返回", fontSize = 13.sp, color = AppColors.TextOnScrim)
    }
}

@Composable
internal fun CustomNumberCell(
    unit: String,
    editable: Boolean = true,
    reportCoords: Boolean = false,
    onCoords: ((LayoutCoordinates) -> Unit)? = null,
    onPreview: ((String?) -> Unit)? = null,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val n = input.toIntOrNull() ?: 0
    val enabled = editable && n >= 1
    val confirmTint = if (enabled) AppColors.Primary else AppColors.TextTertiary
    OutlinedTextField(
        value = input,
        onValueChange = { raw ->
            if (!editable) return@OutlinedTextField
            if (raw.all { it.isDigit() } && raw.length <= 5) {
                input = raw
                onPreview?.invoke(raw.ifEmpty { null })
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (reportCoords) onCoords?.invoke(coords)
            },
        enabled = editable,
        placeholder = { Text("自选输入") },
        suffix = if (input.isNotEmpty()) {
            { Text(unit) }
        } else {
            null
        },
        trailingIcon = {
            IconButton(
                onClick = { onConfirm(n.coerceIn(1, 99999)) },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "确认",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(AppDimensions.Radius.dp),
        colors = appOutlinedTextFieldColors(
            focusedTrailingIconColor = confirmTint,
            unfocusedTrailingIconColor = confirmTint,
        ),
    )
}

@Composable
internal fun CustomTextCell(
    reportCoords: Boolean = false,
    onCoords: ((LayoutCoordinates) -> Unit)? = null,
    onPreview: ((String?) -> Unit)? = null,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val enabled = input.isNotBlank()
    val confirmTint = if (enabled) AppColors.Primary else AppColors.TextTertiary
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it.take(60)
            onPreview?.invoke(input.ifBlank { null })
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (reportCoords) onCoords?.invoke(coords)
            },
        placeholder = { Text("关键词") },
        trailingIcon = {
            IconButton(
                onClick = { onConfirm(input.trim()) },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "确认",
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(AppDimensions.Radius.dp),
        colors = appOutlinedTextFieldColors(
            focusedTrailingIconColor = confirmTint,
            unfocusedTrailingIconColor = confirmTint,
        ),
    )
}
