package buddy.android.cognition

import android.content.Context
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.ledger.LedgerHolder
import buddy.actuation.Executor
import buddy.android.actuation.CalendarConnector
import buddy.android.actuation.NotificationActionConnector
import buddy.android.actuation.SmsConnector
import buddy.cognition.Agent
import buddy.cognition.AnthropicCloudAgent
import buddy.cognition.AnthropicCloudModel
import buddy.cognition.BriefPlanner
import buddy.cognition.CloudModel
import buddy.cognition.MemoryConsolidator
import buddy.cognition.TriageRecorder
import buddy.entities.EntityStore
import buddy.policy.Domain
import buddy.policy.HardLimits
import buddy.policy.Level
import buddy.policy.PolicyEngine
import buddy.policy.PolicyProfile
import buddy.style.StyleBook
import buddy.triage.RuleTriage
import buddy.tasks.TaskStore
import buddy.triage.TriageProfile
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.OutputConfig
import java.io.File
import java.time.ZoneId
import java.util.Properties

/**
 * Phase 1 cognition on the phone: the triage recorder, the entity graph, and the
 * brief planner, wired to the ledger once it is open. The cloud model exists only if
 * an API key has been provisioned; without it the brief is the deterministic fallback.
 */
object Brain {
    private lateinit var appContext: Context

    @Volatile
    var entities: EntityStore? = null
        private set

    @Volatile
    var triage: TriageRecorder? = null
        private set

    @Volatile
    var planner: BriefPlanner? = null
        private set

    @Volatile
    var profile: TriageProfile = TriageProfile()
        private set

    @Volatile
    var policyProfile: PolicyProfile = PolicyProfile()
        private set

    @Volatile
    var executor: Executor? = null
        private set

    @Volatile
    var tasks: TaskStore? = null
        private set

    /** The holistic agent (docs/07). One loop, woken for a reason, owning its tasks. */
    @Volatile
    var agent: Agent? = null
        private set

    @Volatile
    var memory: MemoryConsolidator? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        LedgerHolder.whenAvailable { open() }
    }

    @Synchronized
    private fun open() {
        if (triage != null) return
        val ledger = LedgerHolder.get()
        val driver = LedgerHolder.driver() ?: return
        val e = EntityStore(driver)
        entities = e
        profile = loadProfile()
        triage = TriageRecorder(ledger, e, RuleTriage())
        val client = cloudClient()
        val zone = ZoneId.systemDefault()
        planner = BriefPlanner(ledger, e, client?.let { AnthropicCloudModel(it) }, zone)
        policyProfile = loadPolicyProfile()
        // Phase 3: domain actions and app recipes join the registry before the act loop sees it.
        buddy.actuation.Actions.register(buddy.money.MoneyActions.PAY_BILL, listOf("payee", "reference"))
        buddy.actuation.Actions.register(buddy.automation.Recipes.RESCHEDULE_DELIVERY, listOf("package", "tracking", "day"))
        buddy.actuation.Actions.register(buddy.automation.Recipes.START_RETURN, listOf("package", "order_id", "reason"))
        val dm = appContext.resources.displayMetrics
        val captureDriver = buddy.android.automation.CaptureDriver(appContext, dm.widthPixels, dm.heightPixels)
        val recipes = object : buddy.actuation.RecipeRunnerFacade {
            override fun run(packageName: String, action: String, params: Map<String, String>): Triple<Boolean, String, List<String>> {
                val recipe = buddy.automation.Recipes.find(packageName, action) ?: return Triple(false, "no recipe", emptyList())
                val r = buddy.automation.RecipeRunner(captureDriver).run(recipe, params)
                return Triple(r.ok, r.reason, r.trace)
            }
            override fun available() = buddy.automation.Recipes.all().map { it.packageName to it.name }.toSet()
        }
        val exec = Executor(ledger, listOf(NotificationActionConnector(appContext), CalendarConnector(appContext), SmsConnector(appContext), buddy.actuation.RecipeConnector(recipes)))
        executor = exec
        val style = loadStyle(ledger)
        val store = TaskStore(ledger)
        tasks = store
        agent = Agent(
            ledger, e, store, PolicyEngine(policyProfile), exec,
            cloud = client?.let { AnthropicCloudAgent(it) },
            zone = zone,
            style = style,
            // The second opinion is a cheap, separate call on a smaller model.
            secondOpinion = client?.let { AnthropicCloudModel(it, model = "claude-sonnet-5", effort = OutputConfig.Effort.LOW, maxTokens = 2_000) },
        )
        memory = client?.let { MemoryConsolidator(ledger, AnthropicCloudModel(it), zone) }
        Log.i(BuddyApp.TAG, "brain open; cloud=${client != null}")
    }

    /**
     * Cloud credentials live in a file the build host provisions into the app's
     * credential-encrypted storage, never in the ledger. No key, no cloud.
     */
    private fun cloudClient(): AnthropicClient? {
        val keyFile = File(appContext.filesDir, "cloud/api_key")
        if (!keyFile.exists()) return null
        val key = keyFile.readText().trim()
        if (key.isEmpty()) return null
        return AnthropicOkHttpClient.builder().apiKey(key).build()
    }

    /** Autonomy levels and hard limits from profile/policy.properties; defaults otherwise. */
    private fun loadPolicyProfile(): PolicyProfile {
        val f = File(appContext.filesDir, "profile/policy.properties")
        if (!f.exists()) return PolicyProfile()
        val p = Properties().apply { f.inputStream().use(::load) }
        fun set(k: String) = p.getProperty(k, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val levels = PolicyProfile().levels.toMutableMap()
        for (d in Domain.entries) p.getProperty("level.${d.name.lowercase()}")?.let { runCatching { levels[d] = Level.valueOf(it.uppercase()) } }
        val defaults = HardLimits()
        return PolicyProfile(
            levels = levels,
            limits = defaults.copy(
                knownPayees = set("known_payees"),
                neverContacts = set("never_contacts"),
                quietStartHour = p.getProperty("quiet_start")?.toIntOrNull() ?: defaults.quietStartHour,
                quietEndHour = p.getProperty("quiet_end")?.toIntOrNull() ?: defaults.quietEndHour,
            ),
            holdMinutes = p.getProperty("hold_minutes")?.toIntOrNull() ?: 10,
        )
    }

    /** The style book from the user's own sent messages, grouped by the counterparty's relationship. */
    private fun loadStyle(ledger: buddy.ledger.Ledger): StyleBook {
        val e = entities ?: return StyleBook.learn(emptyMap())
        val sent = ledger.recent(5000).filter { it.trust == buddy.ledger.Trust.USER && it.kind == buddy.ledger.EventKind.MESSAGE && !it.text.isNullOrBlank() }
        val byClass = sent.groupBy { m ->
            val cp = m.structured["counterparty"] ?: return@groupBy "unknown"
            e.personFor(buddy.entities.Identity.key(cp))?.relationship ?: "unknown"
        }.mapValues { it.value.map { m -> m.text!! } }
        return StyleBook.learn(byClass)
    }

    /** The triage profile, from a properties file the user edits by conversation later. */
    private fun loadProfile(): TriageProfile {
        val f = File(appContext.filesDir, "profile/triage.properties")
        if (!f.exists()) return TriageProfile()
        val p = Properties().apply { f.inputStream().use(::load) }
        fun set(k: String) = p.getProperty(k, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return TriageProfile(
            emergencyActors = set("emergency"),
            closeActors = set("close"),
            mutedActors = set("muted"),
            mutedPackages = set("muted_packages"),
            urgentKeywords = set("urgent_keywords"),
        )
    }
}
