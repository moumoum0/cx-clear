package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.util.formatBytes

/**
 * 扫描页顶栏：左侧工具选择器 + 右侧扫描 / 清理按钮。
 * 工具按钮统一走 [ToolEntries]，默认只露前三个，其余由箭头展开。
 */

@Composable
internal fun TopBar(
    selectedTools: Set<String>,
    onToolToggle: (String) -> Unit,
    scanPhase: ScanPhase,
    onStartScan: () -> Unit,
    showClean: Boolean,
    isCleaning: Boolean,
    selectedBytes: Long,
    cleanEnabled: Boolean,
    onRequestClean: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolSelector(selectedTools, onToolToggle)

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showClean) {
                    OutlinedButton(
                        onClick = onStartScan,
                        enabled = !isCleaning && selectedTools.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AppColors.Primary,
                            disabledContentColor = AppColors.Primary.copy(alpha = 0.5f),
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    ) {
                        Text("重新扫描", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    val cleanBg = if (cleanEnabled) AppColors.Primary else AppColors.PrimaryContainer
                    val cleanFg = if (cleanEnabled) AppColors.OnPrimary else AppColors.Primary
                    Button(
                        onClick = onRequestClean,
                        enabled = cleanEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cleanBg,
                            contentColor = cleanFg,
                            disabledContainerColor = cleanBg,
                            disabledContentColor = cleanFg,
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    ) {
                        if (isCleaning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AppColors.OnPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在清理…", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        } else {
                            Text(
                                text = if (selectedBytes > 0L) {
                                    "清理选中 ${formatBytes(selectedBytes)}"
                                } else {
                                    "清理选中项"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                } else {
                    val scanEnabled = !isCleaning && scanPhase != ScanPhase.SCANNING && selectedTools.isNotEmpty()
                    val scanBg = if (scanEnabled) AppColors.Primary else AppColors.PrimaryContainer
                    val scanFg = if (scanEnabled) AppColors.OnPrimary else AppColors.Primary
                    Button(
                        onClick = onStartScan,
                        enabled = scanEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scanBg,
                            contentColor = scanFg,
                            disabledContainerColor = scanBg,
                            disabledContentColor = scanFg,
                        ),
                        shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = when (scanPhase) {
                                ScanPhase.IDLE -> "开始扫描"
                                ScanPhase.SCANNING -> "正在扫描"
                                ScanPhase.DONE -> "重新扫描"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
        }
    }
}

@Composable
internal fun ToolSelector(
    selectedTools: Set<String>,
    onToolToggle: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 前三个常驻，其余收在箭头后面；顺序由 ToolEntries 决定。
        ToolEntries.take(3).forEach { entry ->
            key(entry.id) {
                ToolIcon(entry.name, entry.resource, entry.id in selectedTools) { onToolToggle(entry.id) }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimensions.SpacingSmall.dp)) {
                ToolEntries.drop(3).forEach { entry ->
                    key(entry.id) {
                        ToolIcon(entry.name, entry.resource, entry.id in selectedTools) { onToolToggle(entry.id) }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .background(AppColors.Surface3, RoundedCornerShape(AppDimensions.Radius.dp))
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开更多工具",
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
