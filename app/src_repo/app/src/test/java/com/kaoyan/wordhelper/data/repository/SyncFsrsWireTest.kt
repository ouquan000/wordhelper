package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步契约：FSRS 4 字段必须是「可选」的。
 *
 *  - 新手机推送时带上 fsrs_*；
 *  - 老手机/老数据没有这 4 个键，服务端不能拒；
 *  - 老服务端返回的 row 没有这 4 个键，本地反序列化后保持 null，由 applyRows 回退到本地值。
 */
class SyncFsrsWireTest {

    private val gson = Gson()

    private fun sampleOp(
        stability: Float? = null,
        difficulty: Float? = null,
        lapses: Int? = null,
        state: Int? = null
    ) = SyncRepository.WireOp(
        opId = "op-1",
        type = "progress",
        wordKey = "cathedral",
        bookName = "六级记忆卡",
        status = 2,
        repetitions = 3,
        intervalDays = 7,
        nextReviewTime = 1_800_000_000_000L,
        easeFactor = 2.5f,
        reviewCount = 5,
        lastReviewTime = 1_799_000_000_000L,
        fsrsStability = stability,
        fsrsDifficulty = difficulty,
        fsrsLapses = lapses,
        fsrsState = state
    )

    @Test
    fun `new client serializes fsrs fields`() {
        val json = gson.toJson(sampleOp(12.5f, 4.75f, 2, 3))
        assertTrue(json.contains("\"fsrs_stability\":12.5"))
        assertTrue(json.contains("\"fsrs_difficulty\":4.75"))
        assertTrue(json.contains("\"fsrs_lapses\":2"))
        assertTrue(json.contains("\"fsrs_state\":3"))
        // 原有 11 个必需字段一个都不能少。
        listOf("op_id", "t", "w", "b", "st", "reps", "ivl", "nrt", "ef", "rc", "lrt").forEach {
            assertTrue("missing $it in $json", json.contains("\"$it\""))
        }
    }

    @Test
    fun `old style op omits fsrs fields instead of sending null`() {
        val json = gson.toJson(sampleOp())
        assertFalse(json.contains("fsrs_"))
        assertTrue(json.contains("\"op_id\":\"op-1\""))
    }

    @Test
    fun `legacy row without fsrs keys deserializes as null`() {
        val legacy = """
            {"op_id":"op-2","t":"progress","w":"cat","b":"六级记忆卡","st":1,
             "reps":1,"ivl":2,"nrt":100,"ef":2.6,"rc":1,"lrt":99}
        """.trimIndent()
        val op = gson.fromJson(legacy, SyncRepository.WireOp::class.java)
        assertNull(op.fsrsStability)
        assertNull(op.fsrsDifficulty)
        assertNull(op.fsrsLapses)
        assertNull(op.fsrsState)
        assertEquals("op-2", op.opId)
    }

    @Test
    fun `fsrs row round trips`() {
        val json = gson.toJson(sampleOp(9.25f, 3.5f, 1, 2))
        val back = gson.fromJson(json, SyncRepository.WireOp::class.java)
        assertEquals(9.25f, back.fsrsStability)
        assertEquals(3.5f, back.fsrsDifficulty)
        assertEquals(1, back.fsrsLapses)
        assertEquals(2, back.fsrsState)
    }
}
