package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tb_progress",
    indices = [
        Index(value = ["word_id", "book_id"], unique = true),
        Index(value = ["next_review_time"])
    ]
)
data class Progress(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val status: Int = STATUS_NEW,
    val repetitions: Int = 0,
    @ColumnInfo(name = "interval_days")
    val intervalDays: Int = 0,
    @ColumnInfo(name = "next_review_time")
    val nextReviewTime: Long = 0L,
    @ColumnInfo(name = "ease_factor")
    val easeFactor: Float = 2.5f,
    @ColumnInfo(name = "review_count")
    val reviewCount: Int = 0,
    @ColumnInfo(name = "spell_correct_count")
    val spellCorrectCount: Int = 0,
    @ColumnInfo(name = "spell_wrong_count")
    val spellWrongCount: Int = 0,
    @ColumnInfo(name = "marked_easy_count")
    val markedEasyCount: Int = 0,
    @ColumnInfo(name = "last_easy_time")
    val lastEasyTime: Long = 0L,
    @ColumnInfo(name = "consecutive_correct")
    val consecutiveCorrect: Int = 0,
    @ColumnInfo(name = "avg_response_time_ms")
    val avgResponseTimeMs: Float = 0f,
    @ColumnInfo(name = "last_review_time")
    val lastReviewTime: Long = 0L,
    /** FSRS-5 记忆稳定度 S；0 = 尚未建立 FSRS 状态（新卡或 DB23→24 迁移前的旧行）。 */
    @ColumnInfo(name = "fsrsStability", defaultValue = "0")
    val fsrsStability: Float = 0f,
    /** FSRS-5 难度 D，1..10。 */
    @ColumnInfo(name = "fsrsDifficulty", defaultValue = "0")
    val fsrsDifficulty: Float = 0f,
    /** FSRS-5 累计遗忘次数。 */
    @ColumnInfo(name = "fsrsLapses", defaultValue = "0")
    val fsrsLapses: Int = 0,
    /** 见 FsrsScheduler.STATE_*：0 新卡 / 1 学习中 / 2 复习中 / 3 重学。 */
    @ColumnInfo(name = "fsrsState", defaultValue = "0")
    val fsrsState: Int = 0
) {
    companion object {
        const val STATUS_NEW = 0
        const val STATUS_LEARNING = 1
        const val STATUS_MASTERED = 2
    }
}
