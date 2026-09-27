package dev.cxclear.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.cxclear.model.CleanTarget
import dev.cxclear.model.DeletionPlan
import dev.cxclear.model.Risk
import dev.cxclear.model.ScanResult
import dev.cxclear.model.TargetKey
import dev.cxclear.model.ToolProfile
import dev.cxclear.ui.theme.AppColors

/**
 * 扫描结果的分类桶与展示模型：扫描页的状态机、圆柱图、勾选行共用这些类型。
 * [buildCategories] 决定「应用共占用」如何拆成四个分类，[categoryAccent] 只负责取色。
 */

internal enum class ScanPhase { IDLE, SCANNING, DONE }

internal data class ScanCategory(
    val id: String,
    val label: String,
    val bytes: Long,
    val items: List<ScanTargetItem> = emptyList(),
)

internal data class ScanTargetItem(
    val key: TargetKey,
    val label: String,
    val description: String,
    val bytes: Long,
    val risk: Risk,
    val defaultSelected: Boolean,
    val deletionPlan: DeletionPlan?,
)

internal fun buildCategories(
    profiles: List<ToolProfile>,
    results: List<ScanResult>,
    totalToolBytes: Long,
): List<ScanCategory> {
    val resultByTarget = results.associateBy { TargetKey(it.toolId, it.targetId) }
    val targets = profiles.flatMap { profile -> profile.targets.map { profile to it } }

    fun item(profile: ToolProfile, target: CleanTarget): ScanTargetItem {
        val key = TargetKey(profile.id, target.id)
        val result = resultByTarget.getValue(key)
        return ScanTargetItem(
            key = key,
            label = "${profile.name} · ${target.label}",
            description = target.description,
            bytes = result.bytes,
            risk = target.risk,
            defaultSelected = target.defaultSelected,
            deletionPlan = result.deletionPlan,
        )
    }

    // target.id 的子串关键字决定进哪个桶：新 id 想进「插件与安装缓存」，
    // 必须带上 plugins / downloads / sandbox / vendor / extension / cached / runtime 之一。
    val packageItems = targets
        .filter { (profile, target) ->
            resultByTarget[TargetKey(profile.id, target.id)]?.exists == true &&
                target.risk == Risk.SAFE &&
                listOf("plugins", "downloads", "sandbox", "vendor", "extension", "cached", "runtime").any {
                    target.id.contains(it)
                }
        }
        .map { (profile, target) -> item(profile, target) }
    val workingItems = targets
        .filter { (profile, target) ->
            val key = TargetKey(profile.id, target.id)
            resultByTarget[key]?.exists == true &&
                target.risk == Risk.SAFE &&
                packageItems.none { it.key == key }
        }
        .map { (profile, target) -> item(profile, target) }
    val historyItems = targets
        .filter { (profile, target) ->
            resultByTarget[TargetKey(profile.id, target.id)]?.exists == true &&
                target.risk == Risk.OPTIONAL
        }
        .map { (profile, target) -> item(profile, target) }

    val knownBytes = (packageItems + workingItems + historyItems).sumOf { it.bytes }
    val retainedBytes = (totalToolBytes - knownBytes).coerceAtLeast(0L)

    return listOf(
        ScanCategory(
            id = "retained",
            label = "应用保留数据",
            bytes = retainedBytes,
        ),
        ScanCategory(
            id = "packages",
            label = "插件与安装缓存",
            bytes = packageItems.sumOf { it.bytes },
            items = packageItems,
        ),
        ScanCategory(
            id = "working",
            label = "日志与临时文件",
            bytes = workingItems.sumOf { it.bytes },
            items = workingItems,
        ),
        ScanCategory(
            id = "history",
            label = "历史与会话",
            bytes = historyItems.sumOf { it.bytes },
            items = historyItems,
        ),
    )
}

internal fun emptyScanCategories(): List<ScanCategory> = listOf(
    ScanCategory(
        id = "retained",
        label = "应用保留数据",
        bytes = 0L,
    ),
    ScanCategory(
        id = "packages",
        label = "插件与安装缓存",
        bytes = 0L,
    ),
    ScanCategory(
        id = "working",
        label = "日志与临时文件",
        bytes = 0L,
    ),
    ScanCategory(
        id = "history",
        label = "历史与会话",
        bytes = 0L,
    ),
)

// 纯函数取色，@Composable 是历史遗留，别去掉：调用点都在 @Composable 作用域内。
@Composable
internal fun categoryAccent(id: String): Color = when (id) {
    "packages" -> AppColors.CategoryPackages
    "working" -> AppColors.CategoryWorking
    "history" -> AppColors.CategoryHistory
    else -> AppColors.CategoryRetained
}
