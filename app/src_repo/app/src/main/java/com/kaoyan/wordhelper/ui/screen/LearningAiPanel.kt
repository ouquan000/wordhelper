package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.AIContentType
import com.kaoyan.wordhelper.ui.component.AIGeneratedBadge
import com.kaoyan.wordhelper.ui.theme.AlertRed
import com.kaoyan.wordhelper.ui.viewmodel.LearningAiState

@Composable
internal fun RecognitionAiAssistantPage(
    word: Word,
    aiState: LearningAiState,
    cachedExampleContent: String,
    cachedMemoryAidContent: String,
    showMemoryAidSuggestion: Boolean,
    canGenerateExample: Boolean,
    onBack: () -> Unit,
    onGenerateExample: (Boolean) -> Unit,
    onGenerateMemoryAid: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var exampleContent by remember(word.id) { mutableStateOf(cachedExampleContent) }
    var memoryAidContent by remember(word.id) { mutableStateOf(cachedMemoryAidContent) }
    val scrollState = rememberScrollState()
    val displayWord = remember(word.word) { word.word.adaptiveWordText() }

    LaunchedEffect(cachedExampleContent) {
        if (cachedExampleContent.isNotBlank()) {
            exampleContent = cachedExampleContent
        }
    }

    LaunchedEffect(cachedMemoryAidContent) {
        if (cachedMemoryAidContent.isNotBlank()) {
            memoryAidContent = cachedMemoryAidContent
        }
    }

    LaunchedEffect(aiState.activeType, aiState.content) {
        when (aiState.activeType) {
            AIContentType.EXAMPLE -> if (aiState.content.isNotBlank()) exampleContent = aiState.content
            AIContentType.MEMORY_AID -> if (aiState.content.isNotBlank()) memoryAidContent = aiState.content
            AIContentType.SENTENCE, AIContentType.WORD_TRANSLATION, null -> Unit
        }
    }

    val isExampleLoading = aiState.activeType == AIContentType.EXAMPLE && aiState.isLoading
    val isMemoryAidLoading = aiState.activeType == AIContentType.MEMORY_AID && aiState.isLoading
    val exampleError = if (aiState.activeType == AIContentType.EXAMPLE) aiState.error else null
    val memoryAidError = if (aiState.activeType == AIContentType.MEMORY_AID) aiState.error else null
    val hasExampleContent = exampleContent.isNotBlank()
    val hasMemoryAidContent = memoryAidContent.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("learning_ai_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("learning_ai_page_back")) {
                Text(text = "返回认词")
            }
            Text(
                text = "AI 学习助手",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.size(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前单词",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = displayWord,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "例句和助记会在本页展示，生成内容仅供学习参考。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showMemoryAidSuggestion) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = AlertRed.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "刚才答错了，建议优先生成助记技巧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlertRed,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            if (!aiState.isAvailable) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                ) {
                    Text(
                        text = "AI 未启用或未配置，请先前往“我的 - AI 实验室”完成配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("learning_ai_page_unavailable")
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "例句生成",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!canGenerateExample) {
                        Text(
                            text = "请先翻卡查看释义后再生成例句。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isExampleLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("learning_ai_page_example_loading")
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(text = "正在生成例句...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!exampleError.isNullOrBlank()) {
                        Text(
                            text = exampleError,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed,
                            modifier = Modifier.testTag("learning_ai_page_example_error")
                        )
                    }
                    if (hasExampleContent) {
                        AIContentResultCard(
                            title = "AI 生成例句",
                            content = exampleContent,
                            modifier = Modifier.testTag("learning_ai_page_example_content")
                        )
                    }
                    OutlinedButton(
                        onClick = { onGenerateExample(hasExampleContent) },
                        enabled = aiState.isAvailable && !aiState.isLoading && canGenerateExample,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("learning_ai_page_example_generate")
                    ) {
                        Text(text = if (hasExampleContent) "重新生成例句" else "生成例句")
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "助记技巧",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isMemoryAidLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("learning_ai_page_memory_loading")
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(text = "正在生成助记...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!memoryAidError.isNullOrBlank()) {
                        Text(
                            text = memoryAidError,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed,
                            modifier = Modifier.testTag("learning_ai_page_memory_error")
                        )
                    }
                    if (hasMemoryAidContent) {
                        AIContentResultCard(
                            title = "AI 助记技巧",
                            content = memoryAidContent,
                            modifier = Modifier.testTag("learning_ai_page_memory_content")
                        )
                    }
                    OutlinedButton(
                        onClick = { onGenerateMemoryAid(hasMemoryAidContent) },
                        enabled = aiState.isAvailable && !aiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("learning_ai_page_memory_generate")
                    ) {
                        Text(text = if (hasMemoryAidContent) "重新生成助记" else "生成助记")
                    }
                }
            }
        }
    }
}

@Composable
fun LearningAiActionSheetContent(
    isAvailable: Boolean,
    showExampleAction: Boolean,
    showMemoryAidSuggestion: Boolean,
    hasExampleContent: Boolean,
    hasMemoryContent: Boolean,
    isLoading: Boolean,
    onGenerateExample: () -> Unit,
    onRegenerateExample: () -> Unit,
    onGenerateMemoryAid: () -> Unit,
    onRegenerateMemoryAid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("learning_ai_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "AI 助手", style = MaterialTheme.typography.titleLarge)
        if (!isAvailable) {
            Text(
                text = "AI 未启用或未配置，请先在“我的 - AI 实验室”完成设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        if (showExampleAction) {
            OutlinedButton(
                onClick = { if (hasExampleContent) onRegenerateExample() else onGenerateExample() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("learning_ai_action_example")
            ) {
                Text(text = if (hasExampleContent) "重新生成例句" else "生成例句")
            }
        } else {
            Text(
                text = "翻卡后可使用例句生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            onClick = { if (hasMemoryContent) onRegenerateMemoryAid() else onGenerateMemoryAid() },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("learning_ai_action_memory")
        ) {
            val text = when {
                showMemoryAidSuggestion && hasMemoryContent -> "重新生成助记（推荐）"
                showMemoryAidSuggestion -> "生成助记（推荐）"
                hasMemoryContent -> "重新生成助记"
                else -> "生成助记"
            }
            Text(text = text)
        }
    }
}

@Composable
fun LearningAiResultPanel(
    aiState: LearningAiState,
    showMemoryAidSuggestion: Boolean,
    onRetryExample: () -> Unit,
    onRetryMemoryAid: () -> Unit,
    onOpenAiEntry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isExampleContext = aiState.activeType == AIContentType.EXAMPLE
    val isMemoryAidContext = aiState.activeType == AIContentType.MEMORY_AID
    val shouldShow = showMemoryAidSuggestion ||
        ((isExampleContext || isMemoryAidContext) && (
            aiState.isLoading ||
                aiState.content.isNotBlank() ||
                !aiState.error.isNullOrBlank()
            ))
    if (!shouldShow) return

    val loadingText = when (aiState.activeType) {
        AIContentType.EXAMPLE -> "正在生成例句..."
        AIContentType.MEMORY_AID -> "正在生成助记..."
        AIContentType.SENTENCE -> "正在生成内容..."
        AIContentType.WORD_TRANSLATION -> "正在生成翻译..."
        null -> "正在请求 AI..."
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("learning_ai_panel"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showMemoryAidSuggestion && aiState.activeType == null && aiState.content.isBlank()) {
                Text(
                    text = "刚才答错了，点击灯泡生成 AI 助记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertRed,
                    modifier = Modifier.testTag("learning_ai_suggestion")
                )
            }

            if (aiState.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("learning_ai_loading")
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(text = loadingText, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!aiState.error.isNullOrBlank()) {
                Text(
                    text = aiState.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertRed,
                    modifier = Modifier.testTag("learning_ai_error")
                )
                if (aiState.isAvailable && aiState.activeType != null && !aiState.isLoading) {
                    OutlinedButton(
                        onClick = {
                            when (aiState.activeType) {
                                AIContentType.EXAMPLE -> onRetryExample()
                                AIContentType.MEMORY_AID -> onRetryMemoryAid()
                                AIContentType.SENTENCE -> onOpenAiEntry()
                                AIContentType.WORD_TRANSLATION -> onOpenAiEntry()
                            }
                        },
                        modifier = Modifier.testTag("learning_ai_retry")
                    ) {
                        Text(text = "重试")
                    }
                }
            }

            if (aiState.content.isNotBlank() && aiState.activeType != null) {
                val title = when (aiState.activeType) {
                    AIContentType.EXAMPLE -> "AI 生成例句"
                    AIContentType.MEMORY_AID -> "AI 助记技巧"
                    AIContentType.SENTENCE -> "AI 内容"
                    AIContentType.WORD_TRANSLATION -> "AI 中文翻译"
                }
                if (aiState.activeType == AIContentType.EXAMPLE || aiState.activeType == AIContentType.MEMORY_AID) {
                    AIContentResultCard(title = title, content = aiState.content)
                }
            }
        }
    }
}

@Composable
fun AIContentResultCard(
    title: String,
    content: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("learning_ai_content")
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "内容由 AI 生成，请甄别参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AIGeneratedBadge(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .testTag("learning_ai_badge")
        )
    }
}
