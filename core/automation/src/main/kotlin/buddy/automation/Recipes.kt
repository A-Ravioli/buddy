package buddy.automation

import buddy.policy.ActionSpec
import buddy.policy.BlastRadius
import buddy.policy.Domain
import buddy.policy.Reversibility

/**
 * The recipe registry. Recipes are keyed by package and action; the founder's apps
 * get theirs written against captured screens in Phase 3. The two below are the
 * generic shapes most delivery and retailer apps follow, and the tests run against
 * screens shaped like them.
 */
object Recipes {
    val RESCHEDULE_DELIVERY = ActionSpec(
        "reschedule_delivery", Domain.SHOPPING, Reversibility.SOFT, BlastRadius.EXTERNAL,
        "Move a delivery to another day through the carrier's app. Payload: tracking, day.",
    )
    val START_RETURN = ActionSpec(
        "start_return", Domain.SHOPPING, Reversibility.SOFT, BlastRadius.EXTERNAL,
        "Begin a return for an order in the retailer's app. Payload: order_id, reason.",
    )

    /** A generic carrier app: find the parcel by tracking number, open reschedule, pick a day, confirm. */
    fun genericCarrierReschedule(packageName: String) = Recipe(
        name = "reschedule_delivery",
        packageName = packageName,
        spec = RESCHEDULE_DELIVERY,
        params = listOf("tracking", "day"),
        steps = listOf(
            Step.Launch(packageName),
            Step.WaitFor(Match.Text("{tracking}")),
            Step.Tap(Match.Text("{tracking}")),
            Step.WaitFor(Match.Text("Reschedule")),
            Step.Tap(Match.Text("Reschedule")),
            Step.WaitFor(Match.Text("{day}")),
            Step.Tap(Match.Text("{day}")),
            Step.Tap(Match.Text("Confirm")),
            Step.WaitFor(Match.Text("rescheduled")),
            Step.Verify(Match.Text("{day}"), "confirmation shows the chosen day"),
        ),
    )

    /** A generic retailer app: orders, open the order, start a return, pick a reason, submit. */
    fun genericRetailerReturn(packageName: String) = Recipe(
        name = "start_return",
        packageName = packageName,
        spec = START_RETURN,
        params = listOf("order_id", "reason"),
        steps = listOf(
            Step.Launch(packageName),
            Step.WaitFor(Match.Text("Orders")),
            Step.Tap(Match.Text("Orders")),
            Step.WaitFor(Match.Text("{order_id}")),
            Step.Tap(Match.Text("{order_id}")),
            Step.WaitFor(Match.Text("Return")),
            Step.Tap(Match.Text("Return")),
            Step.WaitFor(Match.Text("{reason}")),
            Step.Tap(Match.Text("{reason}")),
            Step.Tap(Match.Text("Submit")),
            Step.Verify(Match.Text("return"), "a return confirmation is showing"),
        ),
    )

    private val registry = HashMap<Pair<String, String>, Recipe>()

    fun register(recipe: Recipe) {
        registry[recipe.packageName to recipe.name] = recipe
    }

    fun find(packageName: String, action: String): Recipe? = registry[packageName to action]

    fun all(): List<Recipe> = registry.values.toList()
}
