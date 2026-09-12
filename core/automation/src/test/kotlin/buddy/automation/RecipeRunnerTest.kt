package buddy.automation

import buddy.perception.capture.CaptureNode
import buddy.perception.capture.CaptureSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeRunnerTest {
    private fun screen(vararg texts: String) = CaptureSnapshot("com.carrier", "Main", 1, CaptureNode(children = texts.map { CaptureNode(text = it) }))

    /**
     * A scripted phone: a list of screens shown in order; a tap or type advances to the
     * next one. Records every input.
     */
    private class FakeDriver(private val screens: List<CaptureSnapshot?>) : Driver {
        var index = 0
        val inputs = ArrayList<String>()
        var clock = 0L
        var launched: String? = null
        override fun launch(packageName: String): Boolean { launched = packageName; return true }
        override fun screen(): CaptureSnapshot? = screens.getOrNull(index)
        override fun tap(ref: NodeRef): Boolean { inputs.add("tap:${ref.node.text}"); index++; return true }
        override fun type(ref: NodeRef, text: String): Boolean { inputs.add("type:${ref.node.text}=$text"); index++; return true }
        override fun scroll(down: Boolean): Boolean { inputs.add("scroll"); return true }
        override fun back(): Boolean { inputs.add("back"); return true }
        override fun sleep(ms: Long) { clock += ms }
        override fun now(): Long = clock
    }

    private val recipe = Recipes.genericCarrierReschedule("com.carrier")
    private val params = mapOf("tracking" to "1Z999", "day" to "Thursday")

    @Test
    fun `a matching app runs the recipe to completion`() {
        val driver = FakeDriver(listOf(
            screen("Your parcels", "1Z999 arriving tomorrow"),
            screen("Parcel 1Z999", "Reschedule", "Leave with neighbour"),
            screen("Pick a day", "Wednesday", "Thursday", "Friday"),
            screen("Confirm", "Thursday"),
            screen("Delivery rescheduled", "Thursday"),
        ))
        val r = RecipeRunner(driver).run(recipe, params)
        assertTrue(r.ok, r.trace.joinToString("\n"))
        assertEquals("done", r.reason)
        assertEquals("com.carrier", driver.launched)
        assertEquals(listOf("tap:1Z999 arriving tomorrow", "tap:Reschedule", "tap:Thursday", "tap:Confirm"), driver.inputs)
        assertTrue(r.trace.last() == "done")
    }

    @Test
    fun `an app that changed its screens aborts as drift without guessing`() {
        val driver = FakeDriver(listOf(
            screen("Your parcels", "1Z999 arriving tomorrow"),
            screen("Parcel 1Z999", "Reschedule"),
            screen("Pick a day", "Wednesday", "Thursday"),
            screen("Confirm", "Thursday"),
            screen("Delivery rescheduled", "Friday"), // confirmation shows the wrong day
        ))
        val r = RecipeRunner(driver).run(recipe, params)
        assertFalse(r.ok)
        assertEquals("drift", r.reason)
        assertEquals(9, r.step)
        assertTrue(r.trace.last().contains("DRIFT"))
    }

    @Test
    fun `waiting times out when the expected screen never appears`() {
        val driver = FakeDriver(listOf(screen("Loading...")))
        val r = RecipeRunner(driver).run(recipe, params)
        assertFalse(r.ok)
        assertEquals("timeout", r.reason)
        assertEquals(1, r.step)
        assertTrue(driver.clock >= 8_000)
        assertTrue(driver.inputs.isEmpty())
    }

    @Test
    fun `missing params and missing nodes fail before any input`() {
        val driver = FakeDriver(listOf(screen("x")))
        assertEquals("missing_param", RecipeRunner(driver).run(recipe, mapOf("tracking" to "1")).reason)
        val r = RecipeRunner(driver).run(Recipe("t", "p", recipe.spec, listOf(Step.Tap(Match.Text("nope")))), emptyMap())
        assertEquals("not_found", r.reason)
        assertTrue(driver.inputs.isEmpty())
    }

    @Test
    fun `matchers and placeholders`() {
        val s = CaptureSnapshot("p", null, 1, CaptureNode(children = listOf(
            CaptureNode(text = "Hidden", visible = false),
            CaptureNode(resourceId = "btn_ok", contentDescription = "OK button", hint = "press"),
            CaptureNode(text = "Order #A1"),
        )))
        assertNotNull(Screens.find(s, Match.Id("btn_ok")))
        assertNotNull(Screens.find(s, Match.Desc("ok")))
        assertNotNull(Screens.find(s, Match.Hint("PRESS")))
        assertEquals(listOf(2), Screens.find(s, Match.Text("#a1"))!!.path)
        assertEquals(null, Screens.find(s, Match.Text("Hidden")))
        Recipes.register(recipe)
        assertEquals(recipe, Recipes.find("com.carrier", "reschedule_delivery"))
    }
}
