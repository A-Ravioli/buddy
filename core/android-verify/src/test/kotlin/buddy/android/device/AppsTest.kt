package buddy.android.device

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The only way into an app on this build is asking for one by name, out loud, so the
 * match has to survive how people actually say it — and has to refuse rather than open
 * something at random.
 */
class AppsTest {
    private val installed = listOf(
        Apps.App("com.monzo.app", "Monzo"),
        Apps.App("com.google.android.GoogleCamera", "Camera"),
        Apps.App("com.whatsapp", "WhatsApp"),
        Apps.App("uk.co.nationalrail", "National Rail"),
        Apps.App("com.banking.other", "Bank of Elsewhere"),
    )

    @Test
    fun `an exact name wins`() {
        assertEquals("com.monzo.app", Apps.match("Monzo", installed)?.packageName)
        assertEquals("com.whatsapp", Apps.match("whatsapp", installed)?.packageName)
    }

    @Test
    fun `the filler words people say are ignored`() {
        assertEquals("com.google.android.GoogleCamera", Apps.match("open the camera", installed)?.packageName)
        assertEquals("com.monzo.app", Apps.match("my Monzo app", installed)?.packageName)
        assertEquals("uk.co.nationalrail", Apps.match("show me national rail please", installed)?.packageName)
    }

    @Test
    fun `a part of the name is enough`() {
        assertEquals("uk.co.nationalrail", Apps.match("national", installed)?.packageName)
        assertEquals("com.banking.other", Apps.match("bank", installed)?.packageName)
    }

    /** Two letters would open something at random, which is worse than saying no. */
    @Test
    fun `too little to go on opens nothing`() {
        assertNull(Apps.match("m", installed))
        assertNull(Apps.match("the app", installed))
        assertNull(Apps.match("", installed))
    }

    @Test
    fun `an app that is not there is not guessed at`() {
        assertNull(Apps.match("Spotify", installed))
        assertNull(Apps.match("the airline", installed))
    }

    /** The shortest match, so "Bank of Elsewhere" never beats a plain "Bank". */
    @Test
    fun `the closest name wins when several contain the word`() {
        val many = installed + Apps.App("com.bank.plain", "Bank")
        assertEquals("com.bank.plain", Apps.match("bank", many)?.packageName)
    }
}
