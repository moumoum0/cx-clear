package dev.cxclear.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.chats.ChatCondition
import dev.cxclear.chats.ConditionJoin
import dev.cxclear.chats.RetentionAiPrompt
import dev.cxclear.chats.RetentionConfig
import dev.cxclear.chats.RetentionRule
import dev.cxclear.chats.newRuleId
import dev.cxclear.ui.LocalOverlayHost
import dev.cxclear.ui.theme.AppColors
import dev.cxclear.ui.theme.AppDimensions
import dev.cxclear.ui.theme.appOutlinedTextFieldColors
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自动清理策略页入口：策略列表 + 命名对话框 + 规则编辑浮层宿主。
 * 列表见 RetentionRuleList，向导骨架见 RetentionWizardLayout，文案见 RetentionRuleText。
 */
@Composable
internal fun ChatsAutoPane(
    config: RetentionConfig,
    onConfigChange: (RetentionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlayHost = LocalOverlayHost.current
    var namingForNew by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun openWizard(
        ruleName: String,
        initial: RetentionRule? = null,
        onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
    ) {
        overlayHost.show {
            WizardOverlay(
                ruleName = ruleName,
                initialConditions = initial?.conditions.orEmpty(),
                initialJoin = initial?.join ?: ConditionJoin.AND,
                onDismiss = { overlayHost.hide() },
                onSave = onSave,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        RuleListView(
            config = config,
            onConfigChange = onConfigChange,
            onNewRule = { namingForNew = true },
            onAiPrompt = {
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(RetentionAiPrompt.text), null)
                }
                showSnackbar = true
                scope.launch {
                    delay(3000)
                    showSnackbar = false
                }
            },
            onEditRule = { rule ->
                openWizard(ruleName = rule.name.ifBlank { "未命名策略" }, initial = rule) { conditions, join ->
                    onConfigChange(
                        config.copy(
                            rules = config.rules.map {
                                if (it.id == rule.id) it.copy(conditions = conditions, join = join) else it
                            },
                        ),
                    )
                    overlayHost.hide()
                }
            },
        )
        AnimatedVisibility(
            visible = showSnackbar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        ) {
            Snackbar {
                Text("提示词已复制，请粘贴给任意Agent工具", fontSize = 13.sp)
            }
        }
    }

    if (namingForNew) {
        NameRuleDialog(
            onDismiss = { namingForNew = false },
            onConfirm = { name ->
                namingForNew = false
                openWizard(ruleName = name) { conditions, join ->
                    val rule = RetentionRule(
                        id = newRuleId(config.rules.map { it.id }),
                        name = name,
                        enabled = false,
                        join = join,
                        conditions = conditions,
                    )
                    onConfigChange(config.copy(rules = config.rules + rule))
                    overlayHost.hide()
                }
            },
        )
    }
}

@Composable
private fun NameRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val trimmed = input.trim()
    val enabled = trimmed.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface1,
        title = {
            Text(
                "给这条策略起个名字",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(30) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("策略名称") },
                shape = RoundedCornerShape(AppDimensions.Radius.dp),
                colors = appOutlinedTextFieldColors(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (enabled) onConfirm(trimmed) },
                enabled = enabled,
                shape = RoundedCornerShape(AppDimensions.RadiusFull.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Primary,
                    contentColor = AppColors.OnPrimary,
                ),
            ) {
                Text("下一步")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun WizardOverlay(
    ruleName: String,
    initialConditions: List<ChatCondition> = emptyList(),
    initialJoin: ConditionJoin = ConditionJoin.AND,
    onDismiss: () -> Unit,
    onSave: (List<ChatCondition>, ConditionJoin) -> Unit,
) {
    var draft by remember {
        mutableStateOf(draftFromExisting(initialConditions, initialJoin))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = AppDimensions.SidebarWidth.dp, top = AppDimensions.TitleBarHeight.dp)
                .padding(AppDimensions.SpacingLarge.dp)
                // 吞点击，别落到 scrim 上取消。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            WizardView(
                draft = draft,
                onDraftChange = { draft = it },
                onCancel = onDismiss,
                onSave = onSave,
            )
        }
        Text(
            ruleName,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextOnScrim,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(AppDimensions.SpacingLarge.dp),
        )
    }
}
