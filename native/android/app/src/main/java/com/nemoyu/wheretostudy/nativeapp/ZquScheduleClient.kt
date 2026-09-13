package com.nemoyu.wheretostudy.nativeapp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val ZQU_MAX_WEEKS = 25
private const val ZQU_MAX_RESPONSE_BYTES = 3 * 1024 * 1024

// 肇庆学院（乘方教务系统）对接层。
//
// 数据来源：GET {baseURL}/xsbjkbcx!xskbList2.action?xnxqdm=YYYY0S&bjdm=班级代码&zc=周次
// 返回 easyui datagrid HTML 页面，数据行为 <td field="..."> 结构，字段含义：
//   kcmc 课程名称 | jxbmc 班级名称 | pkrs 人数 | teaxms 教师 | zc 周次 |
//   xq 星期(1=周一) | jcdm 节次代码(如 030405 = 第3/4/5节) | jxcdmc 上课地点 |
//   pkrq 排课日期 | kxh 课序 | jxhjmc 类型 | sknrjj 授课内容简介
//
// 凭据复用约定（零改动复用 SecureCredentialStore）：
//   Credentials.account = 学号（任意非空标识）
//   Credentials.password = 浏览器复制的教务系统 Cookie 请求头（可含 WebVPN 域的完整 Cookie）
//
// 学期参数：termID "2026-2027-1" <-> xnxqdm "202601"（学年开始年 + 学期号）。
// 学期第一周周一由 pkrq 反推：pkrq − (zc−1)×7 − (xq−1) 天。
class ZquScheduleClient(
    private val preferences: AppPreferences? = null,
) {
    fun fetch(
        credentials: Credentials,
        fallbackTermID: String,
        fallbackTermStartDate: String,
    ): ScheduleSnapshot {
        val cookie = credentials.password.trim()
        if (cookie.isEmpty()) {
            throw ScheduleClientException("请先在设置中填写教务系统 Cookie。")
        }
        val baseURL = (preferences?.jwglBaseURL ?: DEFAULT_BASE_URL).trim().trimEnd('/')
        if (baseURL.isEmpty()) {
            throw ScheduleClientException("请先在设置中填写教务系统地址。")
        }
        if (!baseURL.startsWith("https://") && !baseURL.startsWith("http://")) {
            throw ScheduleClientException("教务系统地址必须以 http(s):// 开头。")
        }
        val classCode = (preferences?.classCode ?: DEFAULT_CLASS_CODE).trim()
        if (classCode.isEmpty()) {
            throw ScheduleClientException("请先在设置中填写班级代码。")
        }
        val xnxqdm = xnxqdmFromTermID(fallbackTermID)
            ?: throw ScheduleClientException("学期编号格式不正确，请使用 YYYY-YYYY-1/2。")

        val rows = mutableListOf<Map<String, String>>()
        var consecutiveEmptyWeeks = 0
        var seenData = false
        for (week in 1..ZQU_MAX_WEEKS) {
            val html = fetchWeek(baseURL, cookie, xnxqdm, classCode, week)
            val weekRows = ZquScheduleParser.extractRows(html)
            if (weekRows.isEmpty()) {
                // 假期整周（如国庆）可能整周无课，连续两周为空才视为学期结束。
                if (seenData) {
                    consecutiveEmptyWeeks += 1
                    if (consecutiveEmptyWeeks >= 2) break
                }
            } else {
                seenData = true
                consecutiveEmptyWeeks = 0
                rows += weekRows
            }
        }
        if (!seenData) {
            throw ScheduleClientException("未获取到课表数据，请检查 Cookie、学期与班级设置。")
        }
        return ZquScheduleParser.buildSnapshot(
            rows = rows,
            xnxqdm = xnxqdm,
            fallbackTermID = fallbackTermID,
            fallbackTermStartDate = fallbackTermStartDate,
        )
    }

    private fun fetchWeek(
        baseURL: String,
        cookie: String,
        xnxqdm: String,
        classCode: String,
        week: Int,
    ): String {
        val query = listOf("xnxqdm", "bjdm", "zc").joinToString("&") { key ->
            val value = when (key) {
                "xnxqdm" -> xnxqdm
                "bjdm" -> classCode
                else -> week.toString()
            }
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        val connection = URL("$baseURL/xsbjkbcx!xskbList2.action?$query").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = readBody(stream)
            if (status !in 200..399) {
                throw ScheduleClientException(
                    "教务系统请求失败，HTTP $status。",
                    retryable = status == 408 || status == 429 || status >= 500,
                )
            }
            if (!body.contains("datagrid")) {
                // WebVPN/教务会话失效时会重定向到登录页。
                if (body.contains("登录") || body.contains("login")) {
                    throw ScheduleClientException("教务系统 Cookie 已失效，请在浏览器中重新登录教务系统后更新 Cookie。")
                }
                throw ScheduleClientException("教务系统返回了无法识别的页面。")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > ZQU_MAX_RESPONSE_BYTES) {
                    throw ScheduleClientException("教务系统返回的数据超过大小限制。")
                }
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://jwgl.zqu.edu.cn"
        const val DEFAULT_CLASS_CODE = "114334763"

        fun xnxqdmFromTermID(termID: String): String? {
            val match = Regex("^(\\d{4})-(\\d{4})-([12])$").find(termID.trim()) ?: return null
            return "${match.groupValues[1]}${match.groupValues[3]}"
        }

        fun termIDFromXnxqdm(xnxqdm: String): String? {
            val value = xnxqdm.trim().toIntOrNull() ?: return null
            if (value !in 100000..999999) return null
            val year = value / 10
            val semester = value % 10
            if (semester !in 1..2) return null
            return "$year-${year + 1}-$semester"
        }
    }
}

object ZquScheduleParser {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val rowRegex = Regex("<tr[^>]*datagrid-row-index[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val cellRegex = Regex("<td[^>]*field=\"(\\w+)\"[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("<[^>]*>")
    private val whitespaceRegex = Regex("\\s+")
    private val entityRegex = Regex("&(\\w+|#\\d+);")

    fun extractRows(html: String): List<Map<String, String>> = rowRegex.findAll(html).map { row ->
        cellRegex.findAll(row.groupValues[1]).associate { cell ->
            cell.groupValues[1] to plainText(cell.groupValues[2])
        }
    }.toList()

    fun buildSnapshot(
        rows: List<Map<String, String>>,
        xnxqdm: String,
        fallbackTermID: String,
        fallbackTermStartDate: String,
        fetchedAt: String = timestamp(Date()),
    ): ScheduleSnapshot {
        val termID = ZquScheduleClient.termIDFromXnxqdm(xnxqdm) ?: fallbackTermID
        val termStartDate = inferTermStartDate(rows) ?: fallbackTermStartDate
        val seen = mutableSetOf<String>()
        val courses = rows.mapNotNull(::parseCourse).filter { seen.add(it.id) }
            .sortedWith(compareBy(Course::weekday, Course::startSlot, Course::name))
        return ScheduleSnapshot(termID, termStartDate, fetchedAt, courses)
    }

    internal fun parseCourse(row: Map<String, String>): Course? {
        val week = row["zc"].orEmpty().trim().toIntOrNull() ?: return null
        val weekday = row["xq"].orEmpty().trim().toIntOrNull() ?: return null
        if (week !in 1..ZQU_MAX_WEEKS || weekday !in 1..7) return null
        val slotNumbers = slotNumbers(row["jcdm"].orEmpty())
        val startSlot = (slotNumbers.minOrNull() ?: return null) - 1
        val endSlot = (slotNumbers.maxOrNull() ?: return null) - 1
        if (startSlot < 0 || endSlot < startSlot || endSlot >= AppMetadata.slots.size) return null
        val name = row["kcmc"].orEmpty().trim().ifEmpty { "未命名课程" }
        val teacher = row["teaxms"].orEmpty().trim()
        val room = row["jxcdmc"].orEmpty().trim()
        val stable = listOf(
            name,
            row["jxbmc"].orEmpty().trim(),
            teacher,
            room,
            week.toString(),
            weekday.toString(),
            row["jcdm"].orEmpty().trim(),
            row["pkrq"].orEmpty().trim(),
            row["kxh"].orEmpty().trim(),
        ).joinToString("|")
        val id = MessageDigest.getInstance("SHA-1")
            .digest(stable.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(12)
        return Course(
            id = id,
            name = name,
            teacher = teacher,
            room = room,
            weekText = "第${week}周",
            weekNumbers = listOf(week),
            examWeekNumbers = emptyList(),
            weekday = weekday,
            startSlot = startSlot,
            endSlot = endSlot,
            sectionText = "${startSlot + 1}-${endSlot + 1}节",
            timeRange = "${AppMetadata.slots[startSlot].start}-${AppMetadata.slots[endSlot].end}",
            sourceCourseID = null,
        )
    }

    // 节次代码两位一组：030405 -> 3,4,5；0102 -> 1,2；080910 -> 8,9,10。
    private fun slotNumbers(code: String): List<Int> {
        val digits = code.trim().filter(Char::isDigit)
        if (digits.isEmpty()) return emptyList()
        return if (digits.length % 2 == 0) {
            digits.chunked(2).mapNotNull(String::toIntOrNull)
        } else {
            digits.mapNotNull(Char::digitToIntOrNull)
        }
    }

    private fun inferTermStartDate(rows: List<Map<String, String>>): String? {
        for (row in rows) {
            val dateText = row["pkrq"].orEmpty().trim()
            if (dateText.isEmpty()) continue
            val week = row["zc"].orEmpty().trim().toIntOrNull() ?: continue
            val weekday = row["xq"].orEmpty().trim().toIntOrNull() ?: continue
            if (week < 1 || weekday !in 1..7) continue
            val day = parseDate(dateText) ?: continue
            day.add(Calendar.DAY_OF_MONTH, -(weekday - 1) - ((week - 1) * 7))
            return contractDate().format(day.time)
        }
        return null
    }

    internal fun plainText(value: String): String = decodeEntities(
        whitespaceRegex.replace(tagRegex.replace(value, " "), " ").trim(),
    )

    private fun decodeEntities(value: String): String = entityRegex.replace(value) { match ->
        when (val token = match.groupValues[1].lowercase(Locale.ROOT)) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                if (token.startsWith("#")) {
                    token.drop(1).toIntOrNull()?.toChar()?.toString() ?: match.value
                } else {
                    match.value
                }
            }
        }
    }

    private fun parseDate(value: String): Calendar? = runCatching {
        Calendar.getInstance(shanghai).apply { time = contractDate().parse(value) }
    }.getOrNull()

    private fun contractDate(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = shanghai
        isLenient = false
    }

    private fun timestamp(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = shanghai
    }.format(date)
}
