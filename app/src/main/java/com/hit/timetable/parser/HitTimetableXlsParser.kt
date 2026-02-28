package com.hit.timetable.parser

import com.hit.timetable.data.CourseItem
import com.hit.timetable.data.TimetableData
import jxl.Workbook
import java.io.InputStream

class HitTimetableXlsParser {

    fun parse(inputStream: InputStream): TimetableData {
        val workbook = Workbook.getWorkbook(inputStream)
        try {
            val sheet = workbook.getSheet(0)
            val sourceTitle = sheet.getCell(0, 0).contents.trim()

            val headerRow = findHeaderRow(sheet) ?: error("未识别到哈工大课表表头")
            val dayColumns = findDayColumns(sheet, headerRow)
            if (dayColumns.isEmpty()) error("未识别到星期列")

            val courses = mutableListOf<CourseItem>()

            for (row in (headerRow + 1) until sheet.rows) {
                val sectionLabel = safeCell(sheet, 1, row).trim()
                if (sectionLabel.isBlank()) continue

                for ((column, dayInfo) in dayColumns) {
                    val cellText = safeCell(sheet, column, row).trim()
                    if (cellText.isBlank()) continue
                    courses += parseCellCourses(
                        dayIndex = dayInfo.first,
                        dayName = dayInfo.second,
                        sectionLabel = sectionLabel,
                        sectionOrder = row,
                        cellText = cellText
                    )
                }
            }

            return TimetableData(
                sourceTitle = sourceTitle,
                importedAt = System.currentTimeMillis(),
                courses = courses
            )
        } finally {
            workbook.close()
        }
    }

    private fun findHeaderRow(sheet: jxl.Sheet): Int? {
        return (0 until sheet.rows).firstOrNull { row ->
            val markers = listOf("星期一", "星期二", "星期三", "星期四", "星期五")
            markers.count { marker -> rowContains(sheet, row, marker) } >= 3
        }
    }

    private fun findDayColumns(sheet: jxl.Sheet, headerRow: Int): Map<Int, Pair<Int, String>> {
        val dayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val result = mutableMapOf<Int, Pair<Int, String>>()
        for (column in 0 until sheet.columns) {
            val title = sheet.getCell(column, headerRow).contents.trim()
            val index = dayNames.indexOf(title)
            if (index >= 0) result[column] = (index + 1) to title
        }
        return result
    }

    private fun rowContains(sheet: jxl.Sheet, row: Int, marker: String): Boolean {
        for (column in 0 until sheet.columns) {
            if (sheet.getCell(column, row).contents.contains(marker)) return true
        }
        return false
    }

    private fun safeCell(sheet: jxl.Sheet, column: Int, row: Int): String {
        return if (column < sheet.columns && row < sheet.rows) sheet.getCell(column, row).contents else ""
    }

    private fun parseCellCourses(
        dayIndex: Int,
        dayName: String,
        sectionLabel: String,
        sectionOrder: Int,
        cellText: String
    ): List<CourseItem> {
        val lines = cellText
            .split("\n")
            .map { it.replace("　", " ").trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        val blocks = mutableListOf<Pair<String, List<String>>>()
        var i = 0
        while (i < lines.size) {
            val current = lines[i]
            if (containsWeekBracket(current)) {
                if (blocks.isNotEmpty()) {
                    val last = blocks.removeAt(blocks.lastIndex)
                    blocks += last.first to (last.second + current)
                }
                i++
                continue
            }

            val courseName = current
            i++

            val detailLines = mutableListOf<String>()
            while (i < lines.size) {
                val line = lines[i]
                if (isStartOfNextCourse(lines, i)) break
                detailLines += line
                i++
            }
            blocks += courseName to detailLines
        }

        val result = mutableListOf<CourseItem>()
        for ((courseName, detailLines) in blocks) {
            val (teacher, weekText, location) = parseDetailLines(detailLines)
            result += CourseItem(
                dayIndex = dayIndex,
                dayName = dayName,
                sectionLabel = sectionLabel,
                sectionOrder = sectionOrder,
                courseName = courseName,
                teacher = teacher,
                weekText = weekText,
                location = location,
                rawCellText = cellText
            )
            i += 2
        }
        return result
    }

    private fun containsWeekBracket(text: String): Boolean {
        val normalized = normalizeLine(text)
        return normalized.contains("[") && normalized.contains("]")
    }

    private fun isStartOfNextCourse(lines: List<String>, index: Int): Boolean {
        val current = normalizeLine(lines[index])
        if (containsWeekBracket(current)) return false
        val next = lines.getOrNull(index + 1) ?: return false
        return containsWeekBracket(next)
    }

    private fun isLikelyLocation(line: String): Boolean {
        val text = normalizeLine(line)
        if (text.isBlank()) return false
        if (LOCATION_KEYWORDS.any { text.contains(it) }) return true
        if (SHORT_LOCATION_REGEX.matches(text)) return true
        if (BUILDING_CODE_REGEX.matches(text)) return true
        return false
    }

    private fun parseDetailLines(detailLines: List<String>): Triple<String, String, String> {
        if (detailLines.isEmpty()) return Triple("", "", "")

        val structuredIndex = detailLines.indexOfFirst { containsWeekBracket(it) }
        if (structuredIndex >= 0) {
            val structured = parseTeacherWeeksAndLocation(detailLines[structuredIndex])
            if (structured != null) {
                val extraLocation = detailLines
                    .filterIndexed { index, _ -> index != structuredIndex }
                    .joinToString(" ") { normalizeLine(it) }
                    .trim()
                val mergedLocation = listOf(structured.third, extraLocation)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                return Triple(structured.first, structured.second, mergedLocation)
            }
        }

        if (detailLines.size >= 2) {
            return Triple(
                detailLines.first().trim(),
                "",
                detailLines.drop(1).joinToString(" ").trim()
            )
        }

        val only = detailLines.first().trim()
        return if (isLikelyLocation(only)) {
            Triple("", "", only)
        } else {
            Triple(only, "", "")
        }
    }

    private fun parseTeacherWeeksAndLocation(detail: String): Triple<String, String, String>? {
        val normalized = normalizeLine(detail)
        val firstBracket = normalized.indexOf('[')
        if (firstBracket < 0) return null

        val teacher = normalized.substring(0, firstBracket).trim()
        if (teacher.isBlank()) return null

        var rest = normalized.substring(firstBracket).trim()
        val weekParts = mutableListOf<String>()

        while (true) {
            rest = rest.trimStart(' ', '，', ',', '、')
            val match = WEEK_BLOCK_REGEX.find(rest) ?: break
            val suffix = match.groupValues[2].trim().ifBlank { "周" }
            weekParts += "[${match.groupValues[1].trim()}]${suffix}"
            rest = rest.removePrefix(match.value)
        }

        rest = rest.trimStart(' ', '，', ',', '、')
        if (rest.startsWith("周")) {
            rest = rest.removePrefix("周").trimStart(' ', '，', ',', '、')
        }

        val weekText = weekParts.joinToString("，")
        val location = rest.trim()
        return Triple(teacher, weekText, location)
    }

    private fun normalizeLine(text: String): String {
        return text
            .replace('［', '[')
            .replace('］', ']')
            .replace('（', '(')
            .replace('）', ')')
            .replace(',', '，')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val WEEK_BLOCK_REGEX = Regex("^\\[(.+?)](单周|双周|周)?")
        private val SHORT_LOCATION_REGEX = Regex("^[A-Za-z]?\\d{3,4}$")
        private val BUILDING_CODE_REGEX = Regex("^[LG]\\d{3,4}$")
        private val LOCATION_KEYWORDS = listOf(
            "正心", "致知", "格物", "成教楼", "活动中心", "主楼", "教学楼", "实验楼"
        )
    }
}
