package com.kaoyan.wordhelper

import android.app.Application
import com.kaoyan.wordhelper.data.database.AppDatabase
import com.kaoyan.wordhelper.data.model.CardSeedLoader
import com.kaoyan.wordhelper.data.repository.AIConfigRepository
import com.kaoyan.wordhelper.data.repository.AIRepository
import com.kaoyan.wordhelper.data.repository.CardBackRepository
import com.kaoyan.wordhelper.data.repository.ForecastRepository
import com.kaoyan.wordhelper.data.repository.PronunciationRepository
import com.kaoyan.wordhelper.data.repository.RewriteRepository
import com.kaoyan.wordhelper.data.repository.SettingsRepository
import com.kaoyan.wordhelper.data.repository.WordRepository
import com.kaoyan.wordhelper.ml.core.PersonalRetentionPredictor
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.ml.training.ColdStartManager
import com.kaoyan.wordhelper.ml.training.ModelPersistence
import com.kaoyan.wordhelper.util.WordbookFullLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class KaoyanWordApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: WordRepository by lazy {
        WordRepository(database)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    val forecastRepository: ForecastRepository by lazy {
        ForecastRepository(database, settingsRepository)
    }

    /** 卡背结构清单（1.4.2）：只读文件 + 网络，不碰数据库。 */
    val cardBackRepository: CardBackRepository by lazy {
        CardBackRepository(this, settingsRepository)
    }

    /** 重写记忆思路（1.4.3）：本地标记队列 + 电脑端同步，绝不直接调模型。 */
    val rewriteRepository: RewriteRepository by lazy {
        RewriteRepository(
            settingsRepository = settingsRepository,
            wordDao = database.wordDao(),
            wordMemoryDao = database.wordMemoryDao(),
            queueDao = database.rewriteQueueDao()
        )
    }

    val aiConfigRepository: AIConfigRepository by lazy {
        AIConfigRepository(this)
    }

    val aiRepository: AIRepository by lazy {
        AIRepository(database, aiConfigRepository)
    }

    val pronunciationRepository: PronunciationRepository by lazy {
        val pronunciationIndex = WordbookFullLoader
            .loadPronunciationIndex(this)
            .getOrDefault(emptyMap())
        PronunciationRepository(wordbookPronunciations = pronunciationIndex)
    }

    val mlPredictor: PersonalRetentionPredictor by lazy {
        PersonalRetentionPredictor()
    }

    val mlModelPersistence: ModelPersistence by lazy {
        ModelPersistence(database.mlModelStateDao())
    }

    val mlColdStartManager: ColdStartManager by lazy {
        val prior = ModelPersistence.loadPopulationPrior(this)
        ColdStartManager(prior)
    }

    val mlScheduler: MLEnhancedScheduler by lazy {
        MLEnhancedScheduler(mlPredictor, mlColdStartManager)
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // 卡背结构：先读本地缓存，读不到就用内置默认；不阻塞首屏
            cardBackRepository.bootstrap()
            val settings = settingsRepository.settingsFlow.first()
            if (settings.algorithmV4Enabled) {
                repository.repairMasteredStatusForV4()
            }
            // 内置种子词库：首次启动导入，种子 version 变大时原地更新（进度不重置）
            val cardSeed = CardSeedLoader.load(this@KaoyanWordApp)
            if (cardSeed != null && cardSeed.version > settingsRepository.cardSeedVersion()) {
                val seededBookId = repository.ensureCardSeedBook(cardSeed)
                if (seededBookId > 0L) {
                    settingsRepository.updateCardSeedVersion(cardSeed.version)
                }
            }
            val presetSeeds = WordbookFullLoader.loadPresets(this@KaoyanWordApp).getOrNull()
            if (!presetSeeds.isNullOrEmpty()) {
                repository.ensurePresetBooks(presetSeeds)
            }
            // 1.4.3.2：一次性清空学习记录（进度/复习/新词引用/统计/日志/ML 样本/生词本），
            // 只在升级后第一次启动执行一次，之后正常背词不会被清。
            if (!settingsRepository.cleanup132Done()) {
                repository.resetStudyRecords()
                settingsRepository.clearTodayNewWordsPlan()
                settingsRepository.markCleanup132Done()
            }
            // 1.4.3.2：20 词测试书，只建书不切换；导入过一次就不再自动建（删了也不复活）。
            val testSeed = CardSeedLoader.loadTest(this@KaoyanWordApp)
            if (testSeed != null && testSeed.version > settingsRepository.testDeckVersion()) {
                val testBookId = repository.ensureTestDeck(testSeed)
                if (testBookId > 0L) {
                    settingsRepository.updateTestDeckVersion(testSeed.version)
                }
            }
            // 重写思路：等种子导完再拉结果。换机/清数据后本地没有任何标记，
            // 靠这一步把电脑端的历史改写恢复回来（查不到词的条目会留到下次同步）。
            runCatching { rewriteRepository.sync() }
            // ML模型初始化
            if (settings.mlAdaptiveEnabled) {
                initializeMLEngine()
            }
        }
    }

    private suspend fun initializeMLEngine() {
        mlModelPersistence.ensureInitialized()
        val modelState = mlModelPersistence.load(mlPredictor)
        mlScheduler.setModelState(modelState)
        if (mlPredictor.sampleCount == 0) {
            mlColdStartManager.initializePredictor(mlPredictor)
        }
    }

    suspend fun ensureMLEngineInitialized() {
        initializeMLEngine()
    }
}
