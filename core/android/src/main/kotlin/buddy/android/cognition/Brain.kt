package buddy.android.cognition

import android.content.Context
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.ledger.LedgerHolder
import buddy.cognition.AnthropicCloudModel
import buddy.cognition.BriefPlanner
import buddy.cognition.CloudModel
import buddy.cognition.TriageRecorder
import buddy.entities.EntityStore
import buddy.triage.RuleTriage
import buddy.triage.TriageProfile
import com.anthropic.client.okhttp.AnthropicOkHttpClient
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
        planner = BriefPlanner(ledger, e, cloudModel(), ZoneId.systemDefault())
        Log.i(BuddyApp.TAG, "brain open; cloud=${planner != null && cloudModel() != null}")
    }

    /**
     * Cloud credentials live in a file the build host provisions into the app's
     * credential-encrypted storage, never in the ledger. No key, no cloud.
     */
    private fun cloudModel(): CloudModel? {
        val keyFile = File(appContext.filesDir, "cloud/api_key")
        if (!keyFile.exists()) return null
        val key = keyFile.readText().trim()
        if (key.isEmpty()) return null
        return AnthropicCloudModel(AnthropicOkHttpClient.builder().apiKey(key).build())
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
