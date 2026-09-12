package buddy.android.surface

import android.content.Context
import android.util.Log
import buddy.actuation.Actions
import buddy.android.BuddyApp
import buddy.android.cognition.Brain
import buddy.android.ledger.LedgerHolder
import buddy.android.surface.creature.Mood
import buddy.android.surface.home.BuddySays
import buddy.android.surface.home.ChatItem
import buddy.android.surface.home.ComingUp
import buddy.android.surface.home.Decision
import buddy.android.surface.home.Handled
import buddy.android.surface.home.HandledRow
import buddy.android.surface.home.Hold
import buddy.android.surface.home.SuggestedReply
import buddy.android.surface.home.SurfaceState
import buddy.android.surface.home.TimelineEntry
import buddy.android.surface.home.UserSays
import buddy.android.surface.home.Affordance
import buddy.android.surface.theme.SurfaceDomain
import buddy.android.voice.CommandHandler
import buddy.cognition.Planned
import buddy.cognition.TriageRecorder
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.ledger.Trust
import buddy.policy.Domain
import buddy.policy.Verdict
import buddy.triage.TriageClass
import buddy.voice.CommandParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the surface shows, built from the ledger and the last planning cycle. The
 * screen only draws this and sends decisions back; every consequence is an event the
 * executor or the next cycle records. Reads run on one background thread.
 *
 * No logic about what things mean lives here (core/android's rule). Cards are a
 * rendering of ACTION, TRIAGE and BRIEF events plus the planner's decisions.
 */
object SurfaceStore {
    private val _state = MutableStateFlow(SurfaceState())
    val state: StateFlow<SurfaceState> = _state

    private val _mood = MutableStateFlow(Mood.RESTING)
    val mood: StateFlow<Mood> = _mood

    /** Text the composer should show, set by "Change it" and "Reply". */
    val prefill = MutableStateFlow("")

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "buddy-surface") }
    private val fmt = SimpleDateFormat("H:mm", Locale.ROOT)

    @Volatile
    private var lastPlanned: Planned? = null
    private val exchanges = ArrayList<ChatItem>()
    private val dismissed = HashSet<String>()
    private var forced: Mood? = null
    private var settle: java.util.concurrent.Future<*>? = null

    fun init(context: Context) {
        LedgerHolder.whenAvailable { refresh() }
    }

    /** The scheduler ran a cycle. The BRIEF event keeps only counts, so the plan lives here. */
    fun onPlanned(p: Planned) {
        lastPlanned = p
        exchanges.clear()
        refresh()
    }

    fun refresh() {
        io.execute {
            try {
                _state.value = build()
                settleMood()
            } catch (t: Throwable) {
                Log.e(BuddyApp.TAG, "surface build failed", t)
            }
        }
    }

    // ---- what the user does

    fun holdStart() {
        settle?.cancel(false)
        forced = Mood.LISTENING
        _mood.value = Mood.LISTENING
    }

    fun holdEnd() {
        forced = Mood.WORKING
        _mood.value = Mood.WORKING
        flashDone(after = 900)
    }

    /** The user said or typed something. Same path as the earbuds. */
    fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        exchanges.add(UserSays("u-${System.currentTimeMillis()}", t))
        _state.value = _state.value.copy(items = base(_state.value.items) + exchanges)
        forced = Mood.WORKING
        _mood.value = Mood.WORKING
        io.execute {
            val reply = try {
                CommandHandler.handle(CommandParser.parse(t))
            } catch (e: Throwable) {
                Log.e(BuddyApp.TAG, "command failed", e)
                "Something went wrong with that."
            }
            exchanges.add(BuddySays("b-${System.currentTimeMillis()}", reply))
            _state.value = build()
            flashDone(after = 0)
        }
    }

    /**
     * A card's option was tapped. `accepted` is buddy's recommendation: send, do it now,
     * move it. The other option is stop, later, keep as is.
     */
    fun resolve(itemId: String, accepted: Boolean) {
        io.execute {
            try {
                val ledger = LedgerHolder.getOrNull() ?: return@execute
                val exec = Brain.executor
                when {
                    itemId.startsWith("hold-") -> {
                        val e = ledger.get(itemId.removePrefix("hold-")) ?: return@execute
                        if (exec != null) { if (accepted) exec.release(e) else exec.veto(e) }
                    }
                    itemId.startsWith("esc-") -> {
                        val e = ledger.get(itemId.removePrefix("esc-")) ?: return@execute
                        val p = exec?.proposalOf(e)
                        if (accepted && exec != null && p != null) {
                            exec.apply(p, Verdict.Run(listOf("approved_by_user")))
                        } else {
                            if (p != null) prefill.value = p.payload["text"] ?: ""
                            note(ledger, "dismissed_proposal", e.id, mapOf("proposal_id" to (p?.id ?: "")))
                        }
                    }
                    itemId.startsWith("tri-") -> {
                        val e = ledger.get(itemId.removePrefix("tri-")) ?: return@execute
                        if (accepted) prefill.value = "reply to ${e.actor ?: e.sourceApp} " else note(ledger, "dismissed_item", e.id, emptyMap())
                    }
                    itemId.startsWith("dec-") -> {
                        val i = itemId.removePrefix("dec-").toIntOrNull() ?: return@execute
                        val d = lastPlanned?.brief?.decisions?.getOrNull(i) ?: return@execute
                        val chosen = if (accepted) recommended(d.options, d.recommendation) else other(d.options, d.recommendation)
                        note(ledger, "decision", d.eventIds.firstOrNull() ?: "", mapOf("question" to d.question, "chosen" to chosen))
                    }
                }
                dismissed.add(itemId)
                _state.value = build()
                flashDone(after = 0)
            } catch (t: Throwable) {
                Log.e(BuddyApp.TAG, "resolve failed", t)
            }
        }
    }

    fun undo(entryId: String) {
        io.execute {
            val ledger = LedgerHolder.getOrNull() ?: return@execute
            val e = ledger.get(entryId) ?: return@execute
            val exec = Brain.executor ?: return@execute
            try {
                exec.undo(e)
            } catch (t: Throwable) {
                Log.w(BuddyApp.TAG, "undo failed", t)
            }
            _state.value = build()
            flashDone(after = 0)
        }
    }

    // ---- building the view

    private fun base(items: List<ChatItem>): List<ChatItem> = items.filterNot { it.id.startsWith("u-") || it.id.startsWith("b-") }

    private fun build(): SurfaceState {
        val ledger = LedgerHolder.getOrNull() ?: return SurfaceState(locked = true)
        val exec = Brain.executor
        val recent = ledger.recent(2000)
        val brief = recent.firstOrNull { it.kind == EventKind.BRIEF }
        val since = brief?.ts ?: 0L
        val planned = lastPlanned
        val byId = recent.associateBy { it.id }
        val items = ArrayList<ChatItem>()

        // Since the last brief: the planner's decisions, escalated proposals, held actions,
        // and escalated items nothing was proposed for.
        val noted = recent.filter { it.kind == EventKind.MEMORY_NOTE && it.ts > since && it.structured["kind"]?.startsWith("dismissed") == true }
            .mapNotNull { it.structured["about"] }.toSet()
        val decided = recent.filter { it.kind == EventKind.MEMORY_NOTE && it.ts > since && it.structured["kind"] == "decision" }
            .mapNotNull { it.structured["question"] }.toSet()

        val opening = planned?.brief?.spoken ?: brief?.text
        items.add(BuddySays("brief", opening ?: "I haven't written a brief yet. Say hey buddy, or wait for the morning one."))
        planned?.brief?.urgent?.forEachIndexed { i, u -> items.add(BuddySays("urgent-$i", "Urgent: ${u.summary}")) }
        planned?.brief?.decisions?.forEachIndexed { i, d ->
            val id = "dec-$i"
            if (id in dismissed || d.question in decided) return@forEachIndexed
            val about = d.eventIds.firstOrNull()?.let { byId[it] }
            items.add(
                Decision(
                    id, domainOf(about), about?.let { "${it.actor ?: it.sourceApp} · ${it.sourceApp}" } ?: "buddy",
                    d.question, d.recommendation,
                    accept = recommended(d.options, d.recommendation), decline = other(d.options, d.recommendation),
                ),
            )
        }

        val actions = recent.filter { it.kind == EventKind.ACTION }
        val superseded = actions.mapNotNull { it.supersedes }.toSet() + recent.filter { it.kind == EventKind.CORRECTION }.mapNotNull { it.supersedes }
        val covered = HashSet<String>()
        for (e in actions) {
            if (e.id in superseded || e.ts <= since && e.structured["state"] != "held") continue
            val state = e.structured["state"]
            val p = exec?.proposalOf(e)
            val sources = e.structured["source_events"]?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()
            when (state) {
                "held" -> {
                    if (ledger.corrections(e.id).isNotEmpty() || "hold-${e.id}" in dismissed) continue
                    covered.addAll(sources)
                    val until = e.structured["until"]?.toLongOrNull() ?: 0L
                    val total = (Brain.policyProfile.holdMinutes * 60_000L).coerceAtLeast(1L)
                    val fraction = (1f - ((until - System.currentTimeMillis()).toFloat() / total)).coerceIn(0f, 1f)
                    items.add(Hold("hold-${e.id}", domainOf(e), source(e, byId), describe(e), e.text ?: reasons(e), fraction, now = "Now"))
                }
                "escalated" -> {
                    if (e.id in noted || "esc-${e.id}" in dismissed) continue
                    covered.addAll(sources)
                    val about = sources.firstOrNull()?.let { byId[it] }
                    val draft = e.structured["p_text"]
                    if (draft != null && about != null) {
                        items.add(SuggestedReply("esc-${e.id}", domainOf(e), source(about, byId), about.text?.take(240) ?: "", draft))
                    } else {
                        items.add(Decision("esc-${e.id}", domainOf(e), source(e, byId), describe(e) + "?", e.text ?: reasons(e), accept = "Do it", decline = "Skip"))
                    }
                }
            }
        }
        recent.asSequence()
            .filter { it.kind == EventKind.TRIAGE && it.ts > since }
            .mapNotNull { TriageRecorder.fromEvent(it) }
            .filter { it.klass == TriageClass.ESCALATE && it.eventId !in covered && it.eventId !in noted && "tri-${it.eventId}" !in dismissed }
            .forEach { d ->
                val e = byId[d.eventId] ?: return@forEach
                items.add(
                    Decision(
                        "tri-${e.id}", domainOf(e), source(e, byId),
                        (e.text ?: e.structured.entries.joinToString(" ") { "${it.key}=${it.value}" }).take(160),
                        if (d.urgent) "Marked urgent." else "Needs you; I didn't want to guess.",
                        accept = "Reply", decline = "Later",
                    ),
                )
            }

        // What buddy did, as counts, from the plan or from the ledger.
        val doneLines = planned?.brief?.done.orEmpty()
        val doneRows = if (doneLines.isNotEmpty()) {
            doneLines.map { HandledRow(guessDomain(it), "", it) }
        } else {
            actions.filter { it.structured["state"] == "done" && it.ts > since }
                .groupBy { domainOf(it) }
                .map { (d, es) -> HandledRow(d, label(d), "${es.size} ${if (es.size == 1) "action" else "actions"}") }
        }
        if (doneRows.isNotEmpty()) items.add(Handled("handled", "since the last brief", doneRows))
        planned?.brief?.upcoming?.takeIf { it.isNotEmpty() }?.let { up ->
            items.add(ComingUp("upcoming", up.map { "" to it.summary }))
        }
        items.addAll(exchanges)

        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val handledToday = actions.count { it.structured["state"] == "done" && it.ts >= dayStart }
        val timeline = (actions + recent.filter { it.kind == EventKind.CORRECTION })
            .sortedByDescending { it.ts }
            .take(80)
            .map { e ->
                val state = e.structured["state"]
                val undoable = e.kind == EventKind.ACTION && state == "done" && e.id !in superseded && ledger.corrections(e.id).isEmpty()
                TimelineEntry(
                    e.id, fmt.format(Date(e.ts)), domainOf(e), describe(e), e.text ?: reasons(e),
                    when {
                        undoable -> Affordance.UNDO
                        state == "kept" -> Affordance.KEPT
                        else -> null
                    },
                )
            }

        return SurfaceState(
            items = items,
            timeline = timeline,
            status = if (brief != null) "brief ${fmt.format(Date(brief.ts))}" else "no brief yet",
            handledToday = handledToday,
            nextUp = planned?.brief?.upcoming?.firstOrNull()?.summary ?: "",
        )
    }

    private fun note(ledger: Ledger, kind: String, about: String, extra: Map<String, String>) {
        val ts = System.currentTimeMillis()
        ledger.append(
            Event(
                EventId.of(ts, "buddy", "surface", kind, about), ts, "buddy", "surface", EventKind.MEMORY_NOTE, "me", null,
                null, mapOf("kind" to kind, "about" to about, "confidence" to "1.00") + extra, Trust.USER,
            ),
        )
    }

    private fun recommended(options: List<String>, rec: String): String =
        options.firstOrNull { rec.contains(it, ignoreCase = true) } ?: options.firstOrNull() ?: "Yes"

    private fun other(options: List<String>, rec: String): String {
        val r = recommended(options, rec)
        return options.firstOrNull { it != r } ?: "Later"
    }

    private fun reasons(e: Event) = e.structured["reasons"]?.replace('|', ' ')?.replace('_', ' ') ?: ""

    private fun source(e: Event, byId: Map<String, Event>): String {
        val who = e.actor ?: e.structured["target"]
        return listOfNotNull(who, e.sourceApp.takeIf { it != "buddy" }).joinToString(" · ").ifEmpty { "buddy" }
    }

    /** One line for an ACTION event, in the words the timeline uses. */
    private fun describe(e: Event): String {
        val s = e.structured
        val what = when (s["action"]) {
            Actions.SEND_MESSAGE.name -> "Sent a message"
            Actions.NOTIFICATION_REPLY.name -> "Replied"
            Actions.NOTIFICATION_MARK_READ.name -> "Marked as read"
            Actions.NOTIFICATION_DISMISS.name -> "Cleared a notification"
            Actions.ARCHIVE_EMAIL.name -> "Archived an email"
            Actions.LABEL_EMAIL.name -> "Filed an email"
            Actions.REPLY_EMAIL.name -> "Replied to an email"
            Actions.UNSUBSCRIBE_EMAIL.name -> "Unsubscribed"
            Actions.RESPOND_INVITE.name -> "Answered an invite"
            Actions.CREATE_EVENT.name -> "Added to the calendar"
            Actions.SNOOZE.name -> "Snoozed"
            else -> (s["action"] ?: "did something").replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        val to = s["target"]?.let { " to $it" } ?: ""
        val text = s["p_text"]?.let { ": “${it.take(80)}”" } ?: ""
        val prefix = when (s["state"]) {
            "held" -> "Holding: "
            "vetoed" -> "Stopped: "
            "escalated" -> "Asked you: "
            "failed", "unverified" -> "Couldn't: "
            "undone" -> "Undone: "
            "undo_failed" -> "Couldn't undo: "
            "denied" -> "Refused: "
            else -> ""
        }
        return prefix + what + to + text
    }

    private fun domainOf(e: Event?): SurfaceDomain {
        if (e == null) return SurfaceDomain.HEARD
        e.structured["domain"]?.let { runCatching { Domain.valueOf(it) }.getOrNull() }?.let { return map(it) }
        return when (e.kind) {
            EventKind.MESSAGE -> if (e.channel.contains("mail")) SurfaceDomain.EMAIL else SurfaceDomain.MESSAGES
            EventKind.CALENDAR_CHANGE -> SurfaceDomain.CALENDAR
            EventKind.CALL -> SurfaceDomain.CALLS
            EventKind.UTTERANCE -> SurfaceDomain.HEARD
            EventKind.NOTIFICATION -> guessDomain(e.sourceApp + " " + (e.text ?: ""))
            else -> SurfaceDomain.HEARD
        }
    }

    private fun map(d: Domain): SurfaceDomain = when (d) {
        Domain.MESSAGING, Domain.SOCIAL -> SurfaceDomain.MESSAGES
        Domain.EMAIL -> SurfaceDomain.EMAIL
        Domain.CALENDAR -> SurfaceDomain.CALENDAR
        Domain.MONEY, Domain.SHOPPING -> SurfaceDomain.MONEY
        Domain.TRAVEL -> SurfaceDomain.DELIVERIES
        Domain.CALLS -> SurfaceDomain.CALLS
        Domain.ACCOUNTS, Domain.DEVICE -> SurfaceDomain.HEARD
    }

    private fun guessDomain(text: String): SurfaceDomain {
        val t = text.lowercase()
        return when {
            listOf("mail", "email", "inbox", "unsubscribe").any { it in t } -> SurfaceDomain.EMAIL
            listOf("calendar", "meeting", "invite", "appointment").any { it in t } -> SurfaceDomain.CALENDAR
            listOf("paid", "bill", "bank", "£", "$", "payment", "monzo").any { it in t } -> SurfaceDomain.MONEY
            listOf("parcel", "deliver", "package", "order", "flight", "train").any { it in t } -> SurfaceDomain.DELIVERIES
            listOf("call", "voicemail", "rang").any { it in t } -> SurfaceDomain.CALLS
            listOf("message", "replied", "text", "whatsapp", "sms").any { it in t } -> SurfaceDomain.MESSAGES
            else -> SurfaceDomain.HEARD
        }
    }

    private fun label(d: SurfaceDomain) = when (d) {
        SurfaceDomain.CALENDAR -> "Calendar"
        SurfaceDomain.MESSAGES -> "Messages"
        SurfaceDomain.EMAIL -> "Email"
        SurfaceDomain.MONEY -> "Money"
        SurfaceDomain.DELIVERIES -> "Deliveries"
        SurfaceDomain.CALLS -> "Calls"
        SurfaceDomain.EMERGENCY -> "Urgent"
        SurfaceDomain.HEARD -> "Other"
    }

    // ---- the creature's mood

    private fun settleMood() {
        if (forced == null) _mood.value = if (_state.value.needsYou > 0) Mood.NEEDS_YOU else Mood.RESTING
    }

    /** Listened, worked, a short "done", then back to whatever the queue says. */
    private fun flashDone(after: Long) {
        settle?.cancel(false)
        settle = scheduler.submit {
            try {
                if (after > 0) Thread.sleep(after)
                forced = Mood.DONE
                _mood.value = Mood.DONE
                Thread.sleep(600)
                forced = null
                settleMood()
            } catch (_: InterruptedException) {
            }
        }
    }

    private val scheduler = Executors.newSingleThreadExecutor { r -> Thread(r, "buddy-surface-mood") }
}
