package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.network.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesContractTest {
    @Test fun rateLimitExpiresAtTheExactDeadline() {
        assertTrue(SyncRules.isCoolingDown(true, 100, 99))
        assertFalse(SyncRules.isCoolingDown(true, 100, 100))
        assertFalse(SyncRules.isCoolingDown(false, 100, 99))
    }

    @Test fun aRateLimitStopsFallbackEvenWithoutAnHttpResponse() {
        assertTrue(SyncRules.stopAtOrigin(null, RetryPolicy.Kind.RATE_LIMITED))
        assertTrue(SyncRules.stopAtOrigin(429, RetryPolicy.Kind.RATE_LIMITED))
        assertFalse(SyncRules.stopAtOrigin(null, RetryPolicy.Kind.RETRYABLE))
    }

    @Test fun authAndVersionFailuresLeaveTheNextCandidateAvailable() {
        assertFalse(SyncRules.stopAtOrigin(401, RetryPolicy.Kind.UNAUTHORIZED))
        assertFalse(SyncRules.stopAtOrigin(404, RetryPolicy.Kind.NOT_FOUND))
        assertTrue(SyncRules.stopAtOrigin(299, RetryPolicy.Kind.OK))
    }

    @Test fun lanTimeoutClassificationIncludesOnlyTheOriginalRanges() {
        assertTrue(SyncRules.isLanOrigin("http://172.16.0.1"))
        assertTrue(SyncRules.isLanOrigin("http://172.31.255.255"))
        assertFalse(SyncRules.isLanOrigin("http://172.32.0.1"))
        assertTrue(SyncRules.isLanOrigin("http://DESKTOP.local"))
    }

    @Test fun eitherDirectionOfEpochChangeRequiresARewind() {
        assertTrue(SyncRules.epochChanged(1, 3))
        assertTrue(SyncRules.epochChanged(4, 3))
        assertFalse(SyncRules.epochChanged(3, 3))
    }

    @Test fun onlyAKnownLowerRevisionRequiresARewind() {
        assertTrue(SyncRules.revisionRegressed(0, 1))
        assertFalse(SyncRules.revisionRegressed(1, 1))
        assertFalse(SyncRules.revisionRegressed(null, 1))
    }

    @Test fun paginatedCursorBeatsServerHeadIncludingZero() {
        assertEquals(0L, SyncRules.nextRevision(0, 700))
        assertEquals(500L, SyncRules.nextRevision(500, 700))
        assertEquals(700L, SyncRules.nextRevision(null, 700))
        assertNull(SyncRules.nextRevision(null, null))
    }

    @Test fun aPageReplayAndAnExistingRowUseDifferentTieRules() {
        assertTrue(SyncRules.alreadyApplied(20, 20))
        assertFalse(SyncRules.localIsNewer(20, 20))
        assertTrue(SyncRules.localIsNewer(21, 20))
        assertFalse(SyncRules.alreadyApplied(19, 20))
        assertFalse(SyncRules.localIsNewer(null, 20))
        assertFalse(SyncRules.alreadyApplied(null, 20))
    }
}
