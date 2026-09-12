package buddy.android.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.buddy.R
import buddy.android.ledger.LedgerHolder
import buddy.ledger.Event
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The debug timeline: the newest events in the ledger, one line each. In Phase 0 it
 * is also the home screen. It exists so the founder can see what buddy sees and catch
 * perception gaps early.
 */
class TimelineActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var status: TextView
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
    private var shown: List<Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        title = getString(R.string.timeline_title)
        list = findViewById(R.id.events)
        status = findViewById(R.id.status)
        // Long-press a completed action to undo it. The undo is a correction event and
        // the strongest learning signal buddy gets.
        list.setOnItemLongClickListener { _, _, position, _ ->
            val e = shown.getOrNull(position) ?: return@setOnItemLongClickListener false
            val exec = buddy.android.cognition.Brain.executor
            if (e.kind == buddy.ledger.EventKind.ACTION && e.structured["state"] == "done" && exec != null) {
                exec.undo(e)
                Toast.makeText(this, R.string.undo_done, Toast.LENGTH_SHORT).show()
                refresh()
                true
            } else false
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
            list.adapter = null
            return
        }
        val events = ledger.recent(300)
        shown = events
        status.text = "${ledger.count()} events"
        val lines = if (events.isEmpty()) listOf(getString(R.string.timeline_empty)) else events.map(::line)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
    }

    private fun line(e: Event): String {
        val who = e.actor?.let { " $it" } ?: ""
        val body = e.text?.replace('\n', ' ')?.take(120) ?: e.structured.entries.joinToString(" ") { "${it.key}=${it.value}" }
        return "${fmt.format(Date(e.ts))} ${e.kind.name.lowercase()} ${e.sourceApp}/${e.channel}$who: $body"
    }
}

/** SMS role requirement. Phase 0 does not compose messages. */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, R.string.stub_not_yet, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Dialer role requirement. Phase 0 does not place calls. */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, R.string.stub_not_yet, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Assistant role requirement. Long-press opens the timeline until voice lands. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, TimelineActivity::class.java))
        finish()
    }
}
