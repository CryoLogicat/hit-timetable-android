package com.hit.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import com.hit.timetable.MainActivity
import com.hit.timetable.R
import com.hit.timetable.data.CourseItem
import com.hit.timetable.data.TimetableStorage
import java.util.Calendar
import kotlin.math.max

class TimetableWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> refreshAllWidgets(context)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            updateSingle(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimetableWidgetProvider::class.java))
            ids.forEach { updateSingle(context, manager, it) }
        }

        private fun updateSingle(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_timetable)
            val storage = TimetableStorage(context)
            val data = storage.load()

            val today = currentDayIndex()
            val todayName = dayName(today)
            val currentWeek = calculateCurrentWeek(storage.loadFirstWeekDate())

            if (data == null || data.courses.isEmpty()) {
                views.setTextViewText(R.id.tvWidgetTitle, "今日课程")
                views.setTextViewText(R.id.tvWidgetContent, "未导入课表")
            } else if (currentWeek == null) {
                views.setTextViewText(R.id.tvWidgetTitle, "${todayName}课程")
                views.setTextViewText(R.id.tvWidgetContent, "请先在App中设置第一周日期")
            } else if (currentWeek < 1) {
                views.setTextViewText(R.id.tvWidgetTitle, "${todayName}课程")
                views.setTextViewText(R.id.tvWidgetContent, "课程未开始")
            } else {
                val todayCourses = data.courses
                    .filter { it.dayIndex == today }
                    .filter { isCourseInWeek(it.weekText, currentWeek) }
                    .sortedBy { it.sectionOrder }

                views.setTextViewText(R.id.tvWidgetTitle, "${todayName} 第${currentWeek}周")
                views.setTextViewText(
                    R.id.tvWidgetContent,
                    if (todayCourses.isEmpty()) {
                        "今天没有课程"
                    } else {
                        buildColoredCourseText(todayCourses)
                    }
                )
            }

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

            manager.updateAppWidget(widgetId, views)
        }

        private fun currentDayIndex(): Int {
            return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                else -> 7
            }
        }

        private fun dayName(dayIndex: Int): String = when (dayIndex) {
            1 -> "星期一"
            2 -> "星期二"
            3 -> "星期三"
            4 -> "星期四"
            5 -> "星期五"
            6 -> "星期六"
            else -> "星期日"
        }

        private fun calculateCurrentWeek(firstWeekDateMillis: Long?): Int? {
            firstWeekDateMillis ?: return null
            val firstDay = startOfDay(firstWeekDateMillis)
            val today = startOfDay(System.currentTimeMillis())
            val diffDays = ((today - firstDay) / DAY_MILLIS).toInt()
            if (diffDays < 0) return 0
            return max(1, diffDays / 7 + 1)
        }

        private fun startOfDay(timeMillis: Long): Long {
            return Calendar.getInstance().apply {
                timeInMillis = timeMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun isCourseInWeek(weekText: String, currentWeek: Int): Boolean {
            if (weekText.isBlank()) return true
            val matches = WEEK_SEGMENT_REGEX.findAll(weekText).toList()
            if (matches.isEmpty()) return true

            return matches.any { match ->
                val content = match.groupValues[1].trim()
                val type = match.groupValues[2].trim()
                isWeekMatched(content, currentWeek) && isWeekTypeMatched(type, currentWeek)
            }
        }

        private fun isWeekMatched(content: String, currentWeek: Int): Boolean {
            val tokens = content.split('，', ',', '、').map { it.trim() }.filter { it.isNotBlank() }
            return tokens.any { token ->
                if (token.contains('-')) {
                    val parts = token.split('-').map { it.trim() }
                    if (parts.size != 2) return@any false
                    val start = parts[0].toIntOrNull() ?: return@any false
                    val end = parts[1].toIntOrNull() ?: return@any false
                    currentWeek in start..end
                } else {
                    token.toIntOrNull() == currentWeek
                }
            }
        }

        private fun isWeekTypeMatched(type: String, currentWeek: Int): Boolean {
            return when (type) {
                "单周" -> currentWeek % 2 == 1
                "双周" -> currentWeek % 2 == 0
                else -> true
            }
        }

        private fun buildColoredCourseText(courses: List<CourseItem>): CharSequence {
            val builder = SpannableStringBuilder()
            courses.forEachIndexed { index, item ->
                if (index > 0) builder.append('\n')

                val linePrefix = "${item.sectionLabel} "
                val lineMain = item.courseName
                val lineSuffix = item.location.takeIf { it.isNotBlank() }?.let { "  @$it" } ?: ""
                val lineText = linePrefix + lineMain + lineSuffix

                val lineSpan = SpannableString(lineText)
                val courseColor = COURSE_COLORS[(item.courseName.hashCode().ushr(1)) % COURSE_COLORS.size]
                lineSpan.setSpan(
                    ForegroundColorSpan(Color.parseColor("#FF334155")),
                    0,
                    linePrefix.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                lineSpan.setSpan(
                    ForegroundColorSpan(courseColor),
                    linePrefix.length,
                    linePrefix.length + lineMain.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (lineSuffix.isNotBlank()) {
                    lineSpan.setSpan(
                        ForegroundColorSpan(Color.parseColor("#FF64748B")),
                        linePrefix.length + lineMain.length,
                        lineText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                builder.append(lineSpan)
            }
            return builder
        }

        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private val WEEK_SEGMENT_REGEX = Regex("\\[(.+?)](单周|双周|周)?")
        private val COURSE_COLORS = listOf(
            Color.parseColor("#FF1D4ED8"),
            Color.parseColor("#FFB91C1C"),
            Color.parseColor("#FF0F766E"),
            Color.parseColor("#FF7C3AED"),
            Color.parseColor("#FFB45309")
        )
    }
}
