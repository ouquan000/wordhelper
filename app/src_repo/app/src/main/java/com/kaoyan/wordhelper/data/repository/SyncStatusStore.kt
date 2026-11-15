package com.kaoyan.wordhelper.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 同步现在走到哪一步了。
 *
 * 1.4.7 的状态页与「我的」入口共用这一套词，不再各自拼字符串：
 * 已连接 / 连接中 / 重试中 / 失败 / 需要口令 / 被限流 / 电脑端版本过旧。
 */
enum class SyncPhase {
    /** 还没试过（刚冷启动、或用户清过状态）。 */
    IDLE,

    /** 正在发请求。 */
    CONNECTING,

    /** 最近一次同步成功。 */
    OK,

    /** 上次失败，正在按退避等下一次。 */
    RETRYING,

    /** 连不上 / 其他非鉴权、非限流的失败。 */
    FAILED,

    /** 401：口令不对，需要重新粘贴连接串。 */
    UNAUTHORIZED,

    /** 429：被限流，要等窗口过去。 */
    RATE_LIMITED,

    /** 404 或版本号对不上：电脑端还是旧进程，得重启/升级。 */
    SERVER_TOO_OLD
}

/**
 * 一次同步「对外可见」的全部事实。
 *
 * 只放能直接显示给用户的东西：时间戳、HTTP 码、一句人话、队列长度、电脑端自报的版本与能力。
 * 不存 token；地址只存 origin（scheme://host:port），状态页和诊断文本都不带口令。
 */
data class SyncStatusSnapshot(
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastHttpCode: Int? = null,
    val lastMessage: String? = null,
    /** 最近一次成功请求实际命中的 origin（不含口令）。手填地址失败回退到上次成功时，这里显示回退后的地址。 */
    val lastOrigin: String? = null,
    val pendingCount: Int = 0,
    val retryAtEpochMs: Long? = null,
    val serverVersion: String? = null,
    val serverStartedAt: String? = null,
    val capabilities: Set<String> = emptySet(),
    /** 1.4.7.2 W5：服务端恢复代次（老服务端没有这个字段时保持 1）。 */
    val syncEpoch: Long = 1,
    /** 最近一次 push 回执里的真实冲突数（普通幂等重放不算）。 */
    val lastConflictCount: Int = 0,
    /** 最近一次 push 回执里被容量清理的行数。 */
    val lastTrimmedCount: Int = 0,
    /** 最近一次 push 回执里被服务端确认的幂等重放数。 */
    val lastReplayCount: Int = 0
)

/**
 * 同步状态的单一事实来源（进程内，不落盘、不进数据库）。
 *
 * 谁真的发了请求，谁就往这里记一笔（[report]）；队列长度由界面或同步入口用 [reportPending] 刷；
 * 电脑端 `/health` 自报的版本/能力用 [reportServer]。状态页只订阅 [state]，不自己发请求。
 *
 * 冷启动后是空的（[SyncPhase.IDLE]）：上一轮的失败原因不值得跨进程保留，
 * 免得用户开局就看到一条早就过时的「失败」。
 */
object SyncStatusStore {

    private const val HTTP_OK = 200

    private val _state = MutableStateFlow(SyncStatusSnapshot())

    /** 状态页与「我的」入口都订阅这一条。 */
    val state: StateFlow<SyncStatusSnapshot> = _state.asStateFlow()

    /** 通用改法：字段多、调用方少，留一个口子免得每加一个字段就加一个方法。 */
    fun update(transform: (SyncStatusSnapshot) -> SyncStatusSnapshot) {
        _state.update(transform)
    }

    /**
     * 记一次真实的尝试结果。
     *
     * - [lastAttemptAt] 永远是「现在」；
     * - [SyncPhase.OK] 时顺手把 [lastSuccessAt] 记成现在，HTTP 码补 200（调用方没传的话）；
     * - [retryAtEpochMs] 传 null = 现在没有待执行的重试（成功、或这一轮不打算自动重试）。
     */
    fun report(
        phase: SyncPhase,
        httpCode: Int? = null,
        message: String? = null,
        retryAtEpochMs: Long? = null
    ) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            current.copy(
                phase = phase,
                lastAttemptAt = now,
                lastSuccessAt = if (phase == SyncPhase.OK) now else current.lastSuccessAt,
                // 1.4.7 修正：没有 HTTP 码的新失败不能继承上一次的码。
                // 否则限流过一次之后，后面断网/超时都会顶着「HTTP 429」说是限流。
                lastHttpCode = when {
                    httpCode != null -> httpCode
                    phase == SyncPhase.OK -> HTTP_OK
                    else -> null
                },
                lastMessage = message,
                retryAtEpochMs = retryAtEpochMs
            )
        }
    }

    /** 还没发出去的条数（只读本地队列，不联网）。 */
    fun reportPending(count: Int) {
        _state.update { it.copy(pendingCount = count.coerceAtLeast(0)) }
    }

    /** 电脑端 `/health` 自报的版本、启动时间与能力清单；老服务端没有这个接口时保持默认空值。 */
    fun reportServer(version: String?, startedAt: String?, capabilities: Set<String>) {
        _state.update {
            it.copy(
                serverVersion = version,
                serverStartedAt = startedAt,
                capabilities = capabilities
            )
        }
    }

    /**
     * 1.4.7.2 W5：同步回执里的数据治理信息。
     *
     * [conflicts] 是服务端判定「这条 op 真的旧了/同 lrt 输了」的条数；
     * 幂等重放单独记 [replayed]，不要把正常重试记成冲突。
     */
    fun reportSyncDiagnostics(epoch: Long, conflicts: Int, trimmed: Int, replayed: Int) {
        _state.update {
            it.copy(
                syncEpoch = epoch.coerceAtLeast(1),
                lastConflictCount = conflicts.coerceAtLeast(0),
                lastTrimmedCount = trimmed.coerceAtLeast(0),
                lastReplayCount = replayed.coerceAtLeast(0)
            )
        }
    }

    /** 最近一次成功请求实际命中的 origin；用来识别「手填地址连不上、回退到上次成功」。 */
    fun reportOrigin(origin: String) {
        _state.update { it.copy(lastOrigin = origin) }
    }

    /** 回到冷启动状态（换地址、清口令之后用）。 */
    fun reset() {
        _state.value = SyncStatusSnapshot()
    }
}
