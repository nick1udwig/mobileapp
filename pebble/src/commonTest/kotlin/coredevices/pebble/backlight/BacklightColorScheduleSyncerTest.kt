package coredevices.pebble.backlight

import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class BacklightColorScheduleSyncerTest {
    @Test
    fun refreshSolarMinutesUsesDefaultScheduleWhenWatchTimezoneIsManual() = runBlocking {
        val libPebble = RecordingLibPebble()
        val geolocation = FakeSystemGeolocation()
        libPebble.seedWatchPref(WatchPreference(BoolWatchPref.TimezoneSourceIsManual, true))

        val schedule = syncer(libPebble, geolocation).refreshSolarMinutes()

        assertEquals(SolarSchedule.DEFAULT, schedule)
        assertEquals(0, geolocation.requestCount)
        assertEquals(
            SolarSchedule.DEFAULT_SUNRISE_MINUTE.toLong(),
            prefValue(libPebble, NumberWatchPref.BacklightColorSunriseMinute),
        )
        assertEquals(
            SolarSchedule.DEFAULT_SUNSET_MINUTE.toLong(),
            prefValue(libPebble, NumberWatchPref.BacklightColorSunsetMinute),
        )
    }

    @Test
    fun enableDayNightColorWritesScheduleBeforeEnabling() = runBlocking {
        val libPebble = RecordingLibPebble()
        libPebble.seedWatchPref(WatchPreference(BoolWatchPref.TimezoneSourceIsManual, true))

        syncer(libPebble, FakeSystemGeolocation()).enableDayNightColor()

        assertEquals(
            listOf(
                NumberWatchPref.BacklightColorSunriseMinute.id,
                NumberWatchPref.BacklightColorSunsetMinute.id,
                BoolWatchPref.BacklightColorDayNightEnabled.id,
            ),
            libPebble.writeIds,
        )
    }

    @Test
    fun overlappingScheduleRefreshesAreSerialized() = runBlocking {
        val libPebble = RecordingLibPebble()
        val geolocation = BlockingSystemGeolocation()
        val syncer = syncer(libPebble, geolocation)

        val firstRefresh = async { syncer.refreshSolarMinutes() }
        geolocation.firstRequestStarted.await()

        val secondRefresh = async { syncer.refreshSolarMinutes() }
        yield()

        assertEquals(1, geolocation.requestCount)

        geolocation.releaseFirstRequest.complete(Unit)
        firstRefresh.await()
        secondRefresh.await()

        assertEquals(2, geolocation.requestCount)
        assertEquals(
            listOf(
                NumberWatchPref.BacklightColorSunriseMinute.id,
                NumberWatchPref.BacklightColorSunsetMinute.id,
                NumberWatchPref.BacklightColorSunriseMinute.id,
                NumberWatchPref.BacklightColorSunsetMinute.id,
            ),
            libPebble.writeIds,
        )
    }

    private fun syncer(
        libPebble: LibPebble,
        systemGeolocation: SystemGeolocation,
    ) = BacklightColorScheduleSyncer(
        libPebble = libPebble,
        systemGeolocation = systemGeolocation,
        appResumed = AppResumed(),
        timeChanged = NoopTimeChanged,
        clock = FixedClock(Instant.parse("2026-06-18T12:00:00Z")),
    )

    private suspend fun <T> prefValue(libPebble: LibPebble, pref: WatchPref<T>): T {
        val item = libPebble.watchPrefs.first().first { it.pref == pref }
        return pref.castParent(item).valueOrDefault()
    }
}

private class RecordingLibPebble(
    private val delegate: FakeLibPebble = FakeLibPebble(),
) : LibPebble by delegate {
    val writeIds = mutableListOf<String>()

    fun seedWatchPref(watchPref: WatchPreference<*>) {
        delegate.setWatchPref(watchPref)
    }

    override fun setWatchPref(watchPref: WatchPreference<*>) {
        writeIds += watchPref.pref.id
        delegate.setWatchPref(watchPref)
    }
}

private class FakeSystemGeolocation : SystemGeolocation {
    var requestCount = 0

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult {
        requestCount++
        return GeolocationPositionResult.Success(
            timestamp = Instant.parse("2026-06-18T12:00:00Z"),
            latitude = 34.0522,
            longitude = -118.2437,
            accuracy = null,
            altitude = null,
            heading = null,
            speed = null,
        )
    }

    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean,
    ): Flow<GeolocationPositionResult> {
        return emptyFlow()
    }
}

private class BlockingSystemGeolocation : SystemGeolocation {
    val firstRequestStarted = CompletableDeferred<Unit>()
    val releaseFirstRequest = CompletableDeferred<Unit>()
    var requestCount = 0

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult {
        requestCount++
        if (requestCount == 1) {
            firstRequestStarted.complete(Unit)
            releaseFirstRequest.await()
        }

        return GeolocationPositionResult.Success(
            timestamp = Instant.parse("2026-06-18T12:00:00Z"),
            latitude = 34.0522,
            longitude = -118.2437,
            accuracy = null,
            altitude = null,
            heading = null,
            speed = null,
        )
    }

    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean,
    ): Flow<GeolocationPositionResult> {
        return emptyFlow()
    }
}

private object NoopTimeChanged : TimeChanged {
    override fun registerForTimeChanges(onChanged: () -> Unit) {
    }
}

private class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}
