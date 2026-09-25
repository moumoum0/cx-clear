package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.util.formatBytes

/**
 * 手动管理面板的批量删除出口：底部选中条 + 删除前确认弹窗。
 *
 * 条上的按钮在删除期间整体禁用，计数与体积都由主体算好传进来。
 */
@Composable
internal fun SelectionActionBar(
    count: Int,
    bytes: Long,
    deleting: Boolean,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface1)
            .padding(horizontal = AppDimensions.SpacingMedium.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
    ) {
        Text("已选 ", fontSize = 13.sp, color = AppColors.TextSecondary)
        Text(
            "$count",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary,
        )
        Text(" 个 · ", fontSize = 13.sp, color = AppColors.TextSecondary)
        Text(
            formatBytes(bytes),
            fontSize = 13.sp,
            color = AppColors.TextPrimary,
        )

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onClear, enabled = !deleting) {
            Text("取消选择", fontSize = 13.sp, color = AppColors.TextSecondary)
        }
        Button(
            onClick = onDelete,
            enabled = !deleting,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Error,
                contentColor = AppColors.OnPrimary,
            ),
            shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
        ) {
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppColors.OnPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text("删除中", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun DeleteConfirmDialog(
    count: Int,
    bytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface1,
        title = {
            Text("删除 $count 个会话？", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
        },
        text = {
            Text(
                "将释放约 ${formatBytes(bytes)}。会话记录删除后无法恢复；如果对应的工具正在运行，该工具的会话会被整批跳过。",
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                lineHeight = 18.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Error,
                    contentColor = AppColors.OnPrimary,
                ),
                shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
            ) {
                Text("确认删除", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        },
    )
}
