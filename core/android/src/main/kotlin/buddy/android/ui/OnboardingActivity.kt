package buddy.android.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.buddy.R
import buddy.android.cognition.Brain
import buddy.android.ledger.LedgerHolder
import buddy.profile.ProfileBootstrap
import java.io.File
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * The second-user onboarding (Phase 4): bootstrap a profile from the history already
 * in the ledger, show what was inferred in plain words, and write the profile files
 * the brain reads at start-up. Voice enrolment records a sample for the speaker gate.
 */
class OnboardingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brief)
        title = getString(R.string.onboarding_title)
        val status: TextView = findViewById(R.id.status)
        val list: ListView = findViewById(R.id.queue)
        findViewById<TextView>(R.id.spoken).text = getString(R.string.onboarding_intro)
        status.text = getString(R.string.onboarding_running)
        thread(name = "buddy-onboarding") {
            val ledger = LedgerHolder.getOrNull()
            val entities = Brain.entities
            if (ledger == null || entities == null) {
                runOnUiThread { status.text = getString(R.string.onboarding_not_ready) }
                return@thread
            }
            val history = ledger.recent(20_000).reversed()
            val b = ProfileBootstrap(entities, ZoneId.systemDefault()).run(history)
            val dir = File(filesDir, "profile").apply { mkdirs() }
            File(dir, "triage.properties").writeText("close=${b.triage.closeActors.joinToString(",")}\n")
            File(dir, "policy.properties").writeText(
                b.policy.levels.entries.joinToString("\n") { "level.${it.key.name.lowercase()}=${it.value.name.lowercase()}" } +
                    "\nquiet_start=${b.quietStartHour}\nquiet_end=${b.quietEndHour}\nhold_minutes=10\n",
            )
            runOnUiThread {
                status.text = getString(R.string.onboarding_done)
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, b.summary + listOf(getString(R.string.onboarding_enrol_hint)))
                Toast.makeText(this, R.string.onboarding_restart, Toast.LENGTH_LONG).show()
            }
        }
    }
}
