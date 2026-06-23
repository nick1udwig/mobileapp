package coredevices.pebble.backlight

import co.touchlab.kermit.Logger
import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BacklightColorScheduleSyncer(
    private val libPebble: LibPebble,
    private val systemGeolocation: SystemGeolocation,
    private val appResumed: AppResumed,
    private val timeChanged: TimeChanged,
    private val clock: Clock,
) {
    private val initialized = atomic(false)
    private val refreshMutex = Mutex()
    private val logger = Logger.withTag("BacklightColorScheduleSyncer")

    fun init() {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        GlobalScope.launch {
            refreshIfEnabled("init")
        }
        GlobalScope.launch {
            appResumed.appResumed.collect {
                refreshIfEnabled("app-resumed")
            }
        }
        GlobalScope.launch {
            libPebble.watches
                .map { watches ->
                    watches
                        .filterIsInstance<KnownPebbleDevice>()
                        .filter { it.supportsBacklightColorSchedule() }
                        .map { Triple(it.serial, it.lastConnected, it is CommonConnectedDevice) }
                        .toSet()
                }
                .distinctUntilChanged()
                .collect {
                    refreshIfEnabled("watch-changed")
                }
        }
        GlobalScope.launch {
            libPebble.watchPrefs
                .map { prefs -> prefs.scheduleRelevantPrefs() }
                .distinctUntilChanged()
                .drop(1)
                .collect { prefs ->
                    if (prefs.dayNightColorEnabled) {
                        refreshIfEnabled("watch-schedule-prefs-changed")
                    }
                }
        }
        timeChanged.registerForTimeChanges {
            GlobalScope.launch {
                refreshIfEnabled("time-changed")
            }
        }
        GlobalScope.launch {
            refreshAtLocalMidnightLoop()
        }
    }

    suspend fun enableDayNightColor() {
        refreshSolarMinutes()
        writeWatchPref(BoolWatchPref.BacklightColorDayNightEnabled, true)
    }

    fun enableDayNightColorInBackground() {
        GlobalScope.launch {
            enableDayNightColor()
        }
    }

    fun disableDayNightColor() {
        libPebble.setWatchPref(
            WatchPreference(BoolWatchPref.BacklightColorDayNightEnabled, false)
        )
    }

    suspend fun refreshIfEnabled(reason: String) {
        if (!hasSupportedWatch()) {
            return
        }
        if (!dayNightColorEnabled()) {
            return
        }

        logger.d { "Refreshing backlight color solar schedule: $reason" }
        refreshSolarMinutes()
    }

    suspend fun refreshSolarMinutes(): SolarSchedule = refreshMutex.withLock {
        val schedule = computeSolarSchedule()
        writeSolarSchedule(schedule)
        schedule
    }

    private suspend fun computeSolarSchedule(): SolarSchedule {
        val timeZone = automaticWatchTimeZoneOrNull() ?: return SolarSchedule.DEFAULT.also {
            logger.d { "Using default backlight color solar schedule: manual watch timezone" }
        }
        val date = clock.now().toLocalDateTime(timeZone).date
        val position = try {
            systemGeolocation.getCurrentPosition(
                maximumAge = 30.minutes,
                timeout = 5.seconds,
                highAccuracy = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Using default backlight color solar schedule: position error" }
            return SolarSchedule.DEFAULT
        }

        if (position !is GeolocationPositionResult.Success) {
            logger.d { "Using default backlight color solar schedule: no position" }
            return SolarSchedule.DEFAULT
        }

        return SolarCalculator.scheduleFor(
            date = date,
            timeZone = timeZone,
            latitude = position.latitude,
            longitude = position.longitude,
        ) ?: SolarSchedule.DEFAULT.also {
            logger.d { "Using default backlight color solar schedule: unavailable sunrise/sunset" }
        }
    }

    private suspend fun writeSolarSchedule(schedule: SolarSchedule) {
        writeWatchPref(NumberWatchPref.BacklightColorSunriseMinute, schedule.sunriseMinute.toLong())
        writeWatchPref(NumberWatchPref.BacklightColorSunsetMinute, schedule.sunsetMinute.toLong())
    }

    private suspend fun automaticWatchTimeZoneOrNull(): TimeZone? {
        val timezoneSourceIsManual =
            preference(BoolWatchPref.TimezoneSourceIsManual).valueOrDefault()
        // With a manual watch timezone, the app cannot reliably derive solar minutes in the
        // watch's local day. Schedule calculation treats null as "use fixed 6:00/18:00".
        return if (timezoneSourceIsManual) {
            null
        } else {
            TimeZone.currentSystemDefault()
        }
    }

    private fun List<WatchPreference<*>>.scheduleRelevantPrefs(): ScheduleRelevantPrefs {
        return ScheduleRelevantPrefs(
            dayNightColorEnabled = boolPrefValue(BoolWatchPref.BacklightColorDayNightEnabled),
            timezoneSourceIsManual = boolPrefValue(BoolWatchPref.TimezoneSourceIsManual),
        )
    }

    private fun List<WatchPreference<*>>.boolPrefValue(pref: BoolWatchPref): Boolean {
        val item = firstOrNull { it.pref == pref } ?: return pref.defaultValue
        return pref.castParent(item).valueOrDefault()
    }

    private suspend fun dayNightColorEnabled(): Boolean {
        return preference(BoolWatchPref.BacklightColorDayNightEnabled).valueOrDefault()
    }

    private fun hasSupportedWatch(): Boolean {
        return libPebble.watches.value
            .filterIsInstance<KnownPebbleDevice>()
            .any { it.supportsBacklightColorSchedule() }
    }

    private fun KnownPebbleDevice.supportsBacklightColorSchedule(): Boolean {
        return capabilities.contains(ProtocolCapsFlag.SupportsBacklightColorSchedule)
    }

    private suspend fun <T> preference(pref: WatchPref<T>): WatchPreference<T> {
        val item = libPebble.watchPrefs.first().first { it.pref == pref }
        return pref.castParent(item)
    }

    private suspend fun <T> writeWatchPref(pref: WatchPref<T>, value: T) {
        val target = WatchPreference(pref, value)
        libPebble.setWatchPref(target)
        withTimeoutOrNull(2.seconds) {
            libPebble.watchPrefs.first { prefs ->
                prefs.any { item ->
                    item.pref == pref && pref.castParent(item).valueOrDefault() == value
                }
            }
        }
    }

    private suspend fun refreshAtLocalMidnightLoop() {
        while (true) {
            delay(delayUntilNextLocalMidnightMs())
            refreshIfEnabled("local-date-changed")
        }
    }

    private suspend fun delayUntilNextLocalMidnightMs(): Long {
        val timeZone = automaticWatchTimeZoneOrNull() ?: TimeZone.currentSystemDefault()
        val now = clock.now()
        val tomorrow = now.toLocalDateTime(timeZone).date.plus(DatePeriod(days = 1))
        val nextMidnightMs = tomorrow.atStartOfDayIn(timeZone).toEpochMilliseconds()
        return (nextMidnightMs - now.toEpochMilliseconds()).coerceAtLeast(1_000L)
    }
}

private data class ScheduleRelevantPrefs(
    val dayNightColorEnabled: Boolean,
    val timezoneSourceIsManual: Boolean,
)
