package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 每个单词的记忆思路与常用搭配。
 *
 * 独立于 tb_book_word_content：内容表在导入时是 REPLACE 写入的，
 * 把记忆思路放进去会被一次重新导入冲掉；这里以 word_id 为主键，
 * 与词绑定，而不是与某本书绑定。
 */
@Entity(
    tableName = "tb_word_memory",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class WordMemory(
    @PrimaryKey
    @ColumnInfo(name = "word_id")
    val wordId: Long,
    /** 记忆思路，多行文本，一行一步。 */
    val approach: String = "",
    /** 常用搭配，多行文本，一行一条。 */
    val collocations: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
    /** 来源：seed = 内置种子；import = 文件导入；user = 用户重写过。 */
    val source: String = SOURCE_SEED,
    /** 重写版本号；0 = 从没重写过。 */
    val rev: Int = 0,
    /** 上一版思路，够撤销一次；撤销后清空。 */
    @ColumnInfo(name = "prev_approach")
    val prevApproach: String? = null,
    /**
     * 记忆方法标签（六脉神剑之一）：sound / meaning / shape / root / contrast / polysemy。
     * 空串 = 还没标注（老数据、用户自建词）。种子由 by-word 语料回填，重写时由生成结果带上。
     */
    val method: String = "",
    /**
     * 上一版的方法标签（DB 18）：撤销时和 [prevApproach] 一起回滚，回滚完清空。
     * 只够撤销一次；null = 没有可回滚的方法（老数据、从没重写过）。
     */
    @ColumnInfo(name = "prev_method")
    val prevMethod: String? = null,
    /**
     * 1.4.7.1（DB 21）：这一版思路是不是「没复核」的产出。
     *
     * 电脑端重写链路里有时会因为超时/兜底而交出一版没走完自检的思路，服务端会在结果条目上
     * 带 `unverified: true` + `reason`。这里把那个标记跟着思路一起落库，卡背上挂一个中性角标，
     * 让人知道「这版还没复核过」，而不是靠颜色吓人。
     *
     * 老服务端/老记录没有这个字段 → 一律 false（`dto.unverified == true` 兜底），不会误报。
     */
    @ColumnInfo(name = "unverified", defaultValue = "0")
    val unverified: Boolean = false,
    /** 未复核的原因（服务端 reason），空 = 没有更多说明。和 [unverified] 同生同灭。 */
    @ColumnInfo(name = "unverified_reason")
    val unverifiedReason: String? = null
) {
    companion object {
        const val SOURCE_SEED = "seed"
        const val SOURCE_IMPORT = "import"
        const val SOURCE_USER = "user"
    }
}
