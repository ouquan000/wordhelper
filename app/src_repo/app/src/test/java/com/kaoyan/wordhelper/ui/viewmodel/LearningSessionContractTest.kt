package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.entity.Word
import com.kaoyan.wordhelper.data.model.StudyRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningSessionContractTest {
    private val session = LearningSessionState()
    private fun word(id: Long) = Word(id = id, word = "word$id", bookId = 1L)

    private fun reload(vararg ids: Long, presentAgain: Boolean = false) {
        session.rebuildQueue(ids.map(::word), { false }, presentAgain)
    }

    @Test fun repeatedPresentationAndContentRefreshHaveDistinctGenerations() {
        reload(1)
        val generation = session.cardPresentation.value.generation
        session.publishWord(word(1).copy(approach = "edited"))
        assertEquals(generation, session.cardPresentation.value.generation)
        assertEquals("edited", session.cardPresentation.value.word?.approach)
        reload(1, presentAgain = true)
        assertEquals(generation + 1, session.cardPresentation.value.generation)
        reload()
        reload(presentAgain = true)
        assertEquals(generation + 2, session.cardPresentation.value.generation)
    }

    @Test fun restoredWaitingWordIsHiddenWithoutCountingAsASessionSkip() {
        session.restoredWaitingRewriteWords[2] = word(2)
        session.waitingRewriteWords[3] = word(3)
        reload(1, 2, 3, 4)
        assertEquals(listOf(1L, 4L), session.activeQueue.map { it.id })
        assertEquals(3, session.totalCount.value)
        assertEquals(1, session.currentIndex.value)
    }

    @Test fun completionRetainsAnsweredTotalAndIndex() {
        reload(1, 2)
        session.markAnswered()
        reload(2)
        assertEquals(2, session.totalCount.value)
        assertEquals(2, session.currentIndex.value)
        session.markAnswered()
        reload()
        assertEquals(null, session.currentWord.value)
        assertEquals(2, session.currentIndex.value)
        assertEquals(2, session.totalCount.value)
    }

    @Test fun retryIsUniqueAndDoesNotCountAsAnAnswer() {
        reload(1)
        session.enqueueImmediateRetry(word(1))
        session.enqueueImmediateRetry(word(1))
        reload(1, 1, presentAgain = true)
        assertEquals(listOf(1L), session.activeQueue.map { it.id })
        assertEquals(0, session.answeredInSession)
        assertEquals(1, session.currentIndex.value)
    }

    @Test fun stableReinsertSurvivesRefreshAndHonorsSessionSkip() {
        session.rememberPendingReinsert(9, word(9), 100)
        reload(1, 2, 3, 4, 5)
        val first = session.activeQueue.map { it.id }
        assertEquals(1L, first.first())
        assertEquals(1, first.count { it == 9L })
        reload(1, 2, 3, 4, 5)
        assertEquals(first, session.activeQueue.map { it.id })
        session.forgetPendingReinsert(9)
        session.waitingRewriteWords[9] = word(9)
        session.sessionSkippedWordIds.add(9)
        reload(1, 2, 3, 4, 5, 9)
        assertFalse(session.activeQueue.any { it.id == 9L })
        assertEquals(6, session.totalCount.value)
        session.waitingRewriteWords.remove(9)
        session.removeSessionSkippedWord(9)
        session.rememberPendingReinsert(9, word(9), 100)
        reload(1, 2, 3, 4, 5)
        assertEquals(first, session.activeQueue.map { it.id })
    }

    @Test fun restoredPositionAdvancesOnlyAfterConsumptionAndClampsAtEnds() {
        assertEquals(2, LearningSessionRules.restoredPosition(2, false, 4))
        assertEquals(1, LearningSessionRules.restoredPosition(2, true, 4))
        assertEquals(0, LearningSessionRules.restoredPosition(0, true, 4))
        assertEquals(1, LearningSessionRules.restoredPosition(5, false, 1))
    }

    @Test fun recognitionDowngradesOnlyAboveThresholdWithV4Enabled() {
        for (rating in StudyRating.values()) {
            assertEquals(rating, LearningSessionRules.recognitionRating(rating, true, 6000, 6000))
            assertEquals(rating, LearningSessionRules.recognitionRating(rating, false, 6001, 6000))
        }
        assertEquals(StudyRating.HARD, LearningSessionRules.recognitionRating(StudyRating.GOOD, true, 6001, 6000))
        assertEquals(StudyRating.AGAIN, LearningSessionRules.recognitionRating(StudyRating.HARD, true, 6001, 6000))
        assertEquals(StudyRating.AGAIN, LearningSessionRules.recognitionRating(StudyRating.AGAIN, true, 6001, 6000))
    }

    @Test fun pressureReliefRequiresAnEligibleBookAndStrictlyExcessDueCount() {
        assertFalse(LearningSessionRules.canRelievePressure(true, true, 120, 120))
        assertFalse(LearningSessionRules.canRelievePressure(false, true, 120, 121))
        assertFalse(LearningSessionRules.canRelievePressure(true, false, 120, 121))
        assertTrue(LearningSessionRules.canRelievePressure(true, true, 120, 121))
    }
}
