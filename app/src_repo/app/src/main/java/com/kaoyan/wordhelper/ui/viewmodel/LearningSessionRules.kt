package com.kaoyan.wordhelper.ui.viewmodel

import com.kaoyan.wordhelper.data.model.StudyRating

internal object LearningSessionRules {
    fun generation(previous: Long, oldId: Long?, newId: Long?, presentAgain: Boolean): Long {
        val changed = oldId != newId || (presentAgain && newId != null)
        return if (changed) previous + 1 else previous
    }

    fun currentIndex(answered: Int, remaining: Int, total: Int): Int = when {
        total <= 0 -> 0
        remaining == 0 -> total
        else -> (answered + 1).coerceAtMost(total)
    }

    fun restoredPosition(oldIndex: Int, consumed: Boolean, size: Int): Int {
        val target = if (consumed) (oldIndex - 1).coerceAtLeast(0) else oldIndex
        return target.coerceIn(0, size)
    }

    fun canRelievePressure(enabled: Boolean, eligibleBook: Boolean, cap: Int, due: Int): Boolean =
        enabled && eligibleBook && due > cap

    fun recognitionRating(rating: StudyRating, v4: Boolean, responseTimeMs: Long, thresholdMs: Long): StudyRating {
        if (!v4 || responseTimeMs <= thresholdMs) return rating
        return when (rating) {
            StudyRating.GOOD -> StudyRating.HARD
            StudyRating.HARD -> StudyRating.AGAIN
            StudyRating.AGAIN -> StudyRating.AGAIN
        }
    }
}
