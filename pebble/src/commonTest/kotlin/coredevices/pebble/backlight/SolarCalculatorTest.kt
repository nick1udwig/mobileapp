package coredevices.pebble.backlight

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SolarCalculatorTest {
    @Test
    fun scheduleForAnchorsSunriseToRequestedLocalDateBeforeDstStarts() {
        val schedule = assertNotNull(
            SolarCalculator.scheduleFor(
                date = LocalDate(2026, 3, 7),
                timeZone = TimeZone.of("America/Los_Angeles"),
                latitude = 34.0522,
                longitude = -118.2437,
            )
        )

        assertTrue(schedule.sunriseMinute in minuteOfDay(6, 0)..minuteOfDay(6, 30))
        assertTrue(schedule.sunsetMinute in minuteOfDay(17, 40)..minuteOfDay(18, 10))
    }

    @Test
    fun scheduleForAnchorsSunsetToRequestedLocalDateBeforeDstStarts() {
        val schedule = assertNotNull(
            SolarCalculator.scheduleFor(
                date = LocalDate(2026, 3, 28),
                timeZone = TimeZone.of("Europe/Berlin"),
                latitude = 52.5200,
                longitude = 13.4050,
            )
        )

        assertTrue(schedule.sunriseMinute in minuteOfDay(5, 30)..minuteOfDay(6, 10))
        assertTrue(schedule.sunsetMinute in minuteOfDay(18, 20)..minuteOfDay(18, 50))
    }

    @Test
    fun scheduleForAllowsDaylightIntervalsThatWrapAroundMidnight() {
        val schedule = assertNotNull(
            SolarCalculator.scheduleFor(
                date = LocalDate(2026, 6, 21),
                timeZone = TimeZone.of("Atlantic/Reykjavik"),
                latitude = 64.1466,
                longitude = -21.9426,
            )
        )

        assertTrue(schedule.sunriseMinute in minuteOfDay(2, 30)..minuteOfDay(3, 30))
        assertTrue(schedule.sunsetMinute in minuteOfDay(0, 0)..minuteOfDay(0, 30))
        assertTrue(schedule.sunriseMinute > schedule.sunsetMinute)
    }

    private fun minuteOfDay(hour: Int, minute: Int): Int {
        return hour * 60 + minute
    }
}
