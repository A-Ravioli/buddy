package buddy.perception

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust
import kotlin.math.roundToLong

/** Normalisers for the content providers: SMS, calendar, call log. */

object SmsNormalizer {
    const val SOURCE = "android.sms"
    const val CHANNEL = "sms"

    fun toEvent(row: SmsRow): Event? {
        if (row.type == SmsRow.TYPE_DRAFT) return null
        val body = row.body.trim()
        if (body.isEmpty()) return null
        val outgoing = row.type in setOf(SmsRow.TYPE_SENT, SmsRow.TYPE_OUTBOX, SmsRow.TYPE_FAILED, SmsRow.TYPE_QUEUED)
        val address = normalizeAddress(row.address)
        return Event(
            id = EventId.of(row.date, SOURCE, CHANNEL, row.id.toString()),
            ts = row.date,
            sourceApp = SOURCE,
            channel = CHANNEL,
            kind = EventKind.MESSAGE,
            actor = if (outgoing) NotificationNormalizer.USER_ACTOR else address,
            threadId = "sms:${row.threadId}",
            text = body,
            structured = buildMap {
                put("counterparty", address)
                put("direction", if (outgoing) "out" else "in")
                if (row.type == SmsRow.TYPE_FAILED) put("delivery", "failed")
                if (row.type == SmsRow.TYPE_QUEUED || row.type == SmsRow.TYPE_OUTBOX) put("delivery", "pending")
            },
            trust = if (outgoing) Trust.USER else Trust.UNTRUSTED,
            rawRef = "content://sms/${row.id}",
        )
    }

    /** Strips formatting so the same number always reads the same. Alphanumeric sender ids are kept as-is. */
    fun normalizeAddress(address: String): String {
        val trimmed = address.trim()
        val digitsOnly = trimmed.replace(Regex("[\\s().-]"), "")
        return if (digitsOnly.matches(Regex("\\+?\\d{3,}"))) digitsOnly else trimmed
    }
}

object CalendarNormalizer {
    const val SOURCE = "android.calendar"
    const val CHANNEL = "calendar"

    /**
     * The calendar provider exposes no modification time, so change detection is by
     * content: the id hashes the fields that matter, and an unchanged instance observed
     * again de-duplicates in the ledger. The id's time prefix is the instance start so
     * repeated observations of the same instance collide; the event's [Event.ts] is
     * when the change was observed, so it shows up as recent.
     */
    fun toEvent(row: CalendarInstanceRow, observedAt: Long): Event {
        val title = row.title?.trim()?.ifBlank { null }
        val location = row.location?.trim()?.ifBlank { null }
        val description = row.description?.trim()?.ifBlank { null }
        val status = when (row.status) { 0 -> "tentative"; 1 -> "confirmed"; 2 -> "canceled"; else -> "unknown" }
        val mine = when (row.selfAttendeeStatus) {
            1 -> "accepted"; 2 -> "declined"; 3 -> "invited"; 4 -> "tentative"; else -> "none"
        }
        return Event(
            id = EventId.of(
                row.begin, SOURCE, CHANNEL, row.eventId.toString(), row.end.toString(),
                title, location, description, status, mine, row.allDay.toString(),
            ),
            ts = observedAt,
            sourceApp = SOURCE,
            channel = CHANNEL,
            kind = EventKind.CALENDAR_CHANGE,
            actor = row.organizer?.trim()?.ifBlank { null },
            threadId = "calendar:${row.eventId}",
            text = listOfNotNull(title, location?.let { "at $it" }).joinToString(" "),
            structured = buildMap {
                put("event_id", row.eventId.toString())
                put("calendar_id", row.calendarId.toString())
                row.calendarName?.let { put("calendar", it) }
                put("begin", row.begin.toString())
                put("end", row.end.toString())
                put("all_day", row.allDay.toString())
                put("status", status)
                put("my_response", mine)
                location?.let { put("location", it) }
                description?.let { put("description", it) }
            },
            trust = Trust.UNTRUSTED,
            rawRef = "content://com.android.calendar/events/${row.eventId}",
        )
    }
}

object CallLogNormalizer {
    const val SOURCE = "android.calllog"
    const val CHANNEL = "calllog"

    fun toEvent(row: CallLogRow): Event {
        val outgoing = row.type == CallLogRow.OUTGOING
        val outcome = when (row.type) {
            CallLogRow.INCOMING -> "answered"
            CallLogRow.OUTGOING -> if (row.durationSeconds > 0) "connected" else "unanswered"
            CallLogRow.MISSED -> "missed"
            CallLogRow.VOICEMAIL -> "voicemail"
            CallLogRow.REJECTED -> "rejected"
            CallLogRow.BLOCKED -> "blocked"
            CallLogRow.ANSWERED_EXTERNALLY -> "answered_elsewhere"
            else -> "unknown"
        }
        val number = row.number?.let(SmsNormalizer::normalizeAddress)?.ifBlank { null }
        val name = row.cachedName?.trim()?.ifBlank { null }
        return Event(
            id = EventId.of(row.date, SOURCE, CHANNEL, row.id.toString()),
            ts = row.date,
            sourceApp = SOURCE,
            channel = CHANNEL,
            kind = EventKind.CALL,
            actor = if (outgoing) NotificationNormalizer.USER_ACTOR else (number ?: name),
            threadId = number?.let { "call:$it" },
            text = listOfNotNull(name, number).joinToString(" "),
            structured = buildMap {
                put("direction", if (outgoing) "out" else "in")
                put("outcome", outcome)
                put("duration_s", row.durationSeconds.toString())
                number?.let { put("counterparty", it) }
                name?.let { put("cached_name", it) }
            },
            trust = if (outgoing) Trust.USER else Trust.UNTRUSTED,
            rawRef = "content://call_log/calls/${row.id}",
        )
    }
}

object LocationNormalizer {
    const val SOURCE = "android"
    const val CHANNEL = "location"

    fun toEvent(s: LocationSample): Event = Event(
        id = EventId.of(s.ts, SOURCE, CHANNEL, s.provider),
        ts = s.ts,
        sourceApp = SOURCE,
        channel = CHANNEL,
        kind = EventKind.LOCATION,
        structured = buildMap {
            put("lat", round5(s.latitude))
            put("lon", round5(s.longitude))
            s.accuracyMeters?.let { put("accuracy_m", it.roundToLong().toString()) }
            s.provider?.let { put("provider", it) }
        },
        trust = Trust.SYSTEM,
    )

    private fun round5(d: Double): String = "%.5f".format(java.util.Locale.ROOT, d)
}

object DeviceStateNormalizer {
    const val SOURCE = "android"
    const val CHANNEL = "device"

    fun toEvent(s: DeviceStateSample): Event = Event(
        id = EventId.of(s.ts, SOURCE, CHANNEL, s.aspect, s.value),
        ts = s.ts,
        sourceApp = SOURCE,
        channel = CHANNEL,
        kind = EventKind.DEVICE_STATE,
        text = "${s.aspect}=${s.value}",
        structured = mapOf("aspect" to s.aspect, "value" to s.value),
        trust = Trust.SYSTEM,
    )
}
