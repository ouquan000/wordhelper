package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.StudyRating
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一次 FSRS-5 调度结果。
 *
 * 字段与 [Progress] 的 fsrs* 列一一对应，集成分支直接把结果写回 Progress 即可。
 * [state] 取本对象的 STATE_* 常量；[status] 是 App 既有的 Progress.STATUS_* 口径。
 */
data class FsrsScheduleResult(
    val stability: Float,
    val difficulty: Float,
    val lapses: Int,
    val state: Int,
    val intervalDays: Int,
    val nextReviewTime: Long,
    val repetitions: Int,
    val status: Int
)

/**
 * 标准 FSRS-5 调度器（19 个社区默认权重）。
 *
 * 与 [Sm2Scheduler] 的分工：
 * - SM-2 继续负责拼写、旧数据兼容等既有链路；
 * - 本对象只负责「看卡/复习」这一条链路的 S/D 更新与间隔计算；
 * - 日切仍复用 [Sm2Scheduler.nextReviewTimeByDays]（凌晨 4 点刷新日）。
 *
 * 设计口径：
 * - DB23→24 会把老 Progress 行回填成 fsrsStability=intervalDays、fsrsState=REVIEW，
 *   这些行走复习公式；只有 S<=0 / state=NEW 的行（真新卡或写入方漏填）才按新卡初始化，
 *   不会拿 S=0 去算增长公式。
 * - 所有中间计算用 Double，落库前统一 clamp，避免 NaN / 溢出污染 Progress。
 */
object FsrsScheduler {

    const val STATE_NEW = 0
    const val STATE_LEARNING = 1
    const val STATE_REVIEW = 2
    const val STATE_RELEARNING = 3
    const val DAY_REFRESH_HOUR = 4

    /** FSRS-5 社区默认权重（w[0]..w[18]）。 */
    private val W = floatArrayOf(
        0.40255f,
        1.18385f,
        3.173f,
        15.69105f,
        7.1949f,
        0.5345f,
        1.4604f,
        0.0046f,
        1.54575f,
        0.1192f,
        1.01925f,
        1.9395f,
        0.11f,
        0.29605f,
        2.2698f,
        0.2315f,
        2.9898f,
        0.51655f,
        0.6621f
    )

    private const val DECAY = -0.5f
    private const val FACTOR = 19f / 81f

    private const val MIN_ELAPSED_DAYS = 0.01f
    private const val MIN_STABILITY = 0.01f
    private const val MAX_STABILITY = 36_500f
    private const val MIN_DIFFICULTY = 1f
    private const val MAX_DIFFICULTY = 10f
    private const val MIN_INTERVAL_DAYS = 1
    private const val MAX_INTERVAL_DAYS = 36_500
    private const val MIN_RETENTION = 0.80f
    private const val MAX_RETENTION = 0.97f
    private const val DEFAULT_RETENTION = 0.90f
    private const val MILLIS_PER_DAY = 86_400_000.0

    private const val GRADE_AGAIN = 1
    private const val GRADE_HARD = 2
    private const val GRADE_GOOD = 3
    private const val GRADE_EASY = 4

    fun schedule(
        current: Progress?,
        rating: StudyRating,
        easy: Boolean = false,
        now: Long = System.currentTimeMillis(),
        desiredRetention: Float = 0.90f
    ): FsrsScheduleResult {
        val grade = if (easy) GRADE_EASY else rating.toFsrsGrade()
        val retention = sanitizeRetention(desiredRetention)
        val safeNow = if (now > 0L) now else System.currentTimeMillis()

        // 没有 FSRS 记忆状态的行走初始化公式。DB23→24 迁移会把已学过的旧行回填成
        // fsrsState=REVIEW + fsrsStability=intervalDays（见 MIGRATION_23_24），这些行按复习处理；
        // 从没学过、或写入方漏填 FSRS 字段的行 state 仍是 NEW / S<=0，按新卡初始化，避免拿 S=0 算增长。
        val isNewCard = current == null ||
            current.fsrsStability <= 0f ||
            current.fsrsState == STATE_NEW

        val previousLapses = current?.fsrsLapses ?: 0
        val previousRepetitions = current?.repetitions ?: 0

        val stability: Float
        val difficulty: Float
        val lapses: Int

        if (isNewCard) {
            stability = initialStability(grade)
            difficulty = initialDifficulty(grade)
            // 新卡第一次就点 AGAIN 不算「遗忘」：还没有建立过可遗忘的记忆，
            // 与 SM-2 的首次失败一致（不增加 lapse 计数）。
            lapses = previousLapses
        } else {
            val prev = current!!
            val previousStability = sanitizeStability(prev.fsrsStability)
            val previousDifficulty = if (prev.fsrsDifficulty > 0f) {
                sanitizeDifficulty(prev.fsrsDifficulty)
            } else {
                initialDifficulty(GRADE_EASY)
            }
            val elapsedDays = elapsedDays(prev, safeNow)
            val r = retrievability(elapsedDays, previousStability)
            val nextDifficulty = nextDifficulty(previousDifficulty, grade)

            difficulty = nextDifficulty
            if (grade == GRADE_AGAIN) {
                stability = nextForgetStability(previousStability, nextDifficulty, r)
                lapses = previousLapses + 1
            } else {
                stability = nextSuccessStability(previousStability, nextDifficulty, r, grade)
                lapses = previousLapses
            }
        }

        val intervalDays = nextIntervalDays(stability, retention)
        val state = when {
            isNewCard -> STATE_LEARNING
            grade == GRADE_AGAIN -> STATE_RELEARNING
            else -> STATE_REVIEW
        }
        val status = when {
            intervalDays >= 30 -> Progress.STATUS_MASTERED
            state == STATE_NEW -> Progress.STATUS_NEW
            else -> Progress.STATUS_LEARNING
        }
        // repetitions 沿用 SM-2 的「答对递增」语义；AGAIN 保持原值不递增，
        // 遗忘次数由独立的 fsrsLapses 记录，避免把「历史复习次数」清零。
        val repetitions = if (grade == GRADE_AGAIN) previousRepetitions else previousRepetitions + 1

        return FsrsScheduleResult(
            stability = stability,
            difficulty = difficulty,
            lapses = lapses,
            state = state,
            intervalDays = intervalDays,
            nextReviewTime = Sm2Scheduler.nextReviewTimeByDays(intervalDays, safeNow),
            repetitions = repetitions,
            status = status
        )
    }

    /**
     * 记忆可提取性 R(t,S) = (1 + FACTOR * t / S)^DECAY，DECAY=-0.5。
     *
     * t 是距上次复习的天数（至少 0.01 天，避免同一时刻重复调度时除零）；
     * S<=0 / NaN / 无穷大一律兜底，保证返回值落在 0..1。
     */
    fun retrievability(elapsedDays: Float, stability: Float): Float {
        val t = if (elapsedDays.isFinite()) {
            elapsedDays.coerceAtLeast(MIN_ELAPSED_DAYS)
        } else {
            MIN_ELAPSED_DAYS
        }
        val s = sanitizeStability(stability)
        val value = (1.0 + FACTOR.toDouble() * t.toDouble() / s.toDouble())
            .pow(DECAY.toDouble())
        return value.toFloat().coerceIn(0f, 1f)
    }

    /**
     * 由稳定度和目标留存率反推间隔天数：
     * I = S / FACTOR * (desiredRetention^(1/DECAY) - 1)，至少 1 天、最多 36500 天。
     */
    fun nextIntervalDays(stability: Float, desiredRetention: Float): Int {
        val s = sanitizeStability(stability)
        val retention = sanitizeRetention(desiredRetention)
        val rawDays = s.toDouble() / FACTOR.toDouble() *
            (retention.toDouble().pow(1.0 / DECAY.toDouble()) - 1.0)
        if (!rawDays.isFinite() || rawDays <= 0.0) return MIN_INTERVAL_DAYS
        return rawDays.coerceAtMost(MAX_INTERVAL_DAYS.toDouble()).roundToInt()
            .coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
    }

    private fun StudyRating.toFsrsGrade(): Int = when (this) {
        StudyRating.AGAIN -> GRADE_AGAIN
        StudyRating.HARD -> GRADE_HARD
        StudyRating.GOOD -> GRADE_GOOD
    }

    /** 新卡初始稳定度 S0 = w[grade-1]。 */
    private fun initialStability(grade: Int): Float {
        val index = grade.coerceIn(GRADE_AGAIN, GRADE_EASY) - 1
        return sanitizeStability(W[index])
    }

    /** 新卡初始难度 D0 = w[4] - exp(w[5] * (grade-1)) + 1，夹 1..10。 */
    private fun initialDifficulty(grade: Int): Float {
        val g = grade.coerceIn(GRADE_AGAIN, GRADE_EASY)
        val d0 = W[4].toDouble() - exp(W[5].toDouble() * (g - 1)) + 1.0
        return sanitizeDifficulty(d0.toFloat())
    }

    /** 复习难度：先按评分线性修正，再向 D0(4) 均值回归，夹 1..10。 */
    private fun nextDifficulty(previous: Float, grade: Int): Float {
        val delta = previous.toDouble() - W[6].toDouble() * (grade - 3)
        val reverted = W[7].toDouble() * initialDifficulty(GRADE_EASY) +
            (1.0 - W[7].toDouble()) * delta
        return sanitizeDifficulty(reverted.toFloat())
    }

    /** 成功复习：S' = S * (1 + exp(w8)*(11-D)*S^(-w9)*(exp((1-R)*w10)-1)*hardPenalty*easyBonus)。 */
    private fun nextSuccessStability(
        stability: Float,
        difficulty: Float,
        r: Float,
        grade: Int
    ): Float {
        val hardPenalty = if (grade == GRADE_HARD) W[15].toDouble() else 1.0
        val easyBonus = if (grade == GRADE_EASY) W[16].toDouble() else 1.0
        val growth = exp(W[8].toDouble()) *
            (11.0 - difficulty.toDouble()) *
            stability.toDouble().pow(-W[9].toDouble()) *
            (exp((1.0 - r.toDouble()) * W[10].toDouble()) - 1.0) *
            hardPenalty *
            easyBonus
        val next = stability.toDouble() * (1.0 + growth)
        return sanitizeStability(next.toFloat())
    }

    /** 遗忘复习：S' = w11 * D^(-w12) * ((S+1)^w13 - 1) * exp((1-R)*w14)。 */
    private fun nextForgetStability(stability: Float, difficulty: Float, r: Float): Float {
        val next = W[11].toDouble() *
            difficulty.toDouble().pow(-W[12].toDouble()) *
            ((stability.toDouble() + 1.0).pow(W[13].toDouble()) - 1.0) *
            exp((1.0 - r.toDouble()) * W[14].toDouble())
        return sanitizeStability(next.toFloat())
    }

    /**
     * 距上次复习的天数。
     *
     * lastReviewTime 缺失（0）时用 intervalDays 估算；都缺失则按 0.01 天处理。
     * 负值/未来时间统一夹到 0.01，避免 R>1 或稳定度异常增长。
     */
    private fun elapsedDays(current: Progress, now: Long): Float {
        if (current.lastReviewTime <= 0L) {
            val estimated = current.intervalDays.toFloat()
            return if (estimated > 0f) estimated.coerceAtLeast(MIN_ELAPSED_DAYS) else MIN_ELAPSED_DAYS
        }
        val raw = (now - current.lastReviewTime).toDouble() / MILLIS_PER_DAY
        if (!raw.isFinite()) return MIN_ELAPSED_DAYS
        return raw.toFloat().coerceAtLeast(MIN_ELAPSED_DAYS)
    }

    private fun sanitizeRetention(retention: Float): Float {
        if (!retention.isFinite()) return DEFAULT_RETENTION
        return retention.coerceIn(MIN_RETENTION, MAX_RETENTION)
    }

    private fun sanitizeStability(stability: Float): Float {
        if (!stability.isFinite() || stability <= 0f) return MIN_STABILITY
        return stability.coerceIn(MIN_STABILITY, MAX_STABILITY)
    }

    private fun sanitizeDifficulty(difficulty: Float): Float {
        if (!difficulty.isFinite()) return (MIN_DIFFICULTY + MAX_DIFFICULTY) / 2f
        return difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }
}
