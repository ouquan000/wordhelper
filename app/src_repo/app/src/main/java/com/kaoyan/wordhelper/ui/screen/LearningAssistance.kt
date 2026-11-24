package com.kaoyan.wordhelper.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.data.entity.RewriteQueueItem
import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.PronunciationSource
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.ui.viewmodel.LearningAiState
import com.kaoyan.wordhelper.ui.viewmodel.LearningViewModel
import com.kaoyan.wordhelper.ui.viewmodel.RewriteProgressUi
import com.kaoyan.wordhelper.util.PronunciationPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LearningAssistantContent(
    currentWord: Word,
    aiState: LearningAiState,
    cachedExampleContent: String,
    cachedMemoryAidContent: String,
    showMemoryAidSuggestion: Boolean,
    canGenerateExample: Boolean,
    navDrawerDepthScale: () -> Float,
    onBack: () -> Unit,
    viewModel: LearningViewModel
) {
    // AI 助手页是整页替换：沿用旧的「整层缩」观感，用一层全屏 Box 承担缩放。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = navDrawerDepthScale()
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            }
    ) {
        RecognitionAiAssistantPage(
            word = currentWord,
            aiState = aiState,
            cachedExampleContent = cachedExampleContent,
            cachedMemoryAidContent = cachedMemoryAidContent,
            showMemoryAidSuggestion = showMemoryAidSuggestion,
            canGenerateExample = canGenerateExample,
            onBack = onBack,
            onGenerateExample = { forceRefresh -> viewModel.requestAiExample(forceRefresh) },
            onGenerateMemoryAid = { forceRefresh -> viewModel.requestAiMemoryAid(forceRefresh) }
        )
    }
}

internal suspend fun playLearningWordAudio(
    context: Context,
    appContext: KaoyanWordApp,
    pronunciationPlayer: PronunciationPlayer,
    word: Word,
    pronunciationSource: () -> PronunciationSource
) {
    val result = withContext(Dispatchers.IO) {
        appContext.pronunciationRepository.getPronunciationAudioUrl(
            word = word.word,
            preferredSource = pronunciationSource()
        )
    }
    result.onSuccess { audioUrl ->
        pronunciationPlayer.play(
            url = audioUrl,
            onError = { errMsg ->
                Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }.onFailure { throwable ->
        Toast.makeText(context, throwable.message ?: "单词发音失败", Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun LearningRewriteActions(
    viewModel: LearningViewModel,
    wordId: Long,
    mark: RewriteQueueItem?,
    models: List<RewriteModel>,
    selectedModelId: String,
    onInstruction: (RewriteInstructionRequest) -> Unit,
    feedback: String?,
    likeCount: Int,
    enabled: Boolean,
    progress: RewriteProgressUi?
) {
    RewriteBar(
        wordId = wordId,
        mark = mark,
        models = models,
        selectedModelId = selectedModelId,
        onSelectModel = viewModel::selectRewriteModel,
        onRewrite = viewModel::markRewrite,
        onCancel = viewModel::cancelRewrite,
        onInstruction = onInstruction,
        onFeedbackUp = { count, reasons, comment ->
            viewModel.submitRewriteFeedback(
                rating = RewriteFeedbackRating.UP,
                count = count,
                reasons = reasons,
                comment = comment
            )
        },
        onFeedbackDown = { count, reasons, comment ->
            viewModel.submitRewriteFeedback(
                rating = RewriteFeedbackRating.DOWN,
                count = count,
                reasons = reasons,
                comment = comment
            )
        },
        feedback = feedback,
        likeCount = likeCount,
        enabled = enabled,
        progress = progress
    )
}
