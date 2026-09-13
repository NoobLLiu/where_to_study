package com.nemoyu.wheretostudy.nativeapp

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 魔改（肇庆学院）：极简个人课表页。
 * 仅依赖课表仓库与本地偏好；显示学期信息、今日课程与本周课表。
 * 原「空教室联动查询」（PlannerPage）已停用。
 */
class SchedulePage(
    private val activity: MainActivity,
    private val scheduleRepository: ScheduleRepository,
    private val preferences: AppPreferences,
    private val availableWidthDp: Int,
    private val usesBottomNavigation: Boolean,
) {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val isCompact: Boolean
        get() = availableWidthDp < AdaptiveLayoutLogic.MEDIUM_BREAKPOINT_DP

    fun build(): ScrollView {
        val schedule = scheduleRepository.schedule
        val now = Calendar.getInstance(shanghai)
        val weekNumber = ScheduleLogic.weekNumber(schedule, now)
        val weekday = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val todayCourses = ScheduleLogic.courses(schedule, now)

        return ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            setThemeBackgroundColor { Palette.background }
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            addView(verticalPage(activity).apply {
                if (usesBottomNavigation) {
                    setPadding(
                        paddingLeft,
                        paddingTop,
                        paddingRight,
                        activity.dp(PhoneNavigationLayoutLogic.CONTENT_INSET_DP),
                    )
                }
                addView(compactTitle(now, weekNumber, weekday))
                addSection(summarySurface(schedule, now, weekNumber, weekday))
                addSection(todaySurface(todayCourses, schedule, weekNumber))
                if (schedule != null && weekNumber != null) {
                    addSection(weekSurface(schedule, weekNumber))
                }
            })
        }
    }

    private fun LinearLayout.addSection(view: LinearLayout) {
        addView(view)
        addView(
            spacer(
                activity,
                if (isCompact) UiMetrics.phoneSectionSpacingDp else UiMetrics.sectionSpacingDp,
            ),
        )
    }

    private fun sectionSurface(): LinearLayout =
        surface(activity, showsBorder = !isCompact, compact = isCompact)

    private fun compactTitle(
        now: Calendar,
        weekNumber: Int?,
        weekday: Int,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, activity.dp(UiMetrics.phoneSectionSpacingDp))
        addView(TextView(activity).apply {
            text = activity.getString(R.string.planner_eyebrow)
            textSize = 12f
            setThemeTextColor { Palette.muted }
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        })
        addView(TextView(activity).apply {
            text = "个人课表"
            textSize = UiMetrics.phonePageTitleSizeSp
            setThemeTextColor { Palette.text }
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, activity.dp(3), 0, 0)
        })
        addView(TextView(activity).apply {
            text = subtitleText(now, weekNumber, weekday)
            textSize = 14f
            setThemeTextColor { Palette.muted }
            setPadding(0, activity.dp(5), 0, 0)
        })
    }

    private fun subtitleText(now: Calendar, weekNumber: Int?, weekday: Int): String {
        val dateText = SimpleDateFormat("M月d日", Locale.CHINA).apply {
            timeZone = shanghai
        }.format(now.time)
        val weekPart = weekNumber?.let { "第${it}周" } ?: "非教学周"
        return "$weekPart · ${weekdayName(weekday)} · $dateText"
    }

    private fun summarySurface(
        schedule: ScheduleSnapshot?,
        now: Calendar,
        weekNumber: Int?,
        weekday: Int,
    ): LinearLayout = sectionSurface().apply {
        addView(sectionTitle(activity, "学期信息"))
        val termID = schedule?.termID?.takeIf { it.isNotBlank() }
            ?: preferences.termID.ifBlank { AppMetadata.defaultTermID }
        addView(infoRow("学期", termID.ifBlank { "未设置" }))
        val termStartDate = schedule?.termStartDate?.takeIf { it.isNotBlank() }
            ?: preferences.termStartDate.ifBlank { AppMetadata.defaultTermStartDate }
        addView(infoRow("第一周周一", termStartDate.ifBlank { "未知" }))
        addView(infoRow("今天", subtitleText(now, weekNumber, weekday)))
        if (schedule == null) {
            addView(TextView(activity).apply {
                text = "尚未获取课表：请在「设置」中填写学号与教务 Cookie，" +
                    "然后点击「获取个人课表」。"
                textSize = 13f
                setThemeTextColor { Palette.muted }
                setPadding(0, activity.dp(10), 0, 0)
            })
        }
    }

    private fun infoRow(label: String, value: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(6), 0, 0)
            addView(TextView(activity).apply {
                text = label
                textSize = 13f
                setThemeTextColor { Palette.muted }
                layoutParams = LinearLayout.LayoutParams(activity.dp(92), WRAP_CONTENT_HEIGHT)
            })
            addView(TextView(activity).apply {
                text = value
                textSize = 14f
                setThemeTextColor { Palette.text }
                setTypeface(typeface, Typeface.BOLD)
            })
        }

    private fun todaySurface(
        todayCourses: List<Course>,
        schedule: ScheduleSnapshot?,
        weekNumber: Int?,
    ): LinearLayout = sectionSurface().apply {
        addView(sectionTitle(activity, "今日课程"))
        when {
            schedule == null -> addView(emptyHint("暂无课表数据。"))
            weekNumber == null -> addView(emptyHint("今天不在本学期教学周内。"))
            todayCourses.isEmpty() -> addView(emptyHint("今天没有课程。"))
            else -> todayCourses.forEach { course -> addView(courseCard(course)) }
        }
    }

    private fun weekSurface(schedule: ScheduleSnapshot, weekNumber: Int): LinearLayout =
        sectionSurface().apply {
            addView(sectionTitle(activity, "本周课程（第${weekNumber}周）"))
            (1..7).forEach { day ->
                val dayCourses = schedule.courses
                    .filter { it.weekday == day && weekNumber in it.weekNumbers }
                    .sortedWith(compareBy(Course::startSlot, Course::name))
                addView(dayHeader(schedule, weekNumber, day))
                if (dayCourses.isEmpty()) {
                    addView(emptyHint("无课"))
                } else {
                    dayCourses.forEach { course -> addView(courseCard(course)) }
                }
            }
        }

    private fun dayHeader(schedule: ScheduleSnapshot, weekNumber: Int, day: Int): TextView {
        val parsed = strictDate(schedule.termStartDate)
        val dateText = parsed?.let { date ->
            val calendar = Calendar.getInstance(shanghai).apply {
                time = date
                add(Calendar.DAY_OF_MONTH, (weekNumber - 1) * 7 + (day - 1))
            }
            SimpleDateFormat("M/d", Locale.CHINA).apply {
                timeZone = shanghai
            }.format(calendar.time)
        }.orEmpty()
        return TextView(activity).apply {
            text = if (dateText.isBlank()) weekdayName(day) else "${weekdayName(day)} $dateText"
            textSize = 14f
            setThemeTextColor { Palette.primaryText }
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, activity.dp(12), 0, activity.dp(6))
        }
    }

    private fun courseCard(course: Course): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = themedRoundedBackground(
            activity,
            { Palette.surfaceVariant },
            radius = if (isCompact) UiMetrics.phoneControlRadiusDp else UiMetrics.controlRadiusDp,
        )
        setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            WRAP_CONTENT_HEIGHT,
        ).apply { topMargin = activity.dp(2) }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(activity.dp(64), WRAP_CONTENT_HEIGHT)
            addView(TextView(activity).apply {
                text = slotTimeText(course)
                textSize = 13f
                setThemeTextColor { Palette.primaryText }
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = course.sectionText
                textSize = 11f
                setThemeTextColor { Palette.muted }
                setPadding(0, activity.dp(2), 0, 0)
            })
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                WRAP_CONTENT_HEIGHT,
                1f,
            ).apply { marginStart = activity.dp(10) }
            addView(TextView(activity).apply {
                text = course.name
                textSize = 15f
                setThemeTextColor { Palette.text }
                setTypeface(typeface, Typeface.BOLD)
            })
            val details = listOf(course.teacher, course.room)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (details.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = details
                    textSize = 13f
                    setThemeTextColor { Palette.muted }
                    setPadding(0, activity.dp(3), 0, 0)
                })
            }
        })
    }

    private fun slotTimeText(course: Course): String {
        val slots = AppMetadata.slots
        val start = slots.getOrNull(course.startSlot)?.start
        val end = slots.getOrNull(course.endSlot.coerceIn(0, slots.lastIndex))?.end
        return if (start != null && end != null) "$start-$end" else course.timeRange
    }

    private fun emptyHint(message: String): TextView = TextView(activity).apply {
        text = message
        textSize = 13f
        setThemeTextColor { Palette.muted }
        setPadding(0, activity.dp(2), 0, 0)
    }

    private fun weekdayName(weekday: Int): String = when (weekday) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    private fun strictDate(value: String): Date? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = shanghai
            isLenient = false
        }.parse(value.trim())
    }.getOrNull()

    private companion object {
        const val WRAP_CONTENT_HEIGHT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
