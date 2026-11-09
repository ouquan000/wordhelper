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
import com.kaoyan.wordhelper.data.repository.SyncRepository
import com.kaoyan.wordhelper.data.repository.WordRepository
import com.kaoyan.wordhelper.ml.core.PersonalRetentionPredictor
import com.kaoyan.wordhelper.ml.integration.MLEnhancedScheduler
import com.kaoyan.wordhelper.ml.training.ColdStartManager
import com.kaoyan.wordhelper.ml.training.ModelPersistence
import com.kaoyan.wordhelper.util.WordbookFullLoader
import com.kaoyan.wordhelper.util.WordMethodMapLoader
import com.kaoyan.wordhelper.util.BuiltinBooks
import com.kaoyan.wordhelper.util.withoutBuiltinFull
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
            bookMemoryDao = database.bookMemoryDao(),
            queueDao = database.rewriteQueueDao(),
            feedbackDao = database.rewriteFeedbackDao(),
            skippedDao = database.rewriteSkippedDao(),
            bookDao = database.bookDao()
        )
    }

    /** 学习记录同步（1.4.4）：本地排队，只在用户点「同步学习记录」时才联网。 */
    val syncRepository: SyncRepository by lazy {
        SyncRepository(database, settingsRepository)
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
        // 1.4.6.1 P1：断网恢复时补一轮学习记录同步（改写链路已有自己的监听）。
        syncRepository.startNetworkRecoverySync()
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
            // 1.4.4：两本内置大词库（各 5000+ 词）默认隐藏，隐藏时连导入都跳过——
            // 它们每次启动都会从 assets 重导，是启动耗时的大头。库里的旧记录不动，设置里打开就回来。
            val showFullBooks = settingsRepository.showBuiltinFullBooks.first()
            if (showFullBooks) {
                val presetSeeds = WordbookFullLoader.loadPresets(this@KaoyanWordApp).getOrNull()
                if (!presetSeeds.isNullOrEmpty()) {
                    repository.ensurePresetBooks(presetSeeds)
                }
            }
            // 隐藏期间，当前激活的书如果是被藏起来的那两本，自动切到可见的书，
            // 否则学习页会对着一个列表里看不见的词书空转。
            if (!showFullBooks) {
                val active = repository.getActiveBook()
                if (active != null && BuiltinBooks.isBuiltinFull(active)) {
                    val visible = repository.getAllBooks().first().withoutBuiltinFull(hide = true)
                    val target = visible.firstOrNull { it.name == "六级记忆卡" } ?: visible.firstOrNull()
                    if (target != null) {
                        repository.switchBook(target.id)
                    }
                }
            }
            // 1.4.3.2：一次性清空学习记录（进度/复习/新词引用/统计/日志/ML 样本/生词本），
            // 只在升级后第一次启动执行一次，之后正常背词不会被清。
            if (!settingsRepository.cleanup132Done()) {
                repository.resetStudyRecords()
                settingsRepository.clearTodayNewWordsPlan()
                settingsRepository.markCleanup132Done()
            }
            // 1.4.4/1.4.6：老卡片补「记忆方法」标签。资产里是由 by-word 语料抽出来的 word→method，
            // 只填空值、不覆盖用户重写过的词。1.4.6 起每次启动幂等补一次，
            // 这样种子重导新插入的空标签行也会在同一/下一次启动被补上，不再依赖一次性标记。
            runCatching {
                val methodMap = WordMethodMapLoader.load(this@KaoyanWordApp)
                if (methodMap.isNotEmpty()) {
                    repository.backfillWordMethods(methodMap)
                    // P0：种子重导不带 method，新词/老行都会停在默认的 "root"；
                    // 这里把种子行刷成 map 里真正的招式，用户重写过的（source=user）不碰。
                    repository.refreshRootMethods(methodMap)
                }
            }
            settingsRepository.markMethodBackfillDone()
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
