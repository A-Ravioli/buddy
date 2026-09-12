package buddy.android.capture

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.contentcapture.ContentCaptureService
import android.util.Log
import android.view.autofill.AutofillId
import android.view.contentcapture.ContentCaptureContext
import android.view.contentcapture.ContentCaptureEvent
import android.view.contentcapture.ContentCaptureSessionId
import android.view.contentcapture.ViewNode
import buddy.android.BuddyApp
import buddy.android.perception.Ingest
import buddy.perception.capture.CaptureNode
import buddy.perception.capture.CaptureParserRegistry
import buddy.perception.capture.CaptureSnapshot

/**
 * The framework's content capture service, assigned to buddy by the platform overlay.
 * Every app's views report here as they appear, change, and disappear.
 *
 * The service keeps a flat map of nodes per session, rebuilds the tree from parent
 * ids when a burst of events settles, and hands one [CaptureSnapshot] to the parser
 * registry. Raw nodes are dropped as soon as the snapshot is parsed.
 */
class BuddyContentCaptureService : ContentCaptureService() {
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = HashMap<ContentCaptureSessionId, Session>()
    private val registry = CaptureParserRegistry()

    private class Session(val packageName: String, val activity: String?) {
        val nodes = LinkedHashMap<AutofillId, ViewNode>()
        var pending: Runnable? = null
    }

    override fun onCreateContentCaptureSession(context: ContentCaptureContext, sessionId: ContentCaptureSessionId) {
        val component: ComponentName? = context.activityComponent
        val pkg = component?.packageName ?: context.locusId?.id ?: "unknown"
        sessions[sessionId] = Session(pkg, component?.shortClassName)
    }

    override fun onDestroyContentCaptureSession(sessionId: ContentCaptureSessionId) {
        sessions.remove(sessionId)?.let { s -> s.pending?.let(handler::removeCallbacks) }
    }

    override fun onContentCaptureEvent(sessionId: ContentCaptureSessionId, event: ContentCaptureEvent) {
        val s = sessions[sessionId] ?: return
        when (event.type) {
            ContentCaptureEvent.TYPE_VIEW_APPEARED -> event.viewNode?.let { s.nodes[it.autofillId!!] = it }
            ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED -> event.viewNode?.let { s.nodes[it.autofillId!!] = it }
            ContentCaptureEvent.TYPE_VIEW_DISAPPEARED -> {
                event.id?.let(s.nodes::remove)
                event.ids?.forEach(s.nodes::remove)
            }
            else -> return
        }
        schedule(s)
    }

    /** Snapshot once the burst of view events has settled. */
    private fun schedule(s: Session) {
        s.pending?.let(handler::removeCallbacks)
        val r = Runnable {
            s.pending = null
            snapshot(s)
        }
        s.pending = r
        handler.postDelayed(r, SETTLE_MS)
    }

    private fun snapshot(s: Session) {
        if (s.nodes.isEmpty()) return
        val root = buildTree(s.nodes.values.toList())
        val snap = CaptureSnapshot(s.packageName, s.activity, System.currentTimeMillis(), root)
        latest[s.packageName] = snap
        val events = try {
            registry.parse(snap)
        } catch (t: Throwable) {
            Log.w(BuddyApp.TAG, "capture parse failed for ${s.packageName}", t)
            return
        }
        Ingest.submit(events)
    }

    companion object {
        private const val SETTLE_MS = 600L

        /** The most recent snapshot per package, for the automation driver. */
        val latest = java.util.concurrent.ConcurrentHashMap<String, CaptureSnapshot>()

        /**
         * Rebuilds a tree from the flat node list using parent autofill ids. Nodes whose
         * parent is unknown become children of a synthetic root, in arrival order.
         */
        fun buildTree(nodes: List<ViewNode>): CaptureNode {
            val byId = nodes.associateBy { it.autofillId!! }
            val childrenOf = HashMap<AutofillId?, MutableList<ViewNode>>()
            for (n in nodes) {
                val parent = n.parentAutofillId?.takeIf { it in byId }
                childrenOf.getOrPut(parent) { ArrayList() }.add(n)
            }
            // ViewNode positions are relative to the parent; accumulate to absolute.
            fun convert(n: ViewNode, offsetX: Int, offsetY: Int): CaptureNode {
                val left = offsetX + n.left
                val top = offsetY + n.top
                return CaptureNode(
                    className = n.className,
                    text = n.text?.toString(),
                    contentDescription = n.contentDescription?.toString(),
                    hint = n.hint,
                    resourceId = n.idEntry,
                    visible = n.visibility == android.view.View.VISIBLE,
                    children = childrenOf[n.autofillId]?.map { convert(it, left, top) } ?: emptyList(),
                    bounds = if (n.width > 0 && n.height > 0) CaptureNode.Bounds(left, top, n.width, n.height) else null,
                )
            }
            return CaptureNode(className = "root", children = childrenOf[null]?.map { convert(it, 0, 0) } ?: emptyList())
        }
    }
}
