package com.hit.timetable.data

import android.content.Context
import com.google.gson.Gson

class TimetableStorage(context: Context) {
    private val prefs = context.getSharedPreferences("hit_timetable", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(data: TimetableData) {
        prefs.edit().putString(KEY_DATA, gson.toJson(data)).apply()
    }

    fun load(): TimetableData? {
        val json = prefs.getString(KEY_DATA, null) ?: return null
        return runCatching { gson.fromJson(json, TimetableData::class.java) }.getOrNull()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_DATA)
            .remove(KEY_FIRST_WEEK_DATE)
            .apply()
    }

    fun saveFirstWeekDate(dateMillis: Long) {
        prefs.edit().putLong(KEY_FIRST_WEEK_DATE, dateMillis).apply()
    }

    fun loadFirstWeekDate(): Long? {
        if (!prefs.contains(KEY_FIRST_WEEK_DATE)) return null
        return prefs.getLong(KEY_FIRST_WEEK_DATE, 0L)
    }

    companion object {
        private const val KEY_DATA = "timetable_data"
        private const val KEY_FIRST_WEEK_DATE = "first_week_date"
    }
}
