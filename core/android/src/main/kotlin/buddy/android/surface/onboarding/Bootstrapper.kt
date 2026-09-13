package buddy.android.surface.onboarding

import android.content.Context
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.cognition.Brain
import buddy.android.ledger.LedgerHolder
import buddy.policy.Domain
import buddy.policy.Level
import buddy.profile.Bootstrap
import buddy.profile.ProfileBootstrap
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * The first run's background work: read the history already on the phone, infer the
 * profile (who matters, quiet hours, style, autonomy defaults), and write the profile
 * files the brain reads at start-up. The same work as the second-user onboarding
 * screen, started early so the later steps can show what was inferred.
 */
object Bootstrapper {
    private val _result = MutableStateFlow<Bootstrap?>(null)
    val result: StateFlow<Bootstrap?> = _result

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done

    private val _people = MutableStateFlow(0)
    val people: StateFlow<Int> = _people

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "buddy-onboarding") {
            try {
                val ledger = LedgerHolder.getOrNull()
                val entities = Brain.entities
                if (ledger == null || entities == null) {
                    Log.i(BuddyApp.TAG, "onboarding: ledger not open yet, defaults only")
                    writeDefaults(app)
                } else {
                    _people.value = entities.people(5000).size
                    val history = ledger.recent(20_000).reversed()
                    val b = ProfileBootstrap(entities, ZoneId.systemDefault()).run(history)
                    _result.value = b
                    write(app, b, careful = true)
                }
            } catch (t: Throwable) {
                Log.e(BuddyApp.TAG, "onboarding bootstrap failed", t)
            } finally {
                _done.value = true
            }
        }
    }

    /** The trust step. Careful keeps the inferred defaults; "let me act" raises the reversible domains one level. */
    fun applyTrust(context: Context, careful: Boolean) {
        val b = _result.value
        if (b != null) write(context.applicationContext, b, careful) else writeDefaults(context.applicationContext, careful)
    }

    private fun write(context: Context, b: Bootstrap, careful: Boolean) {
        val dir = File(context.filesDir, "profile").apply { mkdirs() }
        File(dir, "triage.properties").writeText("close=${b.triage.closeActors.joinToString(",")}\n")
        val levels = b.policy.levels.toMutableMap()
        if (!careful) for (d in listOf(Domain.MESSAGING, Domain.CALENDAR, Domain.EMAIL)) levels[d] = maxOf(levels[d] ?: Level.DRAFT, Level.HOLD)
        File(dir, "policy.properties").writeText(
            levels.entries.joinToString("\n") { "level.${it.key.name.lowercase()}=${it.value.name.lowercase()}" } +
                "\nquiet_start=${b.quietStartHour}\nquiet_end=${b.quietEndHour}\nhold_minutes=10\n",
        )
    }

    private fun writeDefaults(context: Context, careful: Boolean = true) {
        val dir = File(context.filesDir, "profile").apply { mkdirs() }
        val levels = buddy.policy.PolicyProfile().levels.toMutableMap()
        if (!careful) for (d in listOf(Domain.MESSAGING, Domain.CALENDAR, Domain.EMAIL)) levels[d] = maxOf(levels[d] ?: Level.DRAFT, Level.HOLD)
        File(dir, "policy.properties").writeText(
            levels.entries.joinToString("\n") { "level.${it.key.name.lowercase()}=${it.value.name.lowercase()}" } +
                "\nquiet_start=23\nquiet_end=7\nhold_minutes=10\n",
        )
    }
}
