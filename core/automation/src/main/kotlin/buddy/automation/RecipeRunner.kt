package buddy.automation

/** The outcome of a run: where it got to and why it stopped. */
data class RunResult(
    val ok: Boolean,
    /** Index of the step that failed, or the step count on success. */
    val step: Int,
    /** "done", "drift", "timeout", "not_found", "launch_failed", "input_failed", "missing_param". */
    val reason: String,
    val trace: List<String>,
)

/**
 * Interprets a recipe over a driver. Every action step re-reads the screen and finds
 * its node fresh; a Verify that fails is drift and the run aborts. There is no retry
 * loop: a recipe that does not match the app as it is today escalates, and the
 * founder fixes the recipe.
 */
class RecipeRunner(private val driver: Driver, private val settleMs: Long = 400) {

    fun run(recipe: Recipe, params: Map<String, String>): RunResult {
        val trace = ArrayList<String>()
        val missing = recipe.params.filter { it !in params }
        if (missing.isNotEmpty()) return RunResult(false, 0, "missing_param", listOf("missing ${missing.joinToString()}"))

        for ((i, step) in recipe.steps.withIndex()) {
            val r = execute(step, params, trace)
            if (r != null) return RunResult(false, i, r, trace)
            driver.sleep(settleMs)
        }
        trace.add("done")
        return RunResult(true, recipe.steps.size, "done", trace)
    }

    /** Returns null on success, else the failure reason. */
    private fun execute(step: Step, params: Map<String, String>, trace: MutableList<String>): String? = when (step) {
        is Step.Launch -> {
            trace.add("launch ${step.packageName}")
            if (driver.launch(step.packageName)) null else "launch_failed"
        }
        is Step.WaitFor -> {
            val match = fill(step.match, params)
            val deadline = driver.now() + step.timeoutMs
            var found: NodeRef? = null
            while (found == null && driver.now() < deadline) {
                found = driver.screen()?.let { Screens.find(it, match) }
                if (found == null) driver.sleep(250)
            }
            trace.add("waitFor $match -> ${if (found != null) "found" else "timeout"}")
            if (found != null) null else "timeout"
        }
        is Step.Tap -> act(fill(step.match, params), trace, "tap") { driver.tap(it) }
        is Step.Type -> act(fill(step.match, params), trace, "type") { driver.type(it, fill(step.text, params)) }
        is Step.Scroll -> {
            trace.add("scroll ${if (step.down) "down" else "up"}")
            if (driver.scroll(step.down)) null else "input_failed"
        }
        is Step.Verify -> {
            val match = fill(step.match, params)
            val ok = driver.screen()?.let { Screens.find(it, match) } != null
            trace.add("verify ${step.meaning}: ${if (ok) "ok" else "DRIFT"}")
            if (ok) null else "drift"
        }
        Step.Back -> {
            trace.add("back")
            if (driver.back()) null else "input_failed"
        }
    }

    private fun act(match: Match, trace: MutableList<String>, verb: String, f: (NodeRef) -> Boolean): String? {
        val ref = driver.screen()?.let { Screens.find(it, match) }
        if (ref == null) {
            trace.add("$verb $match -> not found")
            return "not_found"
        }
        trace.add("$verb $match")
        return if (f(ref)) null else "input_failed"
    }

    private fun fill(s: String, params: Map<String, String>): String =
        params.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v) }

    private fun fill(m: Match, params: Map<String, String>): Match = when (m) {
        is Match.Text -> Match.Text(fill(m.contains, params))
        is Match.Desc -> Match.Desc(fill(m.contains, params))
        is Match.Hint -> Match.Hint(fill(m.contains, params))
        is Match.Id -> m
    }
}
