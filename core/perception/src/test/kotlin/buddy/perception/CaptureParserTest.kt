package buddy.perception

import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.perception.capture.CaptureNode
import buddy.perception.capture.CaptureParser
import buddy.perception.capture.CaptureParserRegistry
import buddy.perception.capture.CaptureSnapshot
import buddy.perception.capture.GenericCaptureParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CaptureParserTest {
    private val tree = CaptureNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            CaptureNode(className = "android.widget.TextView", text = " Orders "),
            CaptureNode(className = "android.widget.TextView", text = "Orders"), // duplicate of previous
            CaptureNode(
                className = "androidx.recyclerview.widget.RecyclerView",
                children = listOf(
                    CaptureNode(text = "Kettle", children = listOf(CaptureNode(text = "Arriving Thursday"))),
                    CaptureNode(text = "hidden", visible = false),
                    CaptureNode(contentDescription = "Track package"),
                    CaptureNode(className = "android.widget.EditText", hint = "Search"), // hint only, no text
                ),
            ),
        ),
    )

    @Test
    fun `generic parser extracts visible text in order without duplicates`() {
        assertEquals(listOf("Orders", "Kettle", "Arriving Thursday", "Track package"), GenericCaptureParser.visibleText(tree))
    }

    @Test
    fun `generic parser emits one screen state event and de-duplicates by content`() {
        val snap = CaptureSnapshot("com.shop", "OrdersActivity", 1_700_000_000_000, tree)
        val events = GenericCaptureParser().parse(snap)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(EventKind.SCREEN_STATE, e.kind)
        assertEquals("screen", e.channel)
        assertEquals("Orders\nKettle\nArriving Thursday\nTrack package", e.text)
        assertEquals("OrdersActivity", e.structured["activity"])
        assertEquals("4", e.structured["lines"])
        assertEquals(e.id, GenericCaptureParser().parse(snap)[0].id)
        assertEquals(emptyList(), GenericCaptureParser().parse(snap.copy(root = CaptureNode())))
    }

    @Test
    fun `registry routes by package and falls back`() {
        val custom = object : CaptureParser {
            override fun parse(snapshot: CaptureSnapshot): List<Event> = emptyList()
        }
        val registry = CaptureParserRegistry(mapOf("com.custom" to custom))
        assertEquals(emptyList(), registry.parse(CaptureSnapshot("com.custom", null, 1, tree)))
        assertEquals(1, registry.parse(CaptureSnapshot("com.other", null, 1, tree)).size)
    }

    @Test
    fun `long screens are truncated`() {
        val big = CaptureNode(children = (1..500).map { CaptureNode(text = "line number $it") })
        val e = GenericCaptureParser(maxChars = 100).parse(CaptureSnapshot("p", null, 1, big))[0]
        assertEquals(100, e.text!!.length)
        assertEquals("500", e.structured["lines"])
    }
}
