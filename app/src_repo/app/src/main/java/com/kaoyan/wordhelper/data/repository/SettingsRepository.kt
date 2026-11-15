package com.kaoyan.wordhelper.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaoyan.wordhelper.data.model.PronunciationSource
import com.kaoyan.wordhelper.data.network.CardBackAddress
import com.kaoyan.wordhelper.data.network.NetworkAvailability
import com.kaoyan.wordhelper.data.network.TlsPinned
import com.kaoyan.wordhelper.util.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val DEFAULT_NEW_WORDS_LIMIT = 20
private const val DEFAULT_FONT_SCALE = 1.0f
private const val DEFAULT_REVIEW_PRESSURE_RELIEF_ENABLED = true
private const val DEFAULT_REVIEW_PRESSURE_DAILY_CAP = 120
private const val DEFAULT_SWIPE_GESTURE_GUIDE_SHOWN = false
private const val DEFAULT_ALGORITHM_V4_ENABLED = false
private const val DEFAULT_PRONUNCIATION_ENABLED = false
private const val DEFAULT_PRONUNCIATION_SOURCE = 0
private const val DEFAULT_RECOGNITION_AUTO_PRONOUNCE_ENABLED = false
private const val DEFAULT_NEW_WORDS_SHUFFLE_ENABLED = false
private const val DEFAULT_ML_ADAPTIVE_ENABLED = false
private const val DEFAULT_PLANNED_NEW_WORDS_ENABLED = false
private const val DEFAULT_CARD_BACK_URL = ""
private const val DEFAULT_CARD_BACK_TOKEN = ""
private const val DEFAULT_CARD_BACK_LAST_GOOD_URL = ""
private const val DEFAULT_CARD_BACK_INSTANCE_ID = ""
// 1.4.7.2 W4：局域网 HTTPS 的 SPKI pin（64 位 hex，空 = 还没有可信锚点）。
// 只应由 HTTPS 配置地址的 /identity（tls_spki_sha256）或用户手填写入。
private const val DEFAULT_CARD_BACK_TLS_PIN = ""
private const val DEFAULT_CARD_BACK_TLS_PORT = 8443
// 同 WiFi 自动直连默认关闭：即使走 HTTPS + SPKI pin，也应由用户在设置页显式开启。
private const val DEFAULT_CARD_BACK_LAN_AUTO = false
private val Context.dataStore by preferencesDataStore(name = "user_settings")

data class UserSettings(
    val newWordsLimit: Int = DEFAULT_NEW_WORDS_LIMIT,
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val darkMode: DarkMode = DarkMode.FOLLOW_SYSTEM,
    val reviewPressureReliefEnabled: Boolean = DEFAULT_REVIEW_PRESSURE_RELIEF_ENABLED,
    val reviewPressureDailyCap: Int = DEFAULT_REVIEW_PRESSURE_DAILY_CAP,
    val swipeGestureGuideShown: Boolean = DEFAULT_SWIPE_GESTURE_GUIDE_SHOWN,
    val algorithmV4Enabled: Boolean = DEFAULT_ALGORITHM_V4_ENABLED,
    val pronunciationEnabled: Boolean = DEFAULT_PRONUNCIATION_ENABLED,
    val pronunciationSource: PronunciationSource = PronunciationSource.FREE_DICTIONARY,
    val recognitionAutoPronounceEnabled: Boolean = DEFAULT_RECOGNITION_AUTO_PRONOUNCE_ENABLED,
    val newWordsShuffleEnabled: Boolean = DEFAULT_NEW_WORDS_SHUFFLE_ENABLED,
    val mlAdaptiveEnabled: Boolean = DEFAULT_ML_ADAPTIVE_ENABLED,
    val plannedNewWordsEnabled: Boolean = DEFAULT_PLANNED_NEW_WORDS_ENABLED,
    /** 卡背结构地址；空 = 没配，刷新时提示去设置里填。 */
    val cardBackUrl: String = DEFAULT_CARD_BACK_URL,
    /** 电脑端启动时打印的口令；空 = 没填，非本机访问会被 401。 */
    val cardBackToken: String = DEFAULT_CARD_BACK_TOKEN,
    /**
     * 1.4.6.1：上次真正连通过的地址（已归一成 scheme://host:port）。
     * 换 Wi-Fi / 路由器重新分配 IP / 用户改错手填地址时，请求会先退回这里，
     * 不用再让用户去电脑上查一遍 IP。空 = 还没成功连过。
     */
    val cardBackLastGoodUrl: String = DEFAULT_CARD_BACK_LAST_GOOD_URL,
    /**
     * 1.4.7.2 W4：这台手机上次连的电脑服务身份（服务端 `/health` 的 instance_id）。
     * 同一台电脑换 LAN/域名只换 origin，不换这个值，所以游标可以共享；
     * 值变了说明换了电脑/重装了服务，旧 rev 会静默漏行，必须把游标归零。
     */
    val cardBackInstanceId: String = DEFAULT_CARD_BACK_INSTANCE_ID,
    /**
     * 1.4.7.2 W4：是否允许同 WiFi 自动发现并优先走局域网 HTTPS。
     * 默认关闭，必须由用户显式开启；没有 pin 时发现会直接拒绝，不会退回明文 HTTP。
     */
    val cardBackLanAuto: Boolean = DEFAULT_CARD_BACK_LAN_AUTO,
    /**
     * 1.4.7.2 W4：局域网 HTTPS 证书公钥 pin（SPKI SHA-256，64 位 hex）。
     * 只有它和 [cardBackInstanceId] 同时存在，才允许自动走 LAN HTTPS；
     * 空值 = 不能证明对端身份，绝不能退回明文 HTTP。
     */
    val cardBackTlsPin: String = DEFAULT_CARD_BACK_TLS_PIN,
    /** 局域网 HTTPS 端口；默认 8443，实际值由 HTTPS /identity 的 tls_port 更新。 */
    val cardBackTlsPort: Int = DEFAULT_CARD_BACK_TLS_PORT
)

data class TodayNewWordsPlan(
    val learningDate: String = "",
    val bookId: Long? = null,
    val wordIds: List<Long> = emptyList()
)

enum class DarkMode(val value: Int) {
    FOLLOW_SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int): DarkMode {
            return entries.firstOrNull { it.value == value } ?: FOLLOW_SYSTEM
        }
    }
}

class SettingsRepository(private val context: Context) {

    /** 局域网发现需要 Context；只暴露 applicationContext，避免把 Activity 漏进长生命周期对象。 */
    internal val appContext: Context
        get() = context.applicationContext

    /**
     * 1.4.7：用户改「地址/口令」时 +1。
     *
     * 两个仓库各自记着本次会话连通过的 activeOrigin（候选顺序第一）；
     * 看到这个版本变了就把它丢掉，否则改完地址还会先打旧地址——
     * 实测「改成 B、点重试，仍连 A 且显示已连接」。
     */
    @Volatile
    var endpointRevision: Long = 0L
        private set
    private val dataStore = context.dataStore

    val todayNewWordsPlanFlow: Flow<TodayNewWordsPlan> = dataStore.data.map { prefs ->
        val rawDate = prefs[KEY_TODAY_NEW_WORDS_PLAN_DATE].orEmpty()
        val rawBookId = prefs[KEY_TODAY_NEW_WORDS_PLAN_BOOK_ID].orEmpty()
        val rawWordIds = prefs[KEY_TODAY_NEW_WORDS_PLAN_WORD_IDS].orEmpty()
        val parsedWordIds = rawWordIds
            .split(',')
            .mapNotNull { token -> token.trim().toLongOrNull() }
            .distinct()
        TodayNewWordsPlan(
            learningDate = rawDate,
            bookId = rawBookId.toLongOrNull(),
            wordIds = parsedWordIds
        )
    }

    val settingsFlow: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            newWordsLimit = prefs[KEY_NEW_WORDS_LIMIT] ?: DEFAULT_NEW_WORDS_LIMIT,
            fontScale = prefs[KEY_FONT_SCALE] ?: DEFAULT_FONT_SCALE,
            darkMode = DarkMode.fromValue(prefs[KEY_DARK_MODE] ?: DarkMode.FOLLOW_SYSTEM.value),
            reviewPressureReliefEnabled = prefs[KEY_REVIEW_PRESSURE_RELIEF_ENABLED]
                ?: DEFAULT_REVIEW_PRESSURE_RELIEF_ENABLED,
            reviewPressureDailyCap = (prefs[KEY_REVIEW_PRESSURE_DAILY_CAP] ?: DEFAULT_REVIEW_PRESSURE_DAILY_CAP)
                .coerceIn(10, 500),
            swipeGestureGuideShown = prefs[KEY_SWIPE_GESTURE_GUIDE_SHOWN] ?: DEFAULT_SWIPE_GESTURE_GUIDE_SHOWN,
            algorithmV4Enabled = prefs[KEY_ALGORITHM_V4_ENABLED] ?: DEFAULT_ALGORITHM_V4_ENABLED,
            pronunciationEnabled = prefs[KEY_PRONUNCIATION_ENABLED] ?: DEFAULT_PRONUNCIATION_ENABLED,
            pronunciationSource = PronunciationSource.fromValue(
                prefs[KEY_PRONUNCIATION_SOURCE] ?: DEFAULT_PRONUNCIATION_SOURCE
            ),
            recognitionAutoPronounceEnabled =
                prefs[KEY_RECOGNITION_AUTO_PRONOUNCE_ENABLED]
                    ?: DEFAULT_RECOGNITION_AUTO_PRONOUNCE_ENABLED,
            newWordsShuffleEnabled =
                prefs[KEY_NEW_WORDS_SHUFFLE_ENABLED] ?: DEFAULT_NEW_WORDS_SHUFFLE_ENABLED,
            mlAdaptiveEnabled =
                prefs[KEY_ML_ADAPTIVE_ENABLED] ?: DEFAULT_ML_ADAPTIVE_ENABLED,
            plannedNewWordsEnabled =
                prefs[KEY_PLANNED_NEW_WORDS_ENABLED] ?: DEFAULT_PLANNED_NEW_WORDS_ENABLED,
            cardBackUrl = prefs[KEY_CARD_BACK_URL] ?: DEFAULT_CARD_BACK_URL,
            cardBackToken = prefs[KEY_CARD_BACK_TOKEN] ?: DEFAULT_CARD_BACK_TOKEN,
            cardBackLastGoodUrl = prefs[KEY_CARD_BACK_LAST_GOOD_URL] ?: DEFAULT_CARD_BACK_LAST_GOOD_URL,
            cardBackInstanceId = prefs[KEY_CARD_BACK_INSTANCE_ID] ?: DEFAULT_CARD_BACK_INSTANCE_ID,
            cardBackLanAuto = prefs[KEY_CARD_BACK_LAN_AUTO] ?: DEFAULT_CARD_BACK_LAN_AUTO,
            cardBackTlsPin = prefs[KEY_CARD_BACK_TLS_PIN] ?: DEFAULT_CARD_BACK_TLS_PIN,
            cardBackTlsPort = (prefs[KEY_CARD_BACK_TLS_PORT] ?: DEFAULT_CARD_BACK_TLS_PORT)
                .takeIf { it in 1..65535 } ?: DEFAULT_CARD_BACK_TLS_PORT
        )
    }

    /**
     * 网络可用性（1.4.6.1）。冷流，供 RewriteRepository 在网络恢复后补一次同步用；
     * 不做轮询，collect 结束就注销系统回调。
     */
    val networkAvailableFlow: Flow<Boolean> = NetworkAvailability.observe(context)

    suspend fun updateNewWordsLimit(limit: Int) {
        dataStore.edit { prefs -> prefs[KEY_NEW_WORDS_LIMIT] = limit.coerceIn(1, 500) }
    }

    suspend fun updateFontScale(scale: Float) {
        dataStore.edit { prefs -> prefs[KEY_FONT_SCALE] = scale.coerceIn(0.85f, 1.3f) }
    }

    suspend fun updateDarkMode(mode: DarkMode) {
        dataStore.edit { prefs -> prefs[KEY_DARK_MODE] = mode.value }
    }

    suspend fun updateReviewPressureReliefEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_REVIEW_PRESSURE_RELIEF_ENABLED] = enabled }
    }

    suspend fun updateReviewPressureDailyCap(cap: Int) {
        dataStore.edit { prefs -> prefs[KEY_REVIEW_PRESSURE_DAILY_CAP] = cap.coerceIn(10, 500) }
    }

    suspend fun updateSwipeGestureGuideShown(shown: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SWIPE_GESTURE_GUIDE_SHOWN] = shown }
    }

    suspend fun updateAlgorithmV4Enabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ALGORITHM_V4_ENABLED] = enabled }
    }

    suspend fun updatePronunciationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_PRONUNCIATION_ENABLED] = enabled }
    }

    suspend fun updatePronunciationSource(source: PronunciationSource) {
        dataStore.edit { prefs -> prefs[KEY_PRONUNCIATION_SOURCE] = source.value }
    }

    suspend fun updateRecognitionAutoPronounceEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_RECOGNITION_AUTO_PRONOUNCE_ENABLED] = enabled }
    }

    suspend fun updateNewWordsShuffleEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_NEW_WORDS_SHUFFLE_ENABLED] = enabled }
    }

    suspend fun updateMlAdaptiveEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ML_ADAPTIVE_ENABLED] = enabled }
    }

    suspend fun updatePlannedNewWordsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_PLANNED_NEW_WORDS_ENABLED] = enabled }
    }

    /**
     * 卡背结构地址写入。电脑端给的是一整行「地址#口令」（`#` 后面是口令）：
     * 有 `#` 就拆开 —— 地址进 card_back_url、口令覆盖 card_back_token；
     * 没有 `#` 只当地址存，**不动已有口令**（以前单独填过口令的人不能丢）。
     * 只在这里解析一次，三处读取路径（CardBack / Rewrite / Sync）保持原样。
     */
    suspend fun updateCardBackUrl(url: String) {
        val info = CardbackAuth.parsePairInfo(url)
        val parsedUrl = info.url
        val parsedToken = info.token
        dataStore.edit { prefs ->
            val oldUrl = prefs[KEY_CARD_BACK_URL].orEmpty()
            val oldToken = prefs[KEY_CARD_BACK_TOKEN] ?: DEFAULT_CARD_BACK_TOKEN
            val endpointChanged = endpointKey(oldUrl) != endpointKey(parsedUrl)
            prefs[KEY_CARD_BACK_URL] = parsedUrl
            if (parsedToken != null) prefs[KEY_CARD_BACK_TOKEN] = parsedToken
            // 清空地址 = 解除配对：last-good、旧口令和 TLS pin 都要一起清，否则 hasAddress()
            // 仍会为 true、后台 Job 继续跑，旧 token/pin 也可能被发给下一条地址。
            if (parsedUrl.isBlank()) {
                prefs.remove(KEY_CARD_BACK_LAST_GOOD_URL)
                prefs.remove(KEY_CARD_BACK_TOKEN)
                prefs.remove(KEY_CARD_BACK_TLS_PIN)
                prefs.remove(KEY_CARD_BACK_TLS_PORT)
            } else if (info.tlsPin != null) {
                // 电脑端那一行自带 pin：这是可信一次性配对输入，优先于旧 pin。
                prefs[KEY_CARD_BACK_TLS_PIN] = info.tlsPin
                prefs[KEY_CARD_BACK_TLS_PORT] = info.tlsPort ?: DEFAULT_CARD_BACK_TLS_PORT
            } else if (parsedToken != null && parsedToken != oldToken) {
                // 改口令 = 重新配对，旧 pin 不再可信；只改地址则保留 pin（同一实例可换 origin）。
                prefs.remove(KEY_CARD_BACK_TLS_PIN)
            }
            // 只有真正换主机或换口令才清身份：单纯重贴同一台电脑的配对行（例如补 pin）
            // 不能把「已配对」降级成「首配信任」，否则 /health 成功前 last-good 候选
            // 可能拿到 token。清空地址时上面已按解除配对处理。
            if (parsedUrl.isBlank() || endpointChanged || (parsedToken != null && parsedToken != oldToken)) {
                prefs[KEY_CARD_BACK_INSTANCE_ID] = ""
                // 换主机/换口令就是换电脑：last-good 也是旧电脑，不能留在候选里，
                // 否则新 token 可能先被发给旧地址（W4 P1-2）。
                if (parsedUrl.isNotBlank()) prefs.remove(KEY_CARD_BACK_LAST_GOOD_URL)
            }
        }
        endpointRevision++
    }

    /** 只比 scheme://host:port；路径（/cardback.json）变化不代表换电脑。 */
    private fun endpointKey(raw: String): String {
        val text = raw.trim().trimEnd('/')
        if (text.isBlank()) return ""
        return runCatching {
            val uri = java.net.URI(text)
            val host = uri.host ?: return@runCatching text
            val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
            "${uri.scheme}://${host.lowercase()}:$port"
        }.getOrDefault(text)
    }

    suspend fun updateCardBackToken(token: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CARD_BACK_TOKEN] = token.trim()
            // 1.4.7.2 W4：口令换了，旧 pin 对应的配对关系作废；等 HTTPS /identity 重新取锚点。
            prefs.remove(KEY_CARD_BACK_TLS_PIN)
            prefs[KEY_CARD_BACK_INSTANCE_ID] = ""
            // 新口令不能再发给旧电脑的 last-good 地址。
            prefs.remove(KEY_CARD_BACK_LAST_GOOD_URL)
        }
        endpointRevision++
    }

    /**
     * 1.4.7.2 W4：写入局域网 TLS 锚点。
     *
     * pin 非法时清空（绝不保留一个无法校验的值）；端口越界回退 8443。
     * 只应由 [LanDiscovery.fetchAnchor] 的 HTTPS 结果或用户手填写入，不能来自 UDP/明文 HTTP。
     */
    suspend fun updateCardBackTlsAnchor(pin: String, port: Int) {
        val normalized = TlsPinned.normalizePin(pin)
        val safePort = port.takeIf { it in 1..65535 } ?: DEFAULT_CARD_BACK_TLS_PORT
        dataStore.edit { prefs ->
            if (normalized == null) {
                prefs.remove(KEY_CARD_BACK_TLS_PIN)
                prefs.remove(KEY_CARD_BACK_TLS_PORT)
            } else {
                prefs[KEY_CARD_BACK_TLS_PIN] = normalized
                prefs[KEY_CARD_BACK_TLS_PORT] = safePort
            }
        }
    }

    /** 记住当前电脑的服务身份；instance 变了说明换了电脑/重装，旧 pin 必须作废。 */
    suspend fun updateCardBackInstanceId(instanceId: String) {
        dataStore.edit { prefs ->
            val old = prefs[KEY_CARD_BACK_INSTANCE_ID]?.trim().orEmpty()
            val next = instanceId.trim()
            prefs[KEY_CARD_BACK_INSTANCE_ID] = next
            if (old.isNotBlank() && old != next) prefs.remove(KEY_CARD_BACK_TLS_PIN)
        }
    }

    /**
     * 1.4.7.2 W4-B：同 WiFi 自动直连开关。
     * 默认关闭；开启后设置页会显示「仅可信家庭 WiFi」的风险提示。
     */
    suspend fun updateCardBackLanAuto(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_CARD_BACK_LAN_AUTO] = enabled }
        endpointRevision++
    }

    /**
     * 记住「上次真正连通的地址」（1.4.6.1）。只在请求成功后调用。
     * 先归一成 scheme://host:port 再存；解析不出 host 就忽略 ——
     * 不能因为一次脏输入把原来能用的兜底地址覆盖掉。
     */
    suspend fun updateCardBackLastGoodUrl(url: String) {
        val origin = CardBackAddress.originOf(url) ?: return
        dataStore.edit { prefs -> prefs[KEY_CARD_BACK_LAST_GOOD_URL] = origin }
    }

    /** 已导入的内置种子词库版本号，用于判断要不要重导内容。 */
    suspend fun cardSeedVersion(): Int {
        return dataStore.data.map { prefs -> prefs[KEY_CARD_SEED_VERSION] ?: 0 }.first()
    }

    suspend fun updateCardSeedVersion(version: Int) {
        dataStore.edit { prefs -> prefs[KEY_CARD_SEED_VERSION] = version }
    }

    /** 同一结果流内 seq 才可比较。两项一起读写，避免重启后混配。 */
    internal suspend fun rewriteResultCursor(): RewriteResultCursor {
        val prefs = dataStore.data.first()
        return RewriteResultCursor(prefs[KEY_REWRITE_LAST_SEQ] ?: 0L, prefs[KEY_REWRITE_STREAM_ID])
    }

    internal suspend fun updateRewriteResultCursor(cursor: RewriteResultCursor) {
        dataStore.edit { prefs ->
            prefs[KEY_REWRITE_LAST_SEQ] = cursor.seq
            val streamId = cursor.streamId
            if (streamId == null) prefs.remove(KEY_REWRITE_STREAM_ID)
            else prefs[KEY_REWRITE_STREAM_ID] = streamId
        }
    }

    /** 老服务端（无 streamId）游标归零的「从哪个 seq 归零过」标记，防止同一位置反复重放。 */
    internal suspend fun rewriteLegacyRewoundSeq(): Long {
        return dataStore.data.first()[KEY_REWRITE_LEGACY_REWOUND_SEQ] ?: 0L
    }

    internal suspend fun updateRewriteLegacyRewoundSeq(seq: Long) {
        dataStore.edit { prefs -> prefs[KEY_REWRITE_LEGACY_REWOUND_SEQ] = seq }
    }

    /** 上次选的模型 id，空 = 还没选过。 */
    suspend fun rewriteModel(): String {
        return dataStore.data.map { prefs -> prefs[KEY_REWRITE_MODEL].orEmpty() }.first()
    }

    suspend fun updateRewriteModel(modelId: String) {
        dataStore.edit { prefs -> prefs[KEY_REWRITE_MODEL] = modelId.trim() }
    }

    /** 1.4.3.2 的一次性学习记录清理（清过一次就不再清，免得以后正常背词也被清）。 */
    suspend fun cleanup132Done(): Boolean {
        return dataStore.data.map { prefs -> prefs[KEY_CLEANUP_132_DONE] ?: false }.first()
    }

    suspend fun markCleanup132Done() {
        dataStore.edit { prefs -> prefs[KEY_CLEANUP_132_DONE] = true }
    }

    /** 20 词测试书导入过的版本号；导入过一次就不再自动建，用户删了也不会复活。 */
    suspend fun testDeckVersion(): Int {
        return dataStore.data.map { prefs -> prefs[KEY_TEST_DECK_VERSION] ?: 0 }.first()
    }

    suspend fun updateTestDeckVersion(version: Int) {
        dataStore.edit { prefs -> prefs[KEY_TEST_DECK_VERSION] = version }
    }

    /** 1.4.4：方法标签回填只做一次（升级后第一次启动把老卡片的 method 补齐）。 */
    suspend fun methodBackfillDone(): Boolean {
        return dataStore.data.map { prefs -> prefs[KEY_METHOD_BACKFILL_DONE] ?: false }.first()
    }

    suspend fun markMethodBackfillDone() {
        dataStore.edit { prefs -> prefs[KEY_METHOD_BACKFILL_DONE] = true }
    }

    /**
     * 是否显示两本内置大词库（「完整词库」「完整词库（精选）」）。
     * 默认关：这两本各 5000+ 词、每次启动都要从 assets 重导，日常背词用不到。
     * 只是「不显示/不导入」，数据库里的旧书不删，用户随时能开回来。
     */
    val showBuiltinFullBooks: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_BUILTIN_FULL_BOOKS] ?: false
    }

    suspend fun updateShowBuiltinFullBooks(show: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SHOW_BUILTIN_FULL_BOOKS] = show }
    }

    suspend fun saveTodayNewWordsPlan(bookId: Long, wordIds: List<Long>) {
        val learningDate = DateUtils.currentLearningDate().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val normalizedWordIds = wordIds.distinct().joinToString(separator = ",")
        dataStore.edit { prefs ->
            prefs[KEY_TODAY_NEW_WORDS_PLAN_DATE] = learningDate
            prefs[KEY_TODAY_NEW_WORDS_PLAN_BOOK_ID] = bookId.toString()
            prefs[KEY_TODAY_NEW_WORDS_PLAN_WORD_IDS] = normalizedWordIds
        }
    }

    suspend fun clearTodayNewWordsPlan() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TODAY_NEW_WORDS_PLAN_DATE)
            prefs.remove(KEY_TODAY_NEW_WORDS_PLAN_BOOK_ID)
            prefs.remove(KEY_TODAY_NEW_WORDS_PLAN_WORD_IDS)
        }
    }

    companion object {

        private val KEY_NEW_WORDS_LIMIT = intPreferencesKey("new_words_limit")
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        private val KEY_DARK_MODE = intPreferencesKey("dark_mode")
        private val KEY_REVIEW_PRESSURE_RELIEF_ENABLED =
            booleanPreferencesKey("review_pressure_relief_enabled")
        private val KEY_REVIEW_PRESSURE_DAILY_CAP = intPreferencesKey("review_pressure_daily_cap")
        private val KEY_SWIPE_GESTURE_GUIDE_SHOWN = booleanPreferencesKey("swipe_gesture_guide_shown")
        private val KEY_ALGORITHM_V4_ENABLED = booleanPreferencesKey("algorithm_v4_enabled")
        private val KEY_PRONUNCIATION_ENABLED = booleanPreferencesKey("pronunciation_enabled")
        private val KEY_PRONUNCIATION_SOURCE = intPreferencesKey("pronunciation_source")
        private val KEY_RECOGNITION_AUTO_PRONOUNCE_ENABLED =
            booleanPreferencesKey("recognition_auto_pronounce_enabled")
        private val KEY_NEW_WORDS_SHUFFLE_ENABLED = booleanPreferencesKey("new_words_shuffle_enabled")
        private val KEY_ML_ADAPTIVE_ENABLED = booleanPreferencesKey("ml_adaptive_enabled")
        private val KEY_PLANNED_NEW_WORDS_ENABLED = booleanPreferencesKey("planned_new_words_enabled")
        private val KEY_CARD_BACK_URL = stringPreferencesKey("card_back_url")
        private val KEY_CARD_BACK_TOKEN = stringPreferencesKey("card_back_token")
        private val KEY_CARD_BACK_LAST_GOOD_URL = stringPreferencesKey("card_back_last_good_url")
        private val KEY_CARD_BACK_INSTANCE_ID = stringPreferencesKey("card_back_instance_id")
        private val KEY_CARD_BACK_LAN_AUTO = booleanPreferencesKey("card_back_lan_auto")
        private val KEY_CARD_BACK_TLS_PIN = stringPreferencesKey("card_back_tls_pin")
        private val KEY_CARD_BACK_TLS_PORT = intPreferencesKey("card_back_tls_port")
        private val KEY_CARD_SEED_VERSION = intPreferencesKey("card_seed_version")
        private val KEY_REWRITE_LAST_SEQ = longPreferencesKey("rewrite_last_seq")
        private val KEY_REWRITE_STREAM_ID = stringPreferencesKey("rewrite_stream_id")
        private val KEY_REWRITE_LEGACY_REWOUND_SEQ = longPreferencesKey("rewrite_legacy_rewound_seq")
        private val KEY_REWRITE_MODEL = stringPreferencesKey("rewrite_model")
        private val KEY_CLEANUP_132_DONE = booleanPreferencesKey("cleanup_132_done")
        private val KEY_TEST_DECK_VERSION = intPreferencesKey("test_deck_version")
        private val KEY_METHOD_BACKFILL_DONE = booleanPreferencesKey("method_backfill_done")
        private val KEY_SHOW_BUILTIN_FULL_BOOKS = booleanPreferencesKey("show_builtin_full_books")
        private val KEY_TODAY_NEW_WORDS_PLAN_DATE = stringPreferencesKey("today_new_words_plan_date")
        private val KEY_TODAY_NEW_WORDS_PLAN_BOOK_ID = stringPreferencesKey("today_new_words_plan_book_id")
        private val KEY_TODAY_NEW_WORDS_PLAN_WORD_IDS = stringPreferencesKey("today_new_words_plan_word_ids")
    }
}
