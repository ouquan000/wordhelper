package com.kaoyan.wordhelper.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.5.3v2 C5：worker 闲时洗词的结果绝不能覆盖用户锁定的「免洗金牌」。
 * 只有「来源是 worker 且本地有锁」才跳过；用户自己点重写（source 空）永不拦截。
 */
class RewriteWorkerSourcePolicyTest {

    @Test
    fun workerResultWithLocalLockIsSkipped() {
        assertTrue(RewriteRepository.shouldSkipWorkerResult("worker", isLocked = true))
    }

    @Test
    fun workerResultWithoutLockIsAppliedNormally() {
        assertFalse(RewriteRepository.shouldSkipWorkerResult("worker", isLocked = false))
    }

    @Test
    fun userRequestedResultIsNeverSkippedEvenWhenLocked() {
        // 用户自己点「重写」的结果 source 为空串 —— 锁不阻止用户自己的操作。
        assertFalse(RewriteRepository.shouldSkipWorkerResult("", isLocked = true))
        assertFalse(RewriteRepository.shouldSkipWorkerResult(null, isLocked = true))
    }

    @Test
    fun otherSourceIsNeverSkipped() {
        assertFalse(RewriteRepository.shouldSkipWorkerResult("user", isLocked = true))
        assertFalse(RewriteRepository.shouldSkipWorkerResult("batch", isLocked = true))
    }
}
