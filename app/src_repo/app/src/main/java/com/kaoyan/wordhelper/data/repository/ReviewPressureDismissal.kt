package com.kaoyan.wordhelper.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaoyan.wordhelper.util.DateUtils
import java.time.LocalDate
import kotlinx.coroutines.flow.map

private val dismissedDay = stringPreferencesKey("review_pressure_dismissed_day")

internal val DataStore<Preferences>.pressureDismissedDay
    get() = data.map { it[dismissedDay] }

internal suspend fun DataStore<Preferences>.dismissPressure(day: LocalDate = DateUtils.currentLearningDate()) {
    edit { it[dismissedDay] = day.toString() }
}

internal fun pressureVisible(day: LocalDate, dismissed: String?): Boolean = day.toString() != dismissed
