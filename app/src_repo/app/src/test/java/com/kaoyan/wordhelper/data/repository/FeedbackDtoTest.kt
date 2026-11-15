package com.kaoyan.wordhelper.data.repository

import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.7.2 W1-A 回归：上传的 FeedbackDto 必须把版本号写进正文。
 *
 * 旧 bug 是字段有默认值 0、pushFeedback 构造时漏传，版本只活在 clientId 里；
 * 单测不碰网络，只锁「构造出来的 DTO 序列化后确实带 approachRev」这一环。
 */
class FeedbackDtoTest {

    @Test
    fun feedbackDtoSerializesApproachRev() {
        val dto = RewriteRepository.FeedbackDto(
            clientId = "f73-1-down-r3",
            wordId = 73L,
            bookId = 1L,
            word = "cathedral",
            rating = "down",
            approach = "旧思路",
            method = "sound",
            model = "ds-v41-flash",
            instruction = "谐音太牵强",
            approachRev = 3,
            createdAt = "2026-11-10T11:00:00+08:00"
        )
        val json = Gson().toJson(dto)
        assertTrue("正文必须带真实版本号: $json", json.contains("\"approachRev\":3"))
        assertTrue("clientId 仍需保留版本: $json", json.contains("f73-1-down-r3"))
    }
}
