package buddy.wear

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray

/**
 * One list: each escalation with its options. Tapping an option sends the choice to
 * the phone, which records it as the user's decision and acts through policy.
 */
class EscalationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = ListView(this)
        setContentView(list)
        val items = JSONArray(State.escalations)
        val rows = ArrayList<Pair<String, String>>() // (escalation id, option)
        val lines = ArrayList<String>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val id = it.getString("id")
            val prefix = if (it.optBoolean("urgent")) "URGENT " else ""
            lines.add(prefix + it.getString("text")); rows.add(id to "")
            val options = it.optJSONArray("options") ?: JSONArray()
            for (j in 0 until options.length()) {
                lines.add("   → " + options.getString(j)); rows.add(id to options.getString(j))
            }
        }
        if (lines.isEmpty()) lines.add(State.brief.ifBlank { "Nothing needs you." })
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        list.setOnItemClickListener { _, _, pos, _ ->
            val (id, option) = rows.getOrNull(pos) ?: return@setOnItemClickListener
            if (option.isEmpty()) return@setOnItemClickListener
            val req = PutDataMapRequest.create("/buddy/resolve").apply {
                dataMap.putString("id", id); dataMap.putString("option", option); dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(this).putDataItem(req)
            finish()
        }
    }
}
