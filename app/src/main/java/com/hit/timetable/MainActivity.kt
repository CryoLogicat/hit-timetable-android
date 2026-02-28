package com.hit.timetable

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hit.timetable.data.CourseItem
import com.hit.timetable.data.TimetableStorage
import com.hit.timetable.parser.HitTimetableXlsParser
import com.hit.timetable.ui.CourseAdapter
import com.hit.timetable.widget.TimetableWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var tvSource: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvWeekInfo: TextView
    private lateinit var rvCourses: RecyclerView
    private lateinit var adapter: CourseAdapter

    private val storage by lazy { TimetableStorage(this) }
    private val parser by lazy { HitTimetableXlsParser() }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handleImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSource = findViewById(R.id.tvSource)
        tvStats = findViewById(R.id.tvStats)
        tvWeekInfo = findViewById(R.id.tvWeekInfo)
        rvCourses = findViewById(R.id.rvCourses)

        adapter = CourseAdapter()
        rvCourses.layoutManager = LinearLayoutManager(this)
        rvCourses.adapter = adapter

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/vnd.ms-excel", "*/*"))
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            storage.clear()
            renderState(null, emptyList())
            TimetableWidgetProvider.refreshAllWidgets(this)
            Toast.makeText(this, "已清空课表", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSetFirstWeek).setOnClickListener {
            showFirstWeekDatePicker()
        }

        val saved = storage.load()
        renderState(saved?.sourceTitle, saved?.courses.orEmpty(), saved?.importedAt)
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
                renderState(data.sourceTitle, data.courses, data.importedAt)
                TimetableWidgetProvider.refreshAllWidgets(this@MainActivity)
                Toast.makeText(this@MainActivity, "导入成功，共 ${data.courses.size} 条课程", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderState(source: String?, courses: List<CourseItem>, importedAt: Long? = null) {
        tvWeekInfo.text = buildWeekInfoText()

        if (source.isNullOrBlank()) {
            tvSource.text = "来源：未导入"
            tvStats.text = "请导入哈工大本部课程表（.xls）"
            adapter.submitList(emptyList())
            return
        }

        tvSource.text = "来源：$source"
        val dayCount = courses.map { it.dayIndex }.distinct().size
        val time = importedAt?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))
        } ?: "-"
        tvStats.text = "课程数：${courses.size}   覆盖天数：$dayCount   导入时间：$time"

        val sorted = courses.sortedWith(compareBy<CourseItem> { it.dayIndex }.thenBy { it.sectionOrder })
        adapter.submitList(sorted)
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
                tvWeekInfo.text = buildWeekInfoText()
                TimetableWidgetProvider.refreshAllWidgets(this)
                Toast.makeText(this, "第一周日期已设置为 ${formatDate(selected.timeInMillis)}", Toast.LENGTH_SHORT).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun buildWeekInfoText(): String {
        val firstWeekDate = storage.loadFirstWeekDate() ?: return "第一周日期：未设置"
        val first = startOfDay(firstWeekDate)
        val today = startOfDay(System.currentTimeMillis())
        val diffDays = ((today - first) / DAY_MILLIS).toInt()

        return if (diffDays < 0) {
            "第一周日期：${formatDate(firstWeekDate)}（课程未开始）"
        } else {
            val week = max(1, diffDays / 7 + 1)
            "第一周日期：${formatDate(firstWeekDate)}（当前第${week}周）"
        }
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

    private fun formatDate(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timeMillis))
    }

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
