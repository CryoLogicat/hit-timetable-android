package com.hit.timetable.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hit.timetable.R
import com.hit.timetable.data.CourseItem

class CourseAdapter : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {
    private val items = mutableListOf<CourseItem>()

    fun submitList(data: List<CourseItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_course, parent, false)
        return CourseViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvCourseTitle)
        private val tvSub: TextView = itemView.findViewById(R.id.tvCourseSubtitle)

        fun bind(item: CourseItem) {
            tvTitle.text = "${item.dayName} ${item.sectionLabel} · ${item.courseName}"
            val info = listOfNotNull(
                item.teacher.takeIf { it.isNotBlank() },
                item.weekText.takeIf { it.isNotBlank() },
                item.location.takeIf { it.isNotBlank() }
            ).joinToString("  ")
            tvSub.text = info
        }
    }
}
