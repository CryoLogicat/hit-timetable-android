package com.hit.timetable

import android.app.DatePickerDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.hit.timetable.data.CourseItem
import com.hit.timetable.data.TimetableStorage
import com.hit.timetable.parser.HitTimetableXlsParser
import com.hit.timetable.widget.TimetableWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var timetableGrid: LinearLayout
    private lateinit var tvDisplayedWeek: TextView

    private var selectedWeek: Int? = null

    private val storage by lazy { TimetableStorage(this) }
    private val parser by lazy { HitTimetableXlsParser() }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handleImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideStatusBar()
        setContentView(R.layout.activity_main)

        timetableGrid = findViewById(R.id.timetableGrid)
        tvDisplayedWeek = findViewById(R.id.tvDisplayedWeek)

        findViewById<ImageButton>(R.id.btnImport).setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/vnd.ms-excel", "*/*"))
        }

        findViewById<ImageButton>(R.id.btnClear).setOnClickListener {
            storage.clear()
            selectedWeek = null
            renderState(null, emptyList())
            TimetableWidgetProvider.refreshAllWidgets(this)
            Toast.makeText(this, "已清空课表", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.btnSetFirstWeek).setOnClickListener {
            showFirstWeekDatePicker()
        }

        findViewById<ImageButton>(R.id.btnGuide).setOnClickListener {
            showGuideDialog()
        }

        findViewById<ImageButton>(R.id.btnPrevWeek).setOnClickListener {
            changeDisplayedWeek(-1)
        }

        findViewById<ImageButton>(R.id.btnNextWeek).setOnClickListener {
            changeDisplayedWeek(1)
        }

        findViewById<ImageButton>(R.id.btnCurrentWeek).setOnClickListener {
            selectedWeek = resolveCurrentWeekNumber() ?: DEFAULT_WEEK
            renderSavedState()
        }

        val saved = storage.load()
        renderState(saved?.sourceTitle, saved?.courses.orEmpty(), saved?.importedAt)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        parser.parse(input)
                    } ?: error("无法打开文件")
                }
            }

            result.onSuccess { data ->
                storage.save(data)
                selectedWeek = resolveCurrentWeekNumber()
                renderState(data.sourceTitle, data.courses, data.importedAt)
                TimetableWidgetProvider.refreshAllWidgets(this@MainActivity)
                Toast.makeText(this@MainActivity, "导入成功，共 ${data.courses.size} 条课程", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderState(source: String?, courses: List<CourseItem>, importedAt: Long? = null) {
        val displayedWeek = selectedWeek ?: resolveCurrentWeekNumber()
        updateDisplayedWeekLabel(displayedWeek, !source.isNullOrBlank())

        if (source.isNullOrBlank()) {
            renderTimetable(emptyList())
            return
        }

        val weeklyCourses = displayedWeek
            ?.let { week -> courses.filter { isCourseInWeek(it.weekText, week) } }
            ?: courses

        renderTimetable(weeklyCourses)
    }

    private fun renderSavedState() {
        val saved = storage.load()
        renderState(saved?.sourceTitle, saved?.courses.orEmpty(), saved?.importedAt)
    }

    private fun changeDisplayedWeek(delta: Int) {
        val baseWeek = selectedWeek ?: resolveCurrentWeekNumber() ?: DEFAULT_WEEK
        selectedWeek = (baseWeek + delta).coerceIn(MIN_WEEK, MAX_WEEK)
        renderSavedState()
    }

    private fun updateDisplayedWeekLabel(displayedWeek: Int?, hasTimetable: Boolean) {
        tvDisplayedWeek.text = when {
            !hasTimetable -> "未导入课表"
            displayedWeek == null -> "全部周次"
            displayedWeek == resolveCurrentWeekNumber() -> "第${displayedWeek}周（本周）"
            else -> "第${displayedWeek}周"
        }
    }

    private fun renderTimetable(courses: List<CourseItem>) {
        timetableGrid.removeAllViews()

        val days = DEFAULT_DAYS

        val dayColumnWidth = resolveDayColumnWidth(days.size)

        val sections = courses
            .map { it.sectionOrder to it.sectionLabel }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .ifEmpty { DEFAULT_SECTIONS }

        val courseMap = courses.groupBy { it.sectionOrder to it.dayIndex }

        timetableGrid.addView(createHeaderRow(days, dayColumnWidth))

        sections.forEach { (sectionOrder, sectionLabel) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(createTimeCell(sectionLabel))

            days.forEach { (dayIndex, _) ->
                row.addView(createCourseCell(courseMap[sectionOrder to dayIndex].orEmpty(), dayColumnWidth))
            }

            timetableGrid.addView(row)
        }
    }

    private fun createHeaderRow(days: List<Pair<Int, String>>, dayColumnWidth: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(createHeaderCell("节次", TIME_COL_WIDTH))
            days.forEach { (_, dayName) ->
                addView(createHeaderCell(dayName, dayColumnWidth))
            }
        }
    }

    private fun createHeaderCell(text: String, cellWidth: Int): TextView {
        return TextView(this).apply {
            this.text = text
            setBackgroundResource(R.drawable.bg_grid_header)
            setTextColor(Color.parseColor("#FF0F172A"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(cellWidth, HEADER_HEIGHT).apply {
                rightMargin = GRID_GAP
                bottomMargin = GRID_GAP
            }
        }
    }

    private fun createTimeCell(label: String): TextView {
        return TextView(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_grid_time)
            setTextColor(Color.parseColor("#FF334155"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(TIME_COL_WIDTH, ROW_HEIGHT).apply {
                rightMargin = GRID_GAP
                bottomMargin = GRID_GAP
            }
        }
    }

    private fun createCourseCell(items: List<CourseItem>, dayColumnWidth: Int): FrameLayout {
        return FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_grid_cell)
            layoutParams = LinearLayout.LayoutParams(dayColumnWidth, ROW_HEIGHT).apply {
                rightMargin = GRID_GAP
                bottomMargin = GRID_GAP
            }

            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            if (items.isEmpty()) {
                stack.addView(TextView(context).apply {
                    text = "-"
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#FF94A3B8"))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                })
            } else {
                items.forEachIndexed { index, item ->
                    stack.addView(createCourseCard(item, index))
                }
            }

            addView(stack)
        }
    }

    private fun createCourseCard(item: CourseItem, salt: Int): TextView {
        val baseColor = COURSE_COLORS[(item.courseName.hashCode().ushr(1) + salt) % COURSE_COLORS.size]
        val strokeColor = ColorUtils.blendARGB(baseColor, Color.BLACK, 0.12f)

        return TextView(this).apply {
            setBackgroundResource(R.drawable.bg_course_card)
            background.mutate().setTint(baseColor)
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.0f)
            text = buildCourseText(item)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (salt > 0) topMargin = dp(4)
            }
            setOnClickListener {
                showCourseDetailDialog(item)
            }

            // Keep colorful cards readable by strengthening the border tone.
            (background.mutate()).let {
                it.setTint(baseColor)
            }
            if (background is android.graphics.drawable.GradientDrawable) {
                (background as android.graphics.drawable.GradientDrawable).setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun buildCourseText(item: CourseItem): String {
        return item.courseName
    }

    private fun showCourseDetailDialog(item: CourseItem) {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55344866"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_course_detail_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = dp(10).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(28)
                rightMargin = dp(28)
            }
            isClickable = true
        }

        card.addView(TextView(this).apply {
            text = item.courseName
            setTextColor(Color.parseColor("#FF0F172A"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 19f
        })

        addDetailLine(card, "时间", "${item.dayName} ${item.sectionLabel}")
        addDetailLine(card, "教师", item.teacher.ifBlank { "-" })
        addDetailLine(card, "周次", item.weekText.ifBlank { "-" })
        addDetailLine(card, "地点", item.location.ifBlank { "-" })

        scrim.addView(card)
        dialog.setContentView(scrim)

        scrim.setOnClickListener {
            card.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0f)
                .setDuration(150)
                .withEndAction { dialog.dismiss() }
                .start()
        }

        card.setOnClickListener { }

        dialog.show()

        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.alpha = 0f
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(220)
            .start()
    }

    private fun addDetailLine(container: LinearLayout, label: String, value: String) {
        container.addView(TextView(this).apply {
            text = "$label：$value"
            setTextColor(Color.parseColor("#FF334155"))
            textSize = 15f
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun showGuideDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55344866"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_course_detail_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = dp(10).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true
        }

        card.addView(TextView(this).apply {
            text = "APP 使用说明"
            setTextColor(Color.parseColor("#FF0F172A"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 19f
        })

        val guides = listOf(
            "1. 点击 + 导入哈工大课表 xls 文件",
            "2. 点击日历设置第一周日期以启用按周显示",
            "3. 课表仅显示课程名预览，点击课程查看完整信息",
            "4. 点击垃圾桶可清空当前课表数据"
        )
        guides.forEach { line ->
            card.addView(TextView(this).apply {
                text = line
                setTextColor(Color.parseColor("#FF334155"))
                textSize = 14f
                setPadding(0, dp(10), 0, 0)
            })
        }

        scrim.addView(card)
        dialog.setContentView(scrim)

        scrim.setOnClickListener {
            card.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .alpha(0f)
                .setDuration(150)
                .withEndAction { dialog.dismiss() }
                .start()
        }
        card.setOnClickListener { }

        dialog.show()
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.alpha = 0f
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(220)
            .start()
    }

    private fun resolveDayColumnWidth(dayCount: Int): Int {
        val widthPx = resources.displayMetrics.widthPixels
        val horizontalPaddings = dp(16 * 2 + 8 * 2)
        val totalGap = GRID_GAP * (dayCount + 1)
        val available = widthPx - horizontalPaddings - TIME_COL_WIDTH - totalGap
        return max(dp(MIN_DAY_COL_WIDTH_DP), available / max(1, dayCount))
    }

    private fun resolveCurrentWeekNumber(): Int? {
        val firstWeekDate = storage.loadFirstWeekDate() ?: return null
        val first = startOfDay(firstWeekDate)
        val today = startOfDay(System.currentTimeMillis())
        val diffDays = ((today - first) / DAY_MILLIS).toInt()
        if (diffDays < 0) return null
        return max(1, diffDays / 7 + 1).coerceAtMost(MAX_WEEK)
    }

    private fun isCourseInWeek(weekText: String, week: Int): Boolean {
        if (weekText.isBlank()) return true
        val normalized = weekText
            .replace("，", ",")
            .replace("、", ",")
            .replace(" ", "")

        val matches = WEEK_BLOCK_REGEX.findAll(normalized).toList()
        if (matches.isEmpty()) return matchesWeekRange(normalized, week, suffix = "")

        return matches.any { match ->
            val rangeText = match.groupValues[1]
            val suffix = match.groupValues[2]
            matchesWeekRange(rangeText, week, suffix)
        }
    }

    private fun matchesWeekRange(rangeText: String, week: Int, suffix: String): Boolean {
        val weekHit = rangeText
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .any { token ->
                if ("-" in token) {
                    val (startText, endText) = token.split("-", limit = 2)
                    val start = startText.toIntOrNull()
                    val end = endText.toIntOrNull()
                    start != null && end != null && week in start..end
                } else {
                    token.toIntOrNull() == week
                }
            }

        if (!weekHit) return false

        return when (suffix) {
            "单周" -> week % 2 == 1
            "双周" -> week % 2 == 0
            else -> true
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showFirstWeekDatePicker() {
        val calendar = Calendar.getInstance().apply {
            storage.loadFirstWeekDate()?.let { timeInMillis = it }
        }

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                storage.saveFirstWeekDate(selected.timeInMillis)
                selectedWeek = resolveCurrentWeekNumber()
                val saved = storage.load()
                renderState(saved?.sourceTitle, saved?.courses.orEmpty(), saved?.importedAt)
                TimetableWidgetProvider.refreshAllWidgets(this)
                Toast.makeText(this, "第一周日期已更新", Toast.LENGTH_SHORT).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
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

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val GRID_GAP_DP = 3
        private const val TIME_COL_WIDTH_DP = 66
        private const val MIN_DAY_COL_WIDTH_DP = 62
        private const val HEADER_HEIGHT_DP = 44
        private const val ROW_HEIGHT_DP = 116
        private const val DEFAULT_WEEK = 1
        private const val MIN_WEEK = 1
        private const val MAX_WEEK = 30
        private val WEEK_BLOCK_REGEX = Regex("\\[(.+?)](单周|双周|周)?")
        private val COURSE_COLORS = listOf(
            Color.parseColor("#FF49A7F2"),
            Color.parseColor("#FFEB6A8A"),
            Color.parseColor("#FF67C7A5"),
            Color.parseColor("#FF8869E8"),
            Color.parseColor("#FFE79D45"),
            Color.parseColor("#FFDE6B5C")
        )
        private val DEFAULT_DAYS = listOf(
            1 to "星期一",
            2 to "星期二",
            3 to "星期三",
            4 to "星期四",
            5 to "星期五",
            6 to "星期六",
            7 to "星期日"
        )
        private val DEFAULT_SECTIONS = listOf(
            1 to "第1节",
            2 to "第2节",
            3 to "第3节",
            4 to "第4节",
            5 to "第5节",
            6 to "第6节",
            7 to "第7节",
            8 to "第8节",
            9 to "第9节",
            10 to "第10节",
            11 to "第11节",
            12 to "第12节"
        )
    }

    private val GRID_GAP: Int
        get() = dp(GRID_GAP_DP)

    private val TIME_COL_WIDTH: Int
        get() = dp(TIME_COL_WIDTH_DP)

    private val HEADER_HEIGHT: Int
        get() = dp(HEADER_HEIGHT_DP)

    private val ROW_HEIGHT: Int
        get() = dp(ROW_HEIGHT_DP)

}
