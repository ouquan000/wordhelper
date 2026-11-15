package com.kaoyan.wordhelper.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步游标（单行表，id 恒为 1）。
 *
 * server_rev 是服务端签发的单个整数，客户端从不自己加；
 * device_id 首次同步时生成并固化，用来在拉取时排除自己发出的 op。
 */
@Entity(tableName = "tb_sync_state")
data class SyncState(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "server_rev")
    val serverRev: Long = 0,
    @ColumnInfo(name = "device_id")
    val deviceId: String = "",
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long = 0,
    /**
     * 1.4.7.2 W5：服务端恢复代次。
     *
     * 正常同步保持不变；服务端从坏文件重建时递增。客户端发现它变了只归零
     * [serverRev] 重新拉，绝不删本地 tb_sync_op 里还没发出去的进度。
     */
    @ColumnInfo(name = "epoch")
    val epoch: Long = 1
)
