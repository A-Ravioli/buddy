package buddy.android.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import app.buddy.R
import buddy.android.cognition.BriefScheduler
import buddy.android.ledger.LedgerHolder
import buddy.cognition.TriageRecorder
import buddy.ledger.EventKind
import buddy.triage.TriageClass

/**
 * The brief and the escalation queue on one screen (docs/01-architecture.md, "Surface").
 * Top: the latest brief's spoken text. Below: every escalated item since that brief,
 * one line each. Tapping the status line runs an ad hoc cycle.
 */
class BriefActivity : Activity() {
    private lateinit var spoken: TextView
    private lateinit var status: TextView
    private lateinit var queue: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brief)
        title = getString(R.string.brief_title)
        spoken = findViewById(R.id.spoken)
        status = findViewById(R.id.status)
        queue = findViewById(R.id.queue)
        status.setOnClickListener {
            status.text = getString(R.string.brief_running)
            BriefScheduler.runCycle(this, "adhoc") { runOnUiThread { refresh() } }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ledger = LedgerHolder.getOrNull()
        if (ledger == null) {
            status.text = "ledger locked until first unlock"
            return
        }
        val recent = ledger.recent(2000)
        val brief = recent.firstOrNull { it.kind == EventKind.BRIEF }
        spoken.text = brief?.text ?: getString(R.string.brief_none_yet)
        val since = brief?.ts ?: 0L
        val escalated = recent.asSequence()
            .filter { it.kind == EventKind.TRIAGE && it.ts > since }
            .mapNotNull(TriageRecorder::fromEvent)
            .filter { it.klass == TriageClass.ESCALATE }
            .toList()
        val byId = recent.associateBy { it.id }
        val lines = escalated.map { d ->
            val e = byId[d.eventId]
            val who = e?.actor ?: e?.sourceApp ?: d.eventId
            val what = e?.text?.replace('\n', ' ')?.take(100) ?: e?.structured?.entries?.joinToString(" ") { "${it.key}=${it.value}" } ?: ""
            (if (d.urgent) "URGENT " else "") + "$who: $what"
        }
        status.text = getString(R.string.brief_status, brief?.structured?.get("source") ?: "none", escalated.size)
        queue.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines.ifEmpty { listOf(getString(R.string.brief_queue_empty)) })
    }
}
