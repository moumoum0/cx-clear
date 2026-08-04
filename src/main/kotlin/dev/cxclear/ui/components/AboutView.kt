package dev.cxclear.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cxclear.AppMeta
import dev.cxclear.resources.Res
import dev.cxclear.resources.bilibili
import dev.cxclear.resources.hex_knot_arrow
import dev.cxclear.resources.ic_github
import dev.cxclear.ui.theme.AppColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.awt.Desktop
import java.net.URI

/**
 * 结构对齐 selves [AboutScreen]：顶栏返回 + 图标/名称/版本 + 开发者卡（外链）+ 第三方库列表 + 致谢。
 */
@Composable
fun AboutView(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Surface1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextPrimary,
                )
            }
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                color = AppColors.TextPrimary,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 24.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AppColors.Outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.hex_knot_arrow),
                        contentDescription = AppMeta.NAME,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(72.dp),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Text(
                    text = AppMeta.NAME,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                Text(
                    text = "版本 ${AppMeta.VERSION}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(32.dp))
            }

            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppColors.Surface0),
                    border = BorderStroke(1.dp, AppColors.Outline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "开发者",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = AppColors.TextPrimary,
                            )
                            Text(
                                text = AppMeta.DEVELOPER,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.Primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = AppColors.Outline.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinkRowImage(
                                icon = Res.drawable.bilibili,
                                label = "Bilibili",
                                url = AppMeta.BILIBILI_URL,
                                modifier = Modifier.weight(1f),
                            )
                            LinkRowImage(
                                icon = Res.drawable.ic_github,
                                label = "GitHub",
                                url = AppMeta.GITHUB_URL,
                                modifier = Modifier.weight(1f),
                            )
                            LinkRowVector(
                                icon = Icons.Default.Group,
                                label = "QQ 群",
                                url = AppMeta.QQ_GROUP_URL,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Text(
                    text = "使用的第三方库",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
            }

            items(thirdPartyLibraries) { library ->
                LibraryItem(library = library)
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "感谢所有开源项目的贡献者",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LinkRowImage(
    icon: DrawableResource,
    label: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(enabled = url.isNotBlank()) { openUrl(url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun LinkRowVector(
    icon: ImageVector,
    label: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(enabled = url.isNotBlank()) { openUrl(url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = AppColors.TextPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun LibraryItem(library: ThirdPartyLibrary) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface0),
        border = BorderStroke(1.dp, AppColors.Outline),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            if (library.version.isNotEmpty()) {
                Text(
                    text = "版本 ${library.version}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.Primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = library.description,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                lineHeight = 20.sp,
            )
        }
    }
}

private data class ThirdPartyLibrary(
    val name: String,
    val version: String,
    val description: String,
)

/** 仅列本应用实际依赖，文案风格对齐 selves 关于页。 */
private val thirdPartyLibraries = listOf(
    ThirdPartyLibrary(
        name = "Compose Multiplatform",
        version = "1.11.1",
        description = "现代跨平台 UI 工具包，用于构建原生用户界面",
    ),
    ThirdPartyLibrary(
        name = "Material3",
        version = "1.11.0-alpha07",
        description = "Material Design 3组件库",
    ),
    ThirdPartyLibrary(
        name = "Material Icons Extended",
        version = "1.7.3",
        description = "Material Design扩展图标库",
    ),
    ThirdPartyLibrary(
        name = "Kotlin Coroutines",
        version = "1.9.0",
        description = "Kotlin协程库，用于异步扫描与清理",
    ),
    ThirdPartyLibrary(
        name = "JNA",
        version = "5.19.1",
        description = "Java Native Access，用于 Windows 系统交互",
    ),
    ThirdPartyLibrary(
        name = "Kotlin",
        version = "2.4.10",
        description = "Kotlin语言及协程支持",
    ),
)

private fun openUrl(url: String) {
    if (url.isBlank()) return
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
