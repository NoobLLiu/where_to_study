package com.nemoyu.wheretostudy.nativeapp

import java.util.Calendar
import java.util.TimeZone

data class SlotMetadata(
    val index: Int,
    val label: String,
    val start: String,
    val end: String,
)

data class CampusMetadata(
    val id: String,
    val name: String,
)

data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val room: String,
    val weekText: String,
    val weekNumbers: List<Int>,
    val examWeekNumbers: List<Int>,
    val weekday: Int,
    val startSlot: Int,
    val endSlot: Int,
    val sectionText: String,
    val timeRange: String,
    val sourceCourseID: String? = null,
)

data class ScheduleSnapshot(
    val termID: String,
    val termStartDate: String,
    val fetchedAt: String,
    val courses: List<Course>,
)

data class Classroom(
    val id: String,
    val building: String,
    val room: String,
    val name: String,
    val size: Int?,
    val type: String,
    val availableSlots: List<Int>,
    val source: String,
)

data class CampusClassrooms(
    val campusID: String,
    val campusName: String,
    val targetDate: String,
    val fetchedAt: String,
    val realtime: Boolean,
    val provider: String,
    val rooms: List<Classroom>,
)

data class ClassroomsCache(
    val cacheVersion: Int,
    val targetDate: String,
    val fetchedAt: String,
    val realtime: Boolean,
    val provider: String,
    val campuses: List<CampusClassrooms>,
)

data class HolidayItem(
    val date: String,
    val name: String,
    val type: String,
)

data class HolidaysSnapshot(
    val year: Int,
    val source: String,
    val fetchedAt: String,
    val items: List<HolidayItem>,
)

object AppMetadata {
    const val classroomsCacheVersion = 2

    // 魔改（肇庆学院）：默认学期与第一周周一（兜底值；成功抓取后会被排课日期反推覆盖）。
    const val defaultTermID = "2026-2027-1"
    const val defaultTermStartDate = "2026-08-31"

    // 魔改（肇庆学院）：单校区。
    val campuses = listOf(
        CampusMetadata(id = "01", name = "肇庆学院"),
    )

    // 魔改（肇庆学院）：空教室查询已停用，教学楼清单不再使用。
    private val buildingsByCampusID = mapOf(
        "01" to emptyList<String>(),
    )

    fun buildings(campusID: String): List<String> = buildingsByCampusID[campusID].orEmpty()

    // 魔改（肇庆学院）：14 节作息时间表（来源：肇庆学院教务部）。
    val slots = listOf(
        SlotMetadata(0, "1", "08:00", "08:40"),
        SlotMetadata(1, "2", "08:50", "09:30"),
        SlotMetadata(2, "3", "09:50", "10:30"),
        SlotMetadata(3, "4", "10:40", "11:20"),
        SlotMetadata(4, "5", "11:30", "12:10"),
        SlotMetadata(5, "6", "14:30", "15:10"),
        SlotMetadata(6, "7", "15:20", "16:00"),
        SlotMetadata(7, "8", "16:15", "16:55"),
        SlotMetadata(8, "9", "17:05", "17:45"),
        SlotMetadata(9, "10", "17:55", "18:35"),
        SlotMetadata(10, "11", "19:00", "19:40"),
        SlotMetadata(11, "12", "19:50", "20:30"),
        SlotMetadata(12, "13", "20:40", "21:20"),
        SlotMetadata(13, "14", "21:30", "22:10"),
    )
}

object HolidayMetadata {
    const val source = "https://unpkg.com/holiday-calendar@1.3.3/data/CN"
    const val fallbackSource =
        "https://www.gov.cn/yaowen/liebiao/202511/content_7047099.htm"
    const val minimumYear = 1900
    const val maximumYear = 2100
    const val refreshIntervalMillis = 7L * 24L * 60L * 60L * 1_000L
}

object ScheduleLogic {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    fun weekNumber(termStart: Calendar, target: Calendar): Int {
        val start = startOfDay(termStart)
        val day = startOfDay(target)
        val elapsedDays = Math.floorDiv(day.timeInMillis - start.timeInMillis, MILLIS_PER_DAY)
        if (elapsedDays < 0) return 0
        return Math.floorDiv(elapsedDays.toInt(), 7) + 1
    }

    fun courses(
        schedule: ScheduleSnapshot?,
        target: Calendar,
    ): List<Course> {
        schedule ?: return emptyList()
        val start = parseContractDate(schedule.termStartDate) ?: return emptyList()
        val week = weekNumber(start, target)
        val weekday = ((target.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        return schedule.courses
            .filter { it.weekday == weekday && week in it.weekNumbers }
            .sortedWith(compareBy(Course::startSlot, Course::name))
    }

    fun weekNumber(
        schedule: ScheduleSnapshot?,
        target: Calendar,
    ): Int? {
        schedule ?: return null
        val start = parseContractDate(schedule.termStartDate) ?: return null
        val maximumWeek = schedule.courses
            .flatMap(Course::weekNumbers)
            .filter { it > 0 }
            .maxOrNull()
            ?: return null
        return weekNumber(start, target).takeIf { it in 1..maximumWeek }
    }

    fun busySlots(
        schedule: ScheduleSnapshot?,
        target: Calendar,
    ): Set<Int> = courses(schedule, target)
        .flatMap { it.startSlot..it.endSlot }
        .filter { it in AppMetadata.slots.indices }
        .toSet()

    private fun startOfDay(source: Calendar): Calendar = Calendar.getInstance(shanghai).apply {
        set(source.get(Calendar.YEAR), source.get(Calendar.MONTH), source.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun parseContractDate(value: String): Calendar? {
        val parts = value.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return null
        val date = Calendar.getInstance(shanghai).apply {
            isLenient = false
            set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return runCatching { date.timeInMillis }.map { date }.getOrNull()
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
