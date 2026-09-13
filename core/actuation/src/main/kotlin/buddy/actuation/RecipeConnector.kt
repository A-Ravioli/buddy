package buddy.actuation

import buddy.policy.Proposal

/**
 * Bridges recipes into the executor. The automation module owns recipes and their
 * runner; this connector is the small adapter that the executor calls, so the
 * actuation module does not depend on automation. The Android layer wires a
 * [RecipeRunnerFacade] over the real runner and driver.
 */
interface RecipeRunnerFacade {
    /** Runs the recipe for [action] in [packageName]. Returns ok, reason, and the trace. */
    fun run(packageName: String, action: String, params: Map<String, String>): Triple<Boolean, String, List<String>>

    /** Which (package, action) pairs have recipes. */
    fun available(): Set<Pair<String, String>>
}

class RecipeConnector(private val runner: RecipeRunnerFacade) : Connector {
    override val actions: Set<String> get() = runner.available().map { it.second }.toSet()

    override fun execute(p: Proposal): Outcome {
        val pkg = p.payload["package"] ?: return Outcome(false, "no package in payload")
        val (ok, reason, trace) = runner.run(pkg, p.spec.name, p.payload - "package")
        // A recipe that stalls has stalled inside the app, which is where the user would
        // have to finish it. "No recipe" is not that: buddy never got there, and opening
        // an app the user did not ask for would be a guess.
        val stalled = if (!ok && reason != "no recipe" && reason != "missing_param") pkg else null
        return Outcome(ok, "$reason; " + trace.joinToString(" | "), needsUserIn = stalled)
    }

    // A recipe's postcondition is its final Verify step; the runner only reports ok if it held.
    override fun verify(p: Proposal, o: Outcome): Boolean = o.ok
}
