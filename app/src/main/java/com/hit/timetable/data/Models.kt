package com.hit.timetable.data

data class CourseItem(
    val dayIndex: Int,
    val dayName: String,
    val sectionLabel: String,
    val sectionOrder: Int,
    val courseName: String,
    val teacher: String,
    val weekText: String,
    val location: String,
    val rawCellText: String
)

data class TimetableData(
    val sourceTitle: String,
    val importedAt: Long,
    val courses: List<CourseItem>
)
