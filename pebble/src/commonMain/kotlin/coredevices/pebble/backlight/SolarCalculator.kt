package coredevices.pebble.backlight

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Instant

data class SolarSchedule(
    val sunriseMinute: Int,
    val sunsetMinute: Int,
) {
    companion object {
        const val DEFAULT_SUNRISE_MINUTE = 6 * 60
        const val DEFAULT_SUNSET_MINUTE = 18 * 60

        val DEFAULT = SolarSchedule(DEFAULT_SUNRISE_MINUTE, DEFAULT_SUNSET_MINUTE)
    }
}

object SolarCalculator {
    private const val ZENITH_OFFICIAL = 90.833
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000
    private val utcTimeZone = TimeZone.of("UTC")

    fun scheduleFor(
        date: LocalDate,
        timeZone: TimeZone,
        latitude: Double,
        longitude: Double,
    ): SolarSchedule? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return null
        }

        val sunrise = eventMinuteOfDay(
            date = date,
            timeZone = timeZone,
            latitude = latitude,
            longitude = longitude,
            isSunrise = true,
        ) ?: return null
        val sunset = eventMinuteOfDay(
            date = date,
            timeZone = timeZone,
            latitude = latitude,
            longitude = longitude,
            isSunrise = false,
        ) ?: return null

        return SolarSchedule(sunrise, sunset).takeIf { it.sunriseMinute != it.sunsetMinute }
    }

    private fun eventMinuteOfDay(
        date: LocalDate,
        timeZone: TimeZone,
        latitude: Double,
        longitude: Double,
        isSunrise: Boolean,
    ): Int? {
        val dayOfYear = date.dayOfYear()
        val longitudeHour = longitude / 15.0
        val approximateHour = if (isSunrise) 6.0 else 18.0
        val approximateTime = dayOfYear + ((approximateHour - longitudeHour) / 24.0)

        val meanAnomaly = (0.9856 * approximateTime) - 3.289
        val trueLongitude = normalizeDegrees(
            meanAnomaly +
                    (1.916 * sinDeg(meanAnomaly)) +
                    (0.020 * sinDeg(2.0 * meanAnomaly)) +
                    282.634
        )

        var rightAscension = normalizeDegrees(atanDeg(0.91764 * tanDeg(trueLongitude)))
        val trueLongitudeQuadrant = floor(trueLongitude / 90.0) * 90.0
        val rightAscensionQuadrant = floor(rightAscension / 90.0) * 90.0
        rightAscension = (rightAscension + trueLongitudeQuadrant - rightAscensionQuadrant) / 15.0

        val sinDeclination = 0.39782 * sinDeg(trueLongitude)
        val cosDeclination = cos(asin(sinDeclination))
        val cosHourAngle = (
                cosDeg(ZENITH_OFFICIAL) -
                        (sinDeclination * sinDeg(latitude))
                ) / (cosDeclination * cosDeg(latitude))

        if (cosHourAngle > 1.0 || cosHourAngle < -1.0) {
            return null
        }

        val hourAngle = (if (isSunrise) {
            360.0 - acosDeg(cosHourAngle)
        } else {
            acosDeg(cosHourAngle)
        }) / 15.0

        val localMeanTime = hourAngle + rightAscension - (0.06571 * approximateTime) - 6.622
        val utcMinutesFromDateMidnight = ((localMeanTime - longitudeHour) * 60.0).roundToInt()

        val utcMidnightMs = date.atStartOfDayIn(utcTimeZone).toEpochMilliseconds()
        val eventInstant = Instant.fromEpochMilliseconds(
            utcMidnightMs + utcMinutesFromDateMidnight * 60_000L
        )
        return minuteOfRequestedLocalDate(eventInstant, date, timeZone)
    }

    private fun minuteOfRequestedLocalDate(
        eventInstant: Instant,
        date: LocalDate,
        timeZone: TimeZone,
    ): Int? {
        var adjustedInstant = eventInstant
        repeat(3) {
            val local = adjustedInstant.toLocalDateTime(timeZone)
            if (local.date == date) {
                return local.hour * 60 + local.minute
            }

            val direction = if (local.date < date) 1 else -1
            adjustedInstant = Instant.fromEpochMilliseconds(
                adjustedInstant.toEpochMilliseconds() + direction * MS_PER_DAY
            )
        }

        return null
    }

    private fun LocalDate.dayOfYear(): Int {
        val monthLengths = intArrayOf(31, if (isLeapYear(year)) 29 else 28, 31, 30, 31, 30,
            31, 31, 30, 31, 30, 31)
        return monthLengths.take(monthNumber - 1).sum() + dayOfMonth
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    private fun normalizeDegrees(degrees: Double): Double {
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    private fun sinDeg(degrees: Double) = sin(degrees.toRadians())
    private fun cosDeg(degrees: Double) = cos(degrees.toRadians())
    private fun tanDeg(degrees: Double) = tan(degrees.toRadians())
    private fun acosDeg(value: Double) = acos(value).toDegrees()
    private fun atanDeg(value: Double) = atan(value).toDegrees()
    private fun Double.toRadians() = this * PI / 180.0
    private fun Double.toDegrees() = this * 180.0 / PI
}
