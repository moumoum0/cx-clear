package dev.cxclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.AppMeta
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import java.awt.Desktop
import java.net.URI

// 用户来源调研：最底部的简洁小条
@Composable
internal fun SurveyPromptBar(
    onLater: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppDimensions.Radius.dp))
            .border(1.dp, AppColors.OutlineVariant, RoundedCornerShape(AppDimensions.Radius.dp))
            .background(AppColors.Surface2)
            .padding(horizontal = AppDimensions.SpacingMedium.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "你从哪知道 CX Clear 的？",
                fontSize = 12.sp,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "前往 GitHub 填写",
                fontSize = 10.sp,
                color = AppColors.TextSecondary,
            )
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onLater) {
                Text("稍后", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            TextButton(onClick = onDone) {
                Text("不显示", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Button(
                onClick = {
                    openSurveyIssue()
                    onDone()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Primary,
                    contentColor = AppColors.OnPrimary,
                ),
                shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
            ) {
                Text("去填写", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun openSurveyIssue() {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(AppMeta.SURVEY_ISSUE_URL))
        }
    }
}
