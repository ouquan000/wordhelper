package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.ui.component.AppPageHeader
import com.kaoyan.wordhelper.ui.component.StandardPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilePageLayout(
    onOpenDrawer: () -> Unit,
    onOpenUiDemo: () -> Unit,
    sections: LazyListScope.() -> Unit
) {
    val pageBrush = Brush.verticalGradient(
        colors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            Color.Transparent
        )
    )

    Scaffold(
        // 状态栏内边距由外层 AppNavigation 统一处理，这里不再吃第二遍
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBrush)
                .padding(innerPadding)
        ) {
            AppPageHeader(
                title = "我的",
                onOpenDrawer = onOpenDrawer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .testTag("profile_list"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                // UI 动效沙盒临时入口（feat/frontend-ui）：放在第一个 item，StatsCard 之前。
                item {
                    // 沙盒入口：统一成标准透明细描边面板，不再单独铺 primary 实心底；
                    // 标题与图标仍用 primary 做强调，日夜模式都跟着主题走。
                    StandardPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_ui_demo_entry"),
                        onClick = onOpenUiDemo
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "进入 UI 动效沙盒",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "心流燃烧进度条：速度档位 / 24 套色盘 / 颗粒流光",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "试试 ›",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                sections()
            }
        }
    }
}

@Composable
internal fun ProfileAboutCard() {
    // 版本号现读系统里的安装信息：BuildConfig 的常量会被增量编译内联成旧值（1.4.5 时还显示 1.4.4）。
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // 关于卡：统一透明细描边，版本信息等文案全部走主题色。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "关于软件", style = MaterialTheme.typography.titleLarge)
            Text(text = "真的不想背单词了:(", fontWeight = FontWeight.SemiBold)
            Text(text = "版本 $versionName", style = MaterialTheme.typography.bodySmall)
            Text(text = "完全离线运行，无广告干扰。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ProfileLabEntryCard(
    statusText: String,
    onOpenAiLab: () -> Unit
) {
    // 实验室入口：透明细描边面板，标题与正文沿用主题文字色。
    StandardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "实验室", style = MaterialTheme.typography.titleLarge)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "包含 AI 实验室、发音实验室、算法实验室。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenAiLab,
                modifier = Modifier.testTag("profile_open_ai_lab")
            ) {
                Text(text = "进入实验室")
            }
        }
    }
}
