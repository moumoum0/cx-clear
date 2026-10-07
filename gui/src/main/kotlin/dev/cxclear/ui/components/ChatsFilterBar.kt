package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatAxis
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.Motion

@Composable
internal fun ChatsFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    axis: ChatAxis,
    grouped: Boolean,
    ascending: Boolean,
    onAxisClick: (ChatAxis) -> Unit,
    onToggleOrder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDimensions.SpacingSmall.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        ChatAxis.entries.forEach { entry ->
            val selected = entry == axis
            AxisPill(
                label = entry.label,
                selected = selected,
                grouped = selected && grouped && entry.groupDimension != null,
                onClick = { onAxisClick(entry) },
            )
        }

        Spacer(Modifier.weight(1f))

        OrderToggle(ascending = ascending, onClick = onToggleOrder)

        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.width(SearchFieldWidth),
        )
    }
}

internal val SearchFieldWidth = 160.dp

@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(AppColors.Surface3)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(14.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索", fontSize = 12.sp, color = AppColors.TextTertiary)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = TextStyle(fontSize = 12.sp, color = AppColors.TextPrimary),
                singleLine = true,
                cursorBrush = SolidColor(AppColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "清空",
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

@Composable
internal fun AxisPill(
    label: String,
    selected: Boolean,
    grouped: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) AppColors.Primary else AppColors.Surface3,
        animationSpec = Motion.normal(),
        label = "axisPillBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) AppColors.OnPrimary else AppColors.TextSecondary,
        animationSpec = Motion.normal(),
        label = "axisPillFg",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedVisibility(
            visible = grouped,
            enter = expandHorizontally(Motion.fast()) + fadeIn(Motion.fast()),
            exit = shrinkHorizontally(Motion.fast()) + fadeOut(Motion.fast()),
        ) {
            Icon(
                imageVector = Icons.Filled.Segment,
                contentDescription = "已按 $label 分组",
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
internal fun OrderToggle(ascending: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (ascending) 0f else 180f,
        animationSpec = Motion.normal(),
        label = "orderArrow",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppDimensions.RadiusFull.dp))
            .background(AppColors.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowUpward,
            contentDescription = if (ascending) "升序" else "降序",
            tint = AppColors.TextSecondary,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
        Text(
            if (ascending) "升序" else "降序",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
        )
    }
}
