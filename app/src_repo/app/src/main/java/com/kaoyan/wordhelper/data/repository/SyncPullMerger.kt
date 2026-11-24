package com.kaoyan.wordhelper.data.repository

import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import android.util.Log
import com.google.gson.Gson
import com.kaoyan.wordhelper.data.dao.BookDao
import com.kaoyan.wordhelper.data.dao.ProgressDao
import com.kaoyan.wordhelper.data.dao.SyncDao
import com.kaoyan.wordhelper.data.dao.WordDao
import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.network.RetryPolicy
import com.kaoyan.wordhelper.data.network.ServerHealth
import com.kaoyan.wordhelper.data.repository.SyncContracts.CAP_SYNC_CURSOR
import com.kaoyan.wordhelper.data.repository.SyncContracts.MAX_PULL_PAGES
import com.kaoyan.wordhelper.data.repository.SyncContracts.NO_ADDRESS
import com.kaoyan.wordhelper.data.repository.SyncContracts.PULL_PATH
import com.kaoyan.wordhelper.data.repository.SyncContracts.TAG
import com.kaoyan.wordhelper.data.repository.SyncContracts.TYPE_PROGRESS
import com.kaoyan.wordhelper.data.repository.SyncContracts.UNREACHABLE

internal class SyncPullMerger<W>(
    private val syncDao: SyncDao,
    private val wordDao: WordDao,
    private val bookDao: BookDao,
    private val progressDao: ProgressDao,
    private val gson: Gson,
    private val stateStore: SyncLocalState,
    private val endpoints: SyncEndpointResolver,
    private val http: SyncHttpTransport,
    wireType: Class<W>,
    private val progressRow: (W) -> SyncProgressRow
) {
    private val pageType = TypeToken.getParameterized(PullPage::class.java, wireType).type

    suspend fun pullLocked(): Result<Int> {
        val config = endpoints.config() ?: return Result.failure(SyncException(NO_ADDRESS))
        var state = stateStore.ensureState()
        var applied = 0
        var rewound = false

        repeat(MAX_PULL_PAGES) {
            // 服务端只认 since_rev；device_id 只在 push 时带，pull 不需要（也不该假装能过滤自己发过的 op）。
            val path = "$PULL_PATH?since_rev=${state.serverRev}"
            if (ServerHealth.supports(endpoints.serverHealth, CAP_SYNC_CURSOR) == false) {
                val message = RetryPolicy.missingCapabilityMessage(CAP_SYNC_CURSOR)
                http.publish(SyncPhase.SERVER_TOO_OLD, null, message)
                return Result.failure(SyncException(message))
            }
            val outcome = http.sendToOrigins(config) { origin -> http.requestGet(origin + path, config.token) }
            if (outcome.kind != RetryPolicy.Kind.OK) {
                // 游标没动，下次还是从同一处拉，不会漏也不会重复算。
                return Result.failure(SyncException(outcome.message ?: UNREACHABLE))
            }
            val pullBody = outcome.body
            if (pullBody.isNullOrBlank()) {
                return Result.failure(SyncException("电脑返回的同步数据是空的"))
            }
            val page = runCatching { gson.fromJson<PullPage<W>>(pullBody, pageType) }.getOrNull()
                ?: return Result.failure(SyncException("电脑返回的同步数据读不懂"))

            // 1.4.7.2 W5：恢复代次变了 = 服务端换库/坏文件重建。只归零游标重拉，
            // 本地待发队列原样保留；最多重来一次，第二次还变就报错让用户先别同步。
            val pageEpoch = page.epoch
            if (pageEpoch != null && SyncRules.epochChanged(pageEpoch, state.epoch)) {
                if (rewound) return Result.failure(SyncException("电脑端恢复代次反复变化，先别同步"))
                rewound = true
                Log.w(TAG, "电脑端恢复代次变了：${state.epoch} -> $pageEpoch，游标归零重拉")
                state = state.copy(serverRev = 0L, epoch = pageEpoch)
                syncDao.putState(state)
                SyncStatusStore.reportSyncDiagnostics(
                    epoch = pageEpoch,
                    conflicts = 0,
                    trimmed = 0,
                    replayed = 0
                )
                return@repeat
            }

            val serverRev = page.serverRev
            if (SyncRules.revisionRegressed(serverRev, state.serverRev)) {
                // 电脑端清过库或换过电脑，rev 会从头算；本地游标比它大就归零重拉一次。
                if (rewound) return Result.failure(SyncException("电脑端版本号对不上，先别同步"))
                rewound = true
                state = state.copy(serverRev = 0L)
                syncDao.putState(state)
                return@repeat
            }

            val result = applyRows(page.rows.orEmpty())
            applied += result.applied
            if (result.skipped > 0) {
                Log.i(TAG, "本次拉取跳过 ${result.skipped} 条（词或书不在本机 / 比本地旧）")
            }
            // 游标优先用服务端给的 next_since_rev：还有下一页时它只推进到本页最后一行，
            // 一次超过 500 条也不会漏（1.4.4 验收 P0）。老服务端没这个字段就退回 server_rev。
            val nextRev = SyncRules.nextRevision(page.nextSinceRev, serverRev)
            if (nextRev != null && nextRev != state.serverRev) {
                // rows 全部落库之后才推游标：中途失败下次会重放这一页，
                // 重放是安全的（同一条 lrt 相等的记录不会再覆盖）。
                state = state.copy(serverRev = nextRev, lastSyncAt = System.currentTimeMillis())
                syncDao.putState(state)
            }
            if (page.more != true) return Result.success(applied)
        }
        // 拉不完不能报成功：否则用户以为同步完了，其实后面还有一大截没下来。
        return Result.failure(SyncException("同步没拉完（一次最多 $MAX_PULL_PAGES 页），再点一次同步继续"))
    }

    /**
     * 把一页 rows 写进 tb_progress。返回真正覆盖了几条、跳过了几条。
     *
     * 只写契约里有的字段：本地多出来的拼写次数、连续正确、平均反应时间等一律沿用原值
     * （契约没有它们，硬覆盖会把手机上的统计抹掉）。
     */
    private suspend fun applyRows(rows: List<W>): PullApply {
        if (rows.isEmpty()) return PullApply(0, 0)
        // 服务端只存书名。用户可能自己建过同名书，取 id 最小的那本，保证每次落到同一本。
        val bookIdByName: Map<String, Long> = bookDao.getAllBooksList()
            .groupBy { it.name }
            .mapValues { (_, books) -> books.minOf { it.id } }

        var applied = 0
        var skipped = 0
        // 同一页里同一个 (词, 书) 来多条时，只让 lrt 最大的那条落地。
        val appliedLrt = HashMap<Pair<Long, Long>, Long>()

        rows.forEach { wire ->
            val row = progressRow(wire)
            val type = row.type
            if (!type.isNullOrBlank() && type != TYPE_PROGRESS) {
                skipped++
                return@forEach
            }
            val wordKey = row.wordKey?.trim().orEmpty()
            val wordId = if (wordKey.isEmpty()) null else wordDao.getWordIdByKey(wordKey)
            val bookName = row.bookName?.trim().orEmpty()
            val bookId = if (bookName.isEmpty()) null else bookIdByName[bookName]
            val lrt = row.lastReviewTime
            if (wordId == null || bookId == null || lrt == null) {
                // 词/书不在这台手机上，或这一行没有 lrt：跳过并计数，不动本地数据。
                skipped++
                return@forEach
            }
            val key = wordId to bookId
            val alreadyApplied = appliedLrt[key]
            if (SyncRules.alreadyApplied(alreadyApplied, lrt)) {
                skipped++
                return@forEach
            }
            val existing = progressDao.getProgress(wordId, bookId)
            // 最新赢：本地这条比对面新（比如刚在这台手机上复习过），就保留本地。
            if (SyncRules.localIsNewer(existing?.lastReviewTime, lrt)) {
                skipped++
                return@forEach
            }
            val target = (existing ?: Progress(wordId = wordId, bookId = bookId)).copy(
                status = row.status ?: existing?.status ?: Progress.STATUS_NEW,
                repetitions = row.repetitions ?: existing?.repetitions ?: 0,
                intervalDays = row.intervalDays ?: existing?.intervalDays ?: 0,
                nextReviewTime = row.nextReviewTime ?: existing?.nextReviewTime ?: 0L,
                easeFactor = row.easeFactor ?: existing?.easeFactor ?: 2.5f,
                reviewCount = row.reviewCount ?: existing?.reviewCount ?: 0,
                lastReviewTime = lrt,
                // 1.4.9-P1：FSRS 4 字段是可选的「增量契约」。对面没带（老数据/老服务端）时
                // 必须原样保留本地值，绝不能拿 0 冲掉本机已经算出来的 S/D/lapses/state。
                fsrsStability = row.fsrsStability ?: existing?.fsrsStability ?: 0f,
                fsrsDifficulty = row.fsrsDifficulty ?: existing?.fsrsDifficulty ?: 0f,
                fsrsLapses = row.fsrsLapses ?: existing?.fsrsLapses ?: 0,
                fsrsState = row.fsrsState ?: existing?.fsrsState ?: 0
            )
            if (existing == null) {
                progressDao.insert(target)
            } else {
                // existing.copy(...) 已经带着本地主键，update 只改字段、不换行。
                progressDao.update(target)
            }
            appliedLrt[key] = lrt
            applied++
        }
        return PullApply(applied, skipped)
    }
}

private data class PullApply(val applied: Int, val skipped: Int)

private data class PullPage<W>(
    @SerializedName("server_rev") val serverRev: Long? = null,
    @SerializedName("epoch") val epoch: Long? = null,
    @SerializedName("next_since_rev") val nextSinceRev: Long? = null,
    @SerializedName("rows") val rows: List<W>? = null,
    @SerializedName("more") val more: Boolean? = null
)
