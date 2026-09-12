package buddy.android.perception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import buddy.android.BuddyApp
import app.buddy.R
import buddy.perception.CalendarInstanceRow
import buddy.perception.CalendarNormalizer
import buddy.perception.CallLogNormalizer
import buddy.perception.CallLogRow
import buddy.perception.DeviceStateNormalizer
import buddy.perception.DeviceStateSample
import buddy.perception.LocationDebouncer
import buddy.perception.LocationNormalizer
import buddy.perception.LocationSample
import buddy.perception.SmsNormalizer
import buddy.perception.SmsRow

/**
 * Hosts the perception sources that need a living process: content observers on the
 * SMS, call log, and calendar providers; location; device state broadcasts.
 *
 * Each source re-reads a bounded window on change and hands rows to the normaliser;
 * the ledger's idempotent append takes care of overlap, so the observers can be
 * sloppy about "what is new".
 */
class PerceptionService : Service() {
    private val thread = HandlerThread("buddy-perception").apply { start() }
    private val handler by lazy { Handler(thread.looper) }
    private val observers = ArrayList<ContentObserver>()
    private val receivers = ArrayList<BroadcastReceiver>()
    private val locationDebouncer = LocationDebouncer()
    private var locationListener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        observeProvider(Telephony.Sms.CONTENT_URI) { readSms() }
        observeProvider(CallLog.Calls.CONTENT_URI) { readCallLog() }
        observeProvider(CalendarContract.Instances.CONTENT_URI) { readCalendar() }
        observeDeviceState()
        observeLocation()
        handler.post { readSms(); readCallLog(); readCalendar() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        observers.forEach { contentResolver.unregisterContentObserver(it) }
        receivers.forEach { runCatching { unregisterReceiver(it) } }
        locationListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.perception_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.perception_running))
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun observeProvider(uri: Uri, read: () -> Unit) {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Debounce bursts: providers fire several changes per logical write.
                handler.removeCallbacks(read)
                handler.postDelayed(read, 750)
            }
        }
        contentResolver.registerContentObserver(uri, true, observer)
        observers.add(observer)
    }

    // ---- SMS -------------------------------------------------------------------------

    private fun readSms() {
        val since = System.currentTimeMillis() - WINDOW_MS
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.TYPE,
        )
        val events = ArrayList<buddy.ledger.Event>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $MAX_ROWS",
        )?.use { c ->
            while (c.moveToNext()) {
                val row = SmsRow(
                    id = c.getLong(0),
                    threadId = c.getLong(1),
                    address = c.getString(2) ?: continue,
                    date = c.getLong(3),
                    body = c.getString(4) ?: "",
                    type = c.getInt(5),
                )
                SmsNormalizer.toEvent(row)?.let(events::add)
            }
        }
        Ingest.submit(events)
    }

    // ---- Call log -------------------------------------------------------------------

    private fun readCallLog() {
        val since = System.currentTimeMillis() - WINDOW_MS
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE,
        )
        val events = ArrayList<buddy.ledger.Event>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()),
            "${CallLog.Calls.DATE} DESC LIMIT $MAX_ROWS",
        )?.use { c ->
            while (c.moveToNext()) {
                events.add(
                    CallLogNormalizer.toEvent(
                        CallLogRow(
                            id = c.getLong(0),
                            number = c.getString(1),
                            cachedName = c.getString(2),
                            date = c.getLong(3),
                            durationSeconds = c.getLong(4),
                            type = c.getInt(5),
                        ),
                    ),
                )
            }
        }
        Ingest.submit(events)
    }

    // ---- Calendar -------------------------------------------------------------------

    private fun readCalendar() {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((now - CALENDAR_PAST_MS).toString())
            .appendPath((now + CALENDAR_FUTURE_MS).toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.ORGANIZER, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val events = ArrayList<buddy.ledger.Event>()
        contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                events.add(
                    CalendarNormalizer.toEvent(
                        CalendarInstanceRow(
                            eventId = c.getLong(0),
                            calendarId = c.getLong(1),
                            calendarName = c.getString(2),
                            title = c.getString(3),
                            description = c.getString(4),
                            location = c.getString(5),
                            begin = c.getLong(6),
                            end = c.getLong(7),
                            allDay = c.getInt(8) == 1,
                            organizer = c.getString(9),
                            status = if (c.isNull(10)) null else c.getInt(10),
                            selfAttendeeStatus = if (c.isNull(11)) null else c.getInt(11),
                        ),
                        observedAt = now,
                    ),
                )
            }
        }
        Ingest.submit(events)
    }

    // ---- Device state ---------------------------------------------------------------

    private fun observeDeviceState() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val ts = System.currentTimeMillis()
                val sample = when (intent.action) {
                    Intent.ACTION_BATTERY_LOW -> DeviceStateSample(ts, "battery", "low")
                    Intent.ACTION_BATTERY_OKAY -> DeviceStateSample(ts, "battery", "ok")
                    Intent.ACTION_POWER_CONNECTED -> DeviceStateSample(ts, "charging", "true")
                    Intent.ACTION_POWER_DISCONNECTED -> DeviceStateSample(ts, "charging", "false")
                    Intent.ACTION_SCREEN_ON -> DeviceStateSample(ts, "screen", "on")
                    Intent.ACTION_SCREEN_OFF -> DeviceStateSample(ts, "screen", "off")
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return
                        // Only every 5 percent, otherwise this is a firehose.
                        if (pct % 5 != 0) return
                        DeviceStateSample(ts, "battery", pct.toString())
                    }
                    else -> return
                }
                Ingest.submit(DeviceStateNormalizer.toEvent(sample))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        receivers.add(receiver)
    }

    // ---- Location -------------------------------------------------------------------

    private fun observeLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val provider = when {
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> {
                Log.w(BuddyApp.TAG, "no location provider enabled")
                return
            }
        }
        val listener = LocationListener { loc: Location ->
            val sample = LocationSample(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, loc.provider)
            if (locationDebouncer.accept(sample)) Ingest.submit(LocationNormalizer.toEvent(sample))
        }
        try {
            lm.requestLocationUpdates(provider, LOCATION_MIN_TIME_MS, LOCATION_MIN_DISTANCE_M, listener, thread.looper)
            locationListener = listener
        } catch (e: SecurityException) {
            Log.w(BuddyApp.TAG, "location permission missing", e)
        }
    }

    companion object {
        private const val CHANNEL = "perception"
        private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ROWS = 500
        private const val CALENDAR_PAST_MS = 7L * 24 * 60 * 60 * 1000
        private const val CALENDAR_FUTURE_MS = 60L * 24 * 60 * 60 * 1000
        private const val LOCATION_MIN_TIME_MS = 60_000L
        private const val LOCATION_MIN_DISTANCE_M = 50f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PerceptionService::class.java))
        }
    }
}
