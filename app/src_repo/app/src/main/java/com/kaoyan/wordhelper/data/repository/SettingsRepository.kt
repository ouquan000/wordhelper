package com.kaoyan.wordhelper.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaoyan.wordhelper.BuildConfig
import com.kaoyan.wordhelper.data.model.CardMotionMode
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
private const val DEFAULT_ALGORITHM_V4_ENABLED = false
// v8：FSRS-5 记忆调度默认开启；目标留存率默认 0.90（日常档）。
private const val DEFAULT_FSRS_ENABLED = true
private const val DEFAULT_DESIRED_RETENTION = 0.90f
private const val DEFAULT_PRONUNCIATION_ENABLED = false
private const val DEFAULT_PRONUNCIATION_SOURCE = 0
private const val DEFAULT_RECOGNITION_AUTO_PRONOUNCE_ENABLED = false
private const val DEFAULT_NEW_WORDS_SHUFFLE_ENABLED = false
private const val DEFAULT_ML_ADAPTIVE_ENABLED = false
private const val DEFAULT_PLANNED_NEW_WORDS_ENABLED = false
private const val DEFAULT_CARD_STYLE_ID = "random"
private const val DEFAULT_CARD_GLOSS_HIGH = true
// 1.4.7.4：学习页背景氛围；auto 跟随卡片材质，其余为显式锁定的独立氛围。
private const val DEFAULT_AMBIENT_BACKDROP_ID = "auto"
private val AMBIENT_BACKDROP_IDS = setOf("auto", "aurora", "abyss", "ember", "velvet")
// 1.4.7.4：三大主页默认全局全屏沉浸（隐藏系统状态栏），可由「我的 → 设置」显式关闭。
private const val DEFAULT_IMMERSIVE_FULLSCREEN_ENABLED = true
private val CARD_STYLE_IDS = setOf("random", "classic", "prism", "rainbow", "pearl", "sparkle", "chrome", "etched")
private const val DEFAULT_CARD_BACK_URL = ""
private const val DEFAULT_CARD_BACK_TOKEN = ""
private const val DEFAULT_CARD_BACK_LAST_GOOD_URL = ""
private const val DEFAULT_CARD_BACK_INSTANCE_ID = ""
// 1.4.7.2 W4：局域网 HTTPS 的 SPKI pin（64 位 hex，空 = 还没有可信锚点）。
// 只应由 HTTPS 配置地址的 /identity（tls_spki_sha256）或用户手填写入。
private const val DEFAULT_CARD_BACK_TLS_PIN = ""
private const val DEFAULT_CARD_BACK_TLS_PORT = 8443
// 1.4.8：同 WiFi 自动直连默认开启。resolveLan 仍要求 instanceId + token + pin，
// 未配对（缺 instanceId/pin）不会自动发现，也不会退回明文 HTTP。
private const val DEFAULT_CARD_BACK_LAN_AUTO = true
// 1.5.0：词卡热更的可选「公网/远程」地址（Cloudflare Tunnel 的 https 域名）。
// 空 = 只走局域网；填了就和局域网地址共存，局域网 2 秒连不上时自动切换过去。
// 1.5.1：仓库默认值恒为空串；真正的产品默认值来自本地 keystore.properties 注入的
// BuildConfig.DEFAULT_REMOTE_URL，经 RemoteUrlPolicy.resolve 生效 —— 私人域名不入库。
private const val DEFAULT_WORDBOOK_REMOTE_URL = ""
private const val DEFAULT_WORDBOOK_APPLIED_SHA = ""
private const val DEFAULT_WORDBOOK_LAST_SYNC_AT = 0L
private const val DEFAULT_WORDBOOK_LAST_WORD_COUNT = 0
private val Context.dataStore by preferencesDataStore(name = "user_settings")

data class UserSettings(
    val newWordsLimit: Int = DEFAULT_NEW_WORDS_LIMIT,
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val darkMode: DarkMode = DarkMode.FOLLOW_SYSTEM,
    /** 日夜双模式总开关：true = 夜间，false = 日间（默认浅色，不跟随系统）。 */
    val darkThemeEnabled: Boolean = false,
    val reviewPressureReliefEnabled: Boolean = DEFAULT_REVIEW_PRESSURE_RELIEF_ENABLED,
    val reviewPressureDailyCap: Int = DEFAULT_REVIEW_PRESSURE_DAILY_CAP,
    val algorithmV4Enabled: Boolean = DEFAULT_ALGORITHM_V4_ENABLED,
    /** v8：FSRS-5 记忆调度总开关；关闭时回落到旧的 SM-2 + ML 分支。 */
    val fsrsEnabled: Boolean = DEFAULT_FSRS_ENABLED,
    /** v8：目标留存率，越大复习越密；读写都夹在 0.80..0.97。 */
    val desiredRetention: Float = DEFAULT_DESIRED_RETENTION,
    val pronunciationEnabled: Boolean = DEFAULT_PRONUNCIATION_ENABLED,
    val pronunciationSource: PronunciationSource = PronunciationSource.FREE_DICTIONARY,
    val recognitionAutoPronounceEnabled: Boolean = DEFAULT_RECOGNITION_AUTO_PRONOUNCE_ENABLED,
    val newWordsShuffleEnabled: Boolean = DEFAULT_NEW_WORDS_SHUFFLE_ENABLED,
    val mlAdaptiveEnabled: Boolean = DEFAULT_ML_ADAPTIVE_ENABLED,
    val plannedNewWordsEnabled: Boolean = DEFAULT_PLANNED_NEW_WORDS_ENABLED,
    /** 学习主卡片材质，random 表示每次展示独立选取。 */
    val cardStyleId: String = DEFAULT_CARD_STYLE_ID,
    /** true = 璀璨炫光（1.0x），false = 柔和护眼（0.55x）。 */
    val cardGlossHigh: Boolean = DEFAULT_CARD_GLOSS_HIGH,
    val cardMotionMode: CardMotionMode = CardMotionMode.FULL,
    /** 1.4.7.4：三大主页全局全屏沉浸（隐藏系统状态栏）；false = 恢复系统状态栏。 */
    val immersiveFullscreenEnabled: Boolean = DEFAULT_IMMERSIVE_FULLSCREEN_ENABLED,
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
     * 1.4.8 起默认开启；但只有 instanceId + pin 都在时才会真正发现，
     * 未配对不会自动发现，没有 pin 时直接拒绝，不会退回明文 HTTP。
     */
    val cardBackLanAuto: Boolean = DEFAULT_CARD_BACK_LAN_AUTO,
    /**
     * 1.4.7.2 W4：局域网 HTTPS 证书公钥 pin（SPKI SHA-256，64 位 hex）。
     * 只有它和 [cardBackInstanceId] 同时存在，才允许自动走 LAN HTTPS；
     * 空值 = 不能证明对端身份，绝不能退回明文 HTTP。
     */
    val cardBackTlsPin: String = DEFAULT_CARD_BACK_TLS_PIN,
    /** 局域网 HTTPS 端口；默认 8443，实际值由 HTTPS /identity 的 tls_port 更新。 */
    val cardBackTlsPort: Int = DEFAULT_CARD_BACK_TLS_PORT,
    /** 1.5.0：词卡热更的公网/远程地址；空 = 只用局域网地址（cardBackUrl）。 */
    val wordbookRemoteUrl: String = DEFAULT_WORDBOOK_REMOTE_URL,
    /** 1.5.0：本地已应用的词卡包 sha256；与 manifest 一致时提示「已是最新」。 */
    val wordbookAppliedSha: String = DEFAULT_WORDBOOK_APPLIED_SHA,
    /** 1.5.0：上次成功热更的时间（毫秒）。 */
    val wordbookLastSyncAt: Long = DEFAULT_WORDBOOK_LAST_SYNC_AT,
    /** 1.5.0：上次热更覆盖的词条数，用于「我的」页状态行。 */
    val wordbookLastWordCount: Int = DEFAULT_WORDBOOK_LAST_WORD_COUNT,
    /** 1.4.7.4：学习页背景氛围 id：auto / aurora / abyss / ember / velvet。 */
    val ambientBackdropId: String = DEFAULT_AMBIENT_BACKDROP_ID
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
            darkThemeEnabled = prefs[KEY_DARK_THEME_ENABLED] ?: false,
            reviewPressureReliefEnabled = prefs[KEY_REVIEW_PRESSURE_RELIEF_ENABLED]
                ?: DEFAULT_REVIEW_PRESSURE_RELIEF_ENABLED,
            reviewPressureDailyCap = (prefs[KEY_REVIEW_PRESSURE_DAILY_CAP] ?: DEFAULT_REVIEW_PRESSURE_DAILY_CAP)
                .coerceIn(10, 500),
            algorithmV4Enabled = prefs[KEY_ALGORITHM_V4_ENABLED] ?: DEFAULT_ALGORITHM_V4_ENABLED,
            fsrsEnabled = prefs[KEY_FSRS_ENABLED] ?: DEFAULT_FSRS_ENABLED,
            desiredRetention = (prefs[KEY_DESIRED_RETENTION] ?: DEFAULT_DESIRED_RETENTION)
                .coerceIn(0.80f, 0.97f),
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
            cardStyleId = (prefs[KEY_CARD_STYLE_ID] ?: DEFAULT_CARD_STYLE_ID)
                .takeIf { it in CARD_STYLE_IDS } ?: DEFAULT_CARD_STYLE_ID,
            cardGlossHigh = prefs[KEY_CARD_GLOSS_HIGH] ?: DEFAULT_CARD_GLOSS_HIGH,
            cardMotionMode = prefs[KEY_CARD_MOTION_MODE]?.let(CardMotionMode::fromId)
                ?: if (prefs[KEY_CARD_MOTION_ENABLED] == false) CardMotionMode.STATIC else CardMotionMode.FULL,
            immersiveFullscreenEnabled =
                prefs[KEY_IMMERSIVE_FULLSCREEN_ENABLED] ?: DEFAULT_IMMERSIVE_FULLSCREEN_ENABLED,
            cardBackUrl = prefs[KEY_CARD_BACK_URL] ?: DEFAULT_CARD_BACK_URL,
            cardBackToken = prefs[KEY_CARD_BACK_TOKEN] ?: DEFAULT_CARD_BACK_TOKEN,
            cardBackLastGoodUrl = prefs[KEY_CARD_BACK_LAST_GOOD_URL] ?: DEFAULT_CARD_BACK_LAST_GOOD_URL,
            cardBackInstanceId = prefs[KEY_CARD_BACK_INSTANCE_ID] ?: DEFAULT_CARD_BACK_INSTANCE_ID,
            cardBackLanAuto = prefs[KEY_CARD_BACK_LAN_AUTO] ?: DEFAULT_CARD_BACK_LAN_AUTO,
            cardBackTlsPin = prefs[KEY_CARD_BACK_TLS_PIN] ?: DEFAULT_CARD_BACK_TLS_PIN,
            cardBackTlsPort = (prefs[KEY_CARD_BACK_TLS_PORT] ?: DEFAULT_CARD_BACK_TLS_PORT)
                .takeIf { it in 1..65535 } ?: DEFAULT_CARD_BACK_TLS_PORT,
            // 1.5.1：默认公网地址来自本地 keystore.properties 注入的 BuildConfig.DEFAULT_REMOTE_URL。
            // 只有「从没存过」才回落默认；存过空串 = 用户主动清空，不能再被填回来。
            wordbookRemoteUrl = RemoteUrlPolicy.resolve(
                prefs[KEY_WORDBOOK_REMOTE_URL],
                BuildConfig.DEFAULT_REMOTE_URL
            ),
            wordbookAppliedSha = prefs[KEY_WORDBOOK_APPLIED_SHA] ?: DEFAULT_WORDBOOK_APPLIED_SHA,
            wordbookLastSyncAt = prefs[KEY_WORDBOOK_LAST_SYNC_AT] ?: DEFAULT_WORDBOOK_LAST_SYNC_AT,
            wordbookLastWordCount = prefs[KEY_WORDBOOK_LAST_WORD_COUNT] ?: DEFAULT_WORDBOOK_LAST_WORD_COUNT,
            ambientBackdropId = (prefs[KEY_AMBIENT_BACKDROP_ID] ?: DEFAULT_AMBIENT_BACKDROP_ID)
                .takeIf { it in AMBIENT_BACKDROP_IDS } ?: DEFAULT_AMBIENT_BACKDROP_ID
        )
    }

    /**
     * 网络可用性（1.4.6.1）。冷流，供 RewriteRepository 在网络恢复后补一次同步用；
     * 不做轮询，collect 结束就注销系统回调。
     */
    val connectedNetworkFlow = NetworkAvailability.observe(context)

    suspend fun updateNewWordsLimit(limit: Int) {
        dataStore.edit { prefs -> prefs[KEY_NEW_WORDS_LIMIT] = limit.coerceIn(1, 500) }
    }

    suspend fun updateFontScale(scale: Float) {
        dataStore.edit { prefs -> prefs[KEY_FONT_SCALE] = scale.coerceIn(0.85f, 1.3f) }
    }

    suspend fun updateDarkMode(mode: DarkMode) {
        dataStore.edit { prefs -> prefs[KEY_DARK_MODE] = mode.value }
    }

    /** 日夜双模式开关：写入新键，旧 darkMode 键保持不动以兼容历史设置。 */
    suspend fun updateDarkThemeEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DARK_THEME_ENABLED] = enabled }
    }

    suspend fun updateReviewPressureReliefEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_REVIEW_PRESSURE_RELIEF_ENABLED] = enabled }
    }

    suspend fun updateReviewPressureDailyCap(cap: Int) {
        dataStore.edit { prefs -> prefs[KEY_REVIEW_PRESSURE_DAILY_CAP] = cap.coerceIn(10, 500) }
    }

    suspend fun updateAlgorithmV4Enabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ALGORITHM_V4_ENABLED] = enabled }
    }

    /** v8：FSRS-5 开关；关掉后学习结果回到旧的 SM-2 + ML 调度。 */
    suspend fun updateFsrsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_FSRS_ENABLED] = enabled }
    }

    /** v8：目标留存率，越高考复习越密（0.80..0.97）。 */
    suspend fun updateDesiredRetention(value: Float) {
        dataStore.edit { prefs -> prefs[KEY_DESIRED_RETENTION] = value.coerceIn(0.80f, 0.97f) }
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

    /** 卡片材质写入；未知 id 落回 pearl，避免脏数据把卡片画成空白。 */
    suspend fun updateCardStyleId(styleId: String) {
        val safe = styleId.takeIf { it in CARD_STYLE_IDS } ?: DEFAULT_CARD_STYLE_ID
        dataStore.edit { prefs -> prefs[KEY_CARD_STYLE_ID] = safe }
    }

    suspend fun updateCardGlossHigh(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_CARD_GLOSS_HIGH] = enabled }
    }

    suspend fun updateCardMotionMode(mode: CardMotionMode) {
        dataStore.edit { prefs -> prefs[KEY_CARD_MOTION_MODE] = mode.id }
    }

    /** 背景氛围写入；未知 id 落回 auto（跟随卡片），不让脏数据把背景画空。 */
    suspend fun updateAmbientBackdropId(id: String) {
        val safe = id.takeIf { it in AMBIENT_BACKDROP_IDS } ?: DEFAULT_AMBIENT_BACKDROP_ID
        dataStore.edit { prefs -> prefs[KEY_AMBIENT_BACKDROP_ID] = safe }
    }

    /** 1.4.7.4：全局全屏沉浸开关，实时写回；关闭后根布局立即恢复系统状态栏。 */
    suspend fun updateImmersiveFullscreenEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_IMMERSIVE_FULLSCREEN_ENABLED] = enabled }
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
     * 1.4.8 起默认开启；未配对时即使开着也不会自动发现，设置页仍保留「仅可信家庭 WiFi」的提示。
     */
    suspend fun updateCardBackLanAuto(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_CARD_BACK_LAN_AUTO] = enabled }
        endpointRevision++
    }

    /**
     * 1.5.0：词卡热更的公网/远程地址。
     *
     * 只做「能用的 URL」归一：补 https://、去掉末尾斜杠和误贴的 cards 目录下 json 路径
     * （规则统一收在 [RemoteUrlPolicy.normalizeBase]，纯 Kotlin 可单测）。
     *
     * 1.5.1：**空串也要写入**，不再 remove key。以前 remove 后读回来是 null，
     * 会被「默认公网地址」重新填回，用户清空等于没清空；现在空串本身就是
     * 「用户主动关掉公网地址」的显式标记（见 [RemoteUrlPolicy.resolve]）。
     */
    suspend fun updateWordbookRemoteUrl(url: String) {
        val normalized = normalizeWordbookRemoteUrl(url)
        dataStore.edit { prefs -> prefs[KEY_WORDBOOK_REMOTE_URL] = normalized }
        endpointRevision++
    }

    /** 1.5.0：词卡包应用成功后落盘，供「已是最新」判断和状态行展示。 */
    suspend fun updateWordbookApplied(sha256: String, syncedAt: Long, wordCount: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_WORDBOOK_APPLIED_SHA] = sha256.trim().lowercase()
            prefs[KEY_WORDBOOK_LAST_SYNC_AT] = syncedAt
            prefs[KEY_WORDBOOK_LAST_WORD_COUNT] = wordCount.coerceAtLeast(0)
        }
    }

    /**
     * 远程地址归一：裸域名补 https://；误贴 /cards/manifest.json 时砍回站点根。
     * 1.5.1 起规则统一收到 [RemoteUrlPolicy.normalizeBase]（纯 Kotlin、JVM 可直接单测），
     * 这里只保留原方法名给本类调用方用。
     */
    private fun normalizeWordbookRemoteUrl(raw: String): String =
        RemoteUrlPolicy.normalizeBase(raw)

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
        private val KEY_DARK_THEME_ENABLED = booleanPreferencesKey("dark_theme_enabled")
        private val KEY_REVIEW_PRESSURE_RELIEF_ENABLED =
            booleanPreferencesKey("review_pressure_relief_enabled")
        private val KEY_REVIEW_PRESSURE_DAILY_CAP = intPreferencesKey("review_pressure_daily_cap")
        private val KEY_ALGORITHM_V4_ENABLED = booleanPreferencesKey("algorithm_v4_enabled")
        private val KEY_FSRS_ENABLED = booleanPreferencesKey("fsrs_enabled")
        private val KEY_DESIRED_RETENTION = floatPreferencesKey("desired_retention")
        private val KEY_PRONUNCIATION_ENABLED = booleanPreferencesKey("pronunciation_enabled")
        private val KEY_PRONUNCIATION_SOURCE = intPreferencesKey("pronunciation_source")
        private val KEY_RECOGNITION_AUTO_PRONOUNCE_ENABLED =
            booleanPreferencesKey("recognition_auto_pronounce_enabled")
        private val KEY_NEW_WORDS_SHUFFLE_ENABLED = booleanPreferencesKey("new_words_shuffle_enabled")
        private val KEY_ML_ADAPTIVE_ENABLED = booleanPreferencesKey("ml_adaptive_enabled")
        private val KEY_PLANNED_NEW_WORDS_ENABLED = booleanPreferencesKey("planned_new_words_enabled")
        private val KEY_CARD_STYLE_ID = stringPreferencesKey("card_style_id")
        private val KEY_CARD_GLOSS_HIGH = booleanPreferencesKey("card_gloss_high")
        private val KEY_CARD_MOTION_MODE = stringPreferencesKey("card_motion_mode")
        private val KEY_CARD_MOTION_ENABLED = booleanPreferencesKey("card_motion_enabled")
        private val KEY_AMBIENT_BACKDROP_ID = stringPreferencesKey("ambient_backdrop_id")
        private val KEY_IMMERSIVE_FULLSCREEN_ENABLED =
            booleanPreferencesKey("immersive_fullscreen_enabled")
        private val KEY_CARD_BACK_URL = stringPreferencesKey("card_back_url")
        private val KEY_CARD_BACK_TOKEN = stringPreferencesKey("card_back_token")
        private val KEY_CARD_BACK_LAST_GOOD_URL = stringPreferencesKey("card_back_last_good_url")
        private val KEY_CARD_BACK_INSTANCE_ID = stringPreferencesKey("card_back_instance_id")
        private val KEY_CARD_BACK_LAN_AUTO = booleanPreferencesKey("card_back_lan_auto")
        private val KEY_CARD_BACK_TLS_PIN = stringPreferencesKey("card_back_tls_pin")
        private val KEY_CARD_BACK_TLS_PORT = intPreferencesKey("card_back_tls_port")
        private val KEY_WORDBOOK_REMOTE_URL = stringPreferencesKey("wordbook_remote_url")
        private val KEY_WORDBOOK_APPLIED_SHA = stringPreferencesKey("wordbook_applied_sha")
        private val KEY_WORDBOOK_LAST_SYNC_AT = longPreferencesKey("wordbook_last_sync_at")
        private val KEY_WORDBOOK_LAST_WORD_COUNT = intPreferencesKey("wordbook_last_word_count")
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
