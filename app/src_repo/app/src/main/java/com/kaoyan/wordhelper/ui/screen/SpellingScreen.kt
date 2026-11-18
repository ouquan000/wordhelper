package com.kaoyan.wordhelper.ui.screen

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.ui.component.AIGeneratedBadge
import com.kaoyan.wordhelper.ui.component.StandardPanel
import com.kaoyan.wordhelper.ui.theme.AlertRed
import com.kaoyan.wordhelper.ui.theme.KnownGreen
import com.kaoyan.wordhelper.util.SpellingEvaluator

@Composable
fun SpellingScreen(
    word: Word?,
    presentationKey: Int,
    isSubmitting: Boolean,
    algorithmV4Enabled: Boolean = false,
    onSpellingResolved: (SpellingOutcome, Int, Long) -> Unit,
    onContinueAfterFailure: () -> Unit,
    aiAssistAvailable: Boolean = false,
    aiAssistLoading: Boolean = false,
    aiAssistText: String? = null,
    aiAssistError: String? = null,
    onRequestAiAssist: (forceRefresh: Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (word == null) {
        Text(
            text = "暂无单词",
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val target = word.word
    val wordStartAt = remember(word.id, presentationKey) { System.currentTimeMillis() }
    val contentScrollState = rememberScrollState()

    var input by remember(word.id, presentationKey) { mutableStateOf("") }
    var copyInput by remember(word.id, presentationKey) { mutableStateOf("") }
    var status by remember(word.id, presentationKey) { mutableStateOf(SpellingStatus.Idle) }
    var attemptCount by remember(word.id, presentationKey) { mutableStateOf(0) }
    var showFirstHint by remember(word.id, presentationKey) { mutableStateOf(false) }
    var showLengthHint by remember(word.id, presentationKey) { mutableStateOf(false) }
    val usedHint = showFirstHint || showLengthHint
    val canEditInput = !isSubmitting && status != SpellingStatus.CopyRequired
    val canUseHints = !isSubmitting && status != SpellingStatus.CopyRequired
    val canShowAiAssistAction = status == SpellingStatus.Wrong || status == SpellingStatus.CopyRequired

    fun submit() {
        if (isSubmitting || status == SpellingStatus.CopyRequired) return
        val answer = input.trim()
        if (answer.isBlank()) return
        focusManager.clearFocus()

        val currentAttempt = attemptCount + 1
        attemptCount = currentAttempt
        val outcome = SpellingEvaluator.evaluate(
            input = answer,
            correctWord = target,
            hintUsed = usedHint,
            attemptCount = currentAttempt,
            algorithmV4Enabled = algorithmV4Enabled
        )
        if (outcome != SpellingOutcome.FAILED) {
            status = SpellingStatus.Correct
            vibrate(context)
            onSpellingResolved(outcome, currentAttempt, elapsedDuration(wordStartAt))
            return
        }

        status = SpellingStatus.Wrong
        if (currentAttempt >= MAX_ATTEMPTS) {
            status = SpellingStatus.CopyRequired
            onSpellingResolved(SpellingOutcome.FAILED, currentAttempt, elapsedDuration(wordStartAt))
        }
    }

    val attemptProgress = (attemptCount.coerceAtMost(MAX_ATTEMPTS)).toFloat() / MAX_ATTEMPTS.toFloat()
    val remainingAttempts = (MAX_ATTEMPTS - attemptCount).coerceAtLeast(0)
    val statusText = when (status) {
        SpellingStatus.Idle -> "根据释义拼写单词"
        SpellingStatus.Correct -> "拼写正确，正在进入下一词"
        SpellingStatus.Wrong -> "拼写有误，可继续尝试"
        SpellingStatus.CopyRequired -> "已达最大尝试次数，需抄写后继续"
    }
    val statusColor = when (status) {
        SpellingStatus.Idle -> MaterialTheme.colorScheme.primary
        SpellingStatus.Correct -> KnownGreen
        SpellingStatus.Wrong, SpellingStatus.CopyRequired -> AlertRed
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(contentScrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶部进度卡：透明底 + 20dp 圆角 + 1dp 细描边；文字颜色全部交给 MaterialTheme，日夜自动反转。
            StandardPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "拼写练习",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$attemptCount/$MAX_ATTEMPTS 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                    LinearProgressIndicator(
                        progress = { attemptProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 6.dp),
                        color = statusColor,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    Text(
                        text = if (status == SpellingStatus.CopyRequired) {
                            "本词已进入抄写纠错阶段"
                        } else {
                            "当前剩余尝试次数：$remainingAttempts"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 音标释义卡：同样透明细描边；音标小胶囊用主色 10% 透明底色 + 主色文字，不再用黑色实块。
            StandardPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "根据释义拼写英文单词",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (word.phonetic.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = word.phonetic,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (word.meaning.isNotBlank()) {
                        Text(
                            text = word.meaning,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 提示工具卡：小胶囊保持透明/主色淡底，提示结果区用淡主色 + 细描边，跟随主题。
            StandardPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "提示工具（会降低评分）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = showFirstHint,
                            onClick = {
                                if (canUseHints) {
                                    showFirstHint = !showFirstHint
                                }
                            },
                            enabled = canUseHints,
                            label = { Text(text = "首字母") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        FilterChip(
                            selected = showLengthHint,
                            onClick = {
                                if (canUseHints) {
                                    showLengthHint = !showLengthHint
                                }
                            },
                            enabled = canUseHints,
                            label = { Text(text = "长度") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    if (showFirstHint || showLengthHint) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (showFirstHint) {
                                Text(
                                    text = "首字母：${target.firstOrNull() ?: '-'}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (showLengthHint) {
                                Text(
                                    text = "长度：${target.length}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            if (status == SpellingStatus.CopyRequired) {
                // 抄写提示条：透明细描边，用主题 error 色提示，浅色/深色都清晰。
                StandardPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "本轮需抄写正确拼写后才能继续",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        // 底部输入 + 提交区：一块透明细描边面板，输入框与按钮颜色全部由主题控制。
        StandardPanel(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (status == SpellingStatus.CopyRequired) {
                    Text(
                        text = "正确拼写：$target",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("spelling_correct_answer")
                    )
                    OutlinedTextField(
                        value = copyInput,
                        onValueChange = {
                            if (!isSubmitting) {
                                copyInput = it
                            }
                        },
                        label = { Text(text = "请抄写正确拼写后继续") },
                        singleLine = true,
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (copyInput.trim().equals(target, ignoreCase = true)) {
                                onContinueAfterFailure()
                            }
                        }),
                        colors = spellingFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spelling_copy_input")
                    )
                    Button(
                        onClick = {
                            if (copyInput.trim().equals(target, ignoreCase = true)) {
                                onContinueAfterFailure()
                            }
                        },
                        enabled = !isSubmitting && copyInput.trim().equals(target, ignoreCase = true),
                        colors = spellingSubmitButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spelling_continue_after_copy")
                    ) {
                        Text(text = "继续")
                    }
                    if (canShowAiAssistAction) {
                        SpellingAIAssistSection(
                            aiAssistAvailable = aiAssistAvailable,
                            aiAssistLoading = aiAssistLoading,
                            aiAssistText = aiAssistText,
                            aiAssistError = aiAssistError,
                            onRequestAiAssist = onRequestAiAssist,
                            enabled = !isSubmitting
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            if (!canEditInput) return@OutlinedTextField
                            input = it
                            if (status == SpellingStatus.Wrong) {
                                status = SpellingStatus.Idle
                            }
                        },
                        label = { Text(text = "请输入单词拼写") },
                        singleLine = true,
                        enabled = canEditInput,
                        isError = status == SpellingStatus.Wrong,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        colors = spellingFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spelling_input")
                    )

                    if (input.isNotBlank()) {
                        SpellingMatchIndicator(input = input, target = target)
                    }
                    if (status == SpellingStatus.Wrong && attemptCount < MAX_ATTEMPTS) {
                        Text(
                            text = "拼写错误，请再试一次（剩余 ${MAX_ATTEMPTS - attemptCount} 次）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("spelling_retry_hint")
                        )
                    }

                    val buttonText = if (attemptCount > 0) "重试" else "提交"
                    Button(
                        onClick = { submit() },
                        enabled = !isSubmitting && input.isNotBlank(),
                        colors = spellingSubmitButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spelling_submit")
                    ) {
                        Text(text = buttonText)
                    }
                    if (canShowAiAssistAction) {
                        SpellingAIAssistSection(
                            aiAssistAvailable = aiAssistAvailable,
                            aiAssistLoading = aiAssistLoading,
                            aiAssistText = aiAssistText,
                            aiAssistError = aiAssistError,
                            onRequestAiAssist = onRequestAiAssist,
                            enabled = !isSubmitting
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * 输入框统一配色：文字/标签/占位符全部取自 MaterialTheme。
 * 浅色模式下 onSurface 是深墨色、onSurfaceVariant 是中灰，占位符不会发白看不清；
 * 深色模式下两者自动变浅。容器保持透明，让 StandardPanel 的细描边成为唯一轮廓。
 */
@Composable
private fun spellingFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.50f),
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
    errorLabelColor = MaterialTheme.colorScheme.error,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    errorPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/**
 * 主操作按钮配色：主色实底 + onPrimary 文字（唯一允许“白字”的地方，深色主题下会自动变深字）。
 * 禁用态换成中性半透明底 + onSurface 半透明文字，保证浅色/深色下都还能读出按钮上的字。
 */
@Composable
private fun spellingSubmitButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
)

@Composable
private fun SpellingMatchIndicator(input: String, target: String) {
    val annotated = buildAnnotatedString {
        input.forEachIndexed { index, char ->
            val expected = target.getOrNull(index)
            val color = if (expected != null && char.equals(expected, ignoreCase = true)) {
                KnownGreen
            } else {
                AlertRed
            }
            withStyle(SpanStyle(color = color)) {
                append(char)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "纠错提示：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpellingAIAssistSection(
    aiAssistAvailable: Boolean,
    aiAssistLoading: Boolean,
    aiAssistText: String?,
    aiAssistError: String?,
    onRequestAiAssist: (forceRefresh: Boolean) -> Unit,
    enabled: Boolean
) {
    // AI 记忆卡：同样走 StandardPanel（透明底 + 细描边），不再套深色玻璃底板。
    StandardPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onRequestAiAssist(!aiAssistText.isNullOrBlank()) },
                enabled = aiAssistAvailable && enabled && !aiAssistLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spelling_ai_memory")
            ) {
                if (aiAssistLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 6.dp).height(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(text = "正在生成 AI 助记...")
                } else {
                    Text(text = if (aiAssistText.isNullOrBlank()) "AI 助记" else "重新生成 AI 助记")
                }
            }

            if (!aiAssistAvailable) {
                Text(
                    text = "AI 未启用或未配置，请前往“我的 - AI 实验室”设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!aiAssistError.isNullOrBlank()) {
                Text(
                    text = aiAssistError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (!aiAssistText.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "AI 助记",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = aiAssistText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "内容由 AI 生成，请甄别参考。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AIGeneratedBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .testTag("spelling_ai_badge")
                    )
                }
            }
        }
    }
}

private enum class SpellingStatus {
    Idle,
    Correct,
    Wrong,
    CopyRequired
}

private const val MAX_ATTEMPTS = 3

private fun elapsedDuration(startAt: Long): Long {
    return (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
}

private fun vibrate(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
}
